# CP3-R6 冻结候选独立架构复审

日期：2026-10-09  
审查方式：只读核对 manifest、日志、源码与测试源码；未运行测试/Maven，未修改源码或测试。  
候选 manifest：`C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP3-r6-candidate.json`  
manifest SHA-256：`08ed2f220fa1f23166f0cd1e1fce63f51318f6769c137fec651b4e80eff0c323`  
tracked patch SHA-256：`96f04a05e1dead4e9962aa405fbb12b76ff678de1fcd1ae4ba3879a3627dc3c1`  
最终测试日志 SHA-256：`883ecd1c5962980224838ad21763877625ad6dfefa5b854b902d6b3ebb70442c`

## 裁决

**R6 可通过本次复审，未发现阻断项。** 冻结身份与 patch/log hashes 相符；`PlannerApplicationService.java`、`PlanAdjustmentService.java` 的哈希与 R5 相同。R6 的 CP3 增量只有测试代码和实施报告：在 proposal 删除失败后的复合任务场景中，增加对真实 SQLite session repository 所存 trip 的检查；未变更生产路径。

新增断言证实：第一次复合动作提交后，数据库中的 day 1 已删除目标站；第二项动作经 pending continuation 预览并应用后，数据库中的 day 2 出现要求添加的 `nextPlace`，且 day 1 的删除仍然保留。断言读的是 `sessions.findAnonymousByAccessToken(...).trip()` 返回的存储快照，不是 Apply response map，因此补上了 R5 仅以版本/回执说明行程结果的证据空档。

该结论限于 Spring test application + SQLite + 单 JVM。它验证了测试环境里持久化 session trip 的两个关键变化及跨步骤保留，不等于生产 PostgreSQL/Redis、进程重启、多节点事务或网络恢复已通过。

## 冻结身份核验

- Manifest、tracked patch 与 final log 的 SHA-256 均匹配指定值。
- Manifest 中 CP3 slice hashes 匹配工作树：`PlannerPreviewUseCaseTest.java` `9b953cbc...3308ff85`、`CP3_IMPLEMENTATION_REPORT.md` `27ae8d46...6bc4b1bd`、`PlannerApplicationService.java` `3d14667d...912e380`、`PlanAdjustmentService.java` `186110d8...f4f9bb6`。
- 两个生产 service 文件 hash 与 R5 一致；全量 tracked patch 也不变，支持“只增加存储内容断言”的范围声明。
- Manifest 报告 fresh isolated copy 中 146 项测试通过，0 failures/errors/skipped；本审查未执行 Maven。启动时 Qdrant localhost 不可用并走降级路径；测试数据库为副本内 SQLite。
- 工作树含 CP0–CP2、用户原有修改和以往审查文档；本次没有覆盖或清理它们，只新增本报告。

## 具体证据

### 1. R6 新增存储内容断言

测试位置：`ai/src/test/java/com/ai/guide/application/planner/PlannerPreviewUseCaseTest.java:304-407`。

- 初始 3 天行程从 `sessions.findAnonymousByAccessToken(token)` 读取；测试记录 day 1 第二个站点的 ID/name，并选择一个不在当前行程名称集合中的渝中区 POI 作为 `nextPlace`（`:306-324`）。它发起“第 1 天删除 …，第 2 天添加 …”复合操作，得到 clarification，再选第一项取得 proposal（`:325-338`）。
- Proposal `remove` 被替身注入抛异常后，Apply 和同 key retry 都成功且 body 相等；receipt 含剩余 pending intent，revision 与 revision count 先各增加 1（`:340-354`）。
- 随后重新从 repository 查 session，断言 `storedStopNamesForDay(storedAfterFirstApply.trip(), 1)` 不含目标名（`:355-358`）。这是对第一次已落库变化的直接断言。
- 新 key 尝试复用旧 proposal 返回 conflict，revision/write count 不变（`:360-365`）。再用 receipt 的 pending intent ID 和“执行下一个”继续 Preview 并 Apply（`:367-377`）。再次从 repository 查询，断言 day 2 包含 `nextPlace`，day 1 仍不含原目标名，version/history 再增加 1（`:378-384`）。这证实第二步既提交了目标新增，也保留第一步的删除。
- Helper `storedStopNamesForDay` 按保存的 `days[*].day` 定位日程，只读取该日 `stops[*].name`（`:394-407`）；未从 proposal body 或 response 拼接预期内容。

### 2. 这能证明和不能证明什么

**可以证明（该隔离测试配置）**：Apply 后通过 session repository 重新读到的 stored trip 已发生真实内容变化；remove failure 下完成第一步仍能保留下一步 continuation；第二步完成后行程内容符合两条原始操作，且版本历史恰好各增加一次。

**仍不能证明**：生产数据库真实事务隔离或写入持久化到磁盘、进程重启后的 pending continuation 恢复、真实 Redis 删除失败/TTL、跨服务副本一致性、响应丢失的 socket 级行为、Qdrant/外部服务正常模式。Manifest 本身也将 R6 定位为 SQLite/单进程 test-only closure。

### 3. CP3 lifecycle 架构没有被 R6 改动

- Application 仍负责 Preview/Cancel/Reset admission 与 Apply 步骤排序（`ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java:115-209`）。Apply 按 replay lookup、领域准备、CAS commit、completion、组装 receipt、登记 replay 顺序执行。
- 唯一共享 lock、continuation/generation/epoch 与 bounded replay authority 仍由一个 `PlanAdjustmentService` 实例持有（`ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java:85-93`）；Preview admission 与 Cancel/Reset 仍使用同一 stripe。R6 生产代码 hash 与 R5 相同，无新增锁或状态 map。
- Apply 在 domain 中完成 CAS（`PlanAdjustmentService.java:923-939`），成功后执行 proposal 清理与 `afterApply` continuation 完成（`:945-964`）；Application 随后构造 receipt/replay（`PlannerApplicationService.java:199-208`）。R6 的 stored-trip assertions 读取这些流程之后的 repository 状态，没有改变这一提交顺序。

### 4. 对 §3.4 的判定及已知限制

R6 加强了 §3.4 “失败结果要检查最终状态、不能仅凭 HTTP/receipt 宣称成功”的行为证据：在模拟 proposal 删除异常时，复合任务两个实质性 trip diff 均从存储快照验证。其余 CP3 边界保持 R5 审查结论：同进程 servlet response filter 丢弃 response 后同 key replay；CAS 成功后 completion 前抛异常时 fail closed、不双写但不回放原成功 receipt；remove failure 由替身注入；不承诺真实 Redis、重启或多节点语义。

剩余架构债务仍非本次阻断项：生命周期 lock/generation/remaining actions/replay 是进程内状态；CAS 和内存 replay 不是同一事务，commit 后到 replay 写入前崩溃可能无法返回相同 receipt；公开 lock callback 与阶段 record 可被内部误用。R6 没有修改这些已披露边界。

## 阻断 / 非阻断矩阵

| 项目 | 结论 | 等级 |
|---|---|---|
| Manifest、patch、log 与 slice 身份 | 与指定值匹配；生产文件 hash 与 R5 一致 | **通过** |
| R6 是否只加 stored-trip assertions | test/report 改动；生产 application/domain 与 patch 未变 | **通过** |
| 第一项后 day 1 target 删除 | 从 repository reread 的 stored trip 中断言目标名不存在 | **通过（SQLite/隔离应用测试）** |
| 第二项后 day 2 `nextPlace` 新增且删除仍保留 | 从 repository reread 的 stored trip 中断言新增存在、旧目标仍不存在 | **通过（SQLite/隔离应用测试）** |
| remove failure 下 continuation、旧 proposal 与版本 | pending intent 续接、旧 proposal 被拒；版本各阶段仅增 1 | **通过（mock port + SQLite）** |
| 多进程/重启/真实 Redis/PostgreSQL | 不在本候选验证范围 | **未验证，明确限制** |
| 146 项 Maven suite | Manifest 记录全绿，本审查未执行 | **未由本审查独立验证** |

## 最终意见

CP3 R6 可通过独立架构复审。存储内容断言直接检查 repo 返回的 trip，足以关闭此前复合任务“receipt/版本成功但站点是否真的持久化变化未证实”的证据缺口；它没有使验证范围扩展至生产数据库、真实 Redis、重启或多实例。未发现本轮引入的生产逻辑问题或超范围声称。

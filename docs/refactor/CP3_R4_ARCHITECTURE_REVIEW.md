# CP3-R4 冻结候选独立架构复审

日期：2026-10-09  
审查方式：只读核对冻结 manifest、原始测试日志、源码与测试源码；未运行 Maven，未修改实现文件。  
候选 manifest：`C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP3-r4-candidate.json`  
manifest SHA-256：`33034cee0e3d1dcccf2d5e61c474d017382f7f586e0b140432d1c289ded29522`  
tracked patch SHA-256：`96f04a05e1dead4e9962aa405fbb12b76ff678de1fcd1ae4ba3879a3627dc3c1`  
测试日志 SHA-256：`eb652531bc19385917b8382d09b4408148206e16b8ae115c711a30c3623bddeb`

## 裁决

**R4 可通过本次架构复审，未发现生产路径阻断项。** Manifest 与 tracked patch、定向测试日志哈希匹配；R4 scope 是新增故障边界测试和实现报告，生产 application/domain 文件哈希与 R3 一致，未改生产代码或 schema。三个新增故障场景的表述总体准确，但应按各自的注入层级理解：

1. **Controller 后响应丢失**由 standalone MockMvc servlet filter 在 Controller 完成后清空响应并抛出 I/O 异常模拟。相同 JVM/service 实例下，同 idempotency key 重试返回成功 receipt，revision/写入数仅增加一次。它不是 TCP/socket 断连、进程崩溃或跨实例重试测试。
2. **CAS 返回成功后方法抛异常**由 spy 调用真实 `commitPreparedApply`（真实 SQLite CAS 已完成）后立即抛错模拟。首次调用对客户端异常；同 key 重试由于没有写入 replay receipt、旧 revision 已过期而返回 conflict，行程不再写入。此行为是 fail-closed、避免双写，但不能恢复首次成功 receipt；测试和报告正确披露了这一边界。
3. **Proposal remove 失败**由 `ProposalRepositoryPort` 替身在 CAS 后抛异常模拟。领域 completion 捕获清理异常、仍推进 continuation/epoch；Application 仍组装并记录成功 receipt，同 key 重试回放相同 receipt，revision/写入数只增加一次。证明范围不包含真实 Redis 故障、Redis L2 tombstone 一致性或进程崩溃。

这与重构计划 §3.4 的要求一致：必须避免重复写入、说明数据库提交与缓存/内存后处理边界，并对响应丢失、提交后缓存失败分别定义可观察结果；计划明确没有要求在未持久化 receipt 的情况下承诺跨进程/重启后回放。CAS-then-throw 后只返回 conflict 而非原成功 receipt，是有意揭示的恢复边界，不是可宣称完整持久幂等。

## 冻结身份核验

- manifest、tracked patch 与测试日志 raw-file SHA-256 均匹配本任务指定值。
- R4 CP3 slice 中 application 与 domain 实现文件 SHA 与 R3 相同；R4 改动位于 `PlannerPreviewUseCaseTest.java` 与 `CP3_IMPLEMENTATION_REPORT.md`。因此新增场景强化测试，不改变生产顺序或故障语义。
- manifest 记录 146 tests、0 failures/errors/skipped，BUILD SUCCESS；本审查没有独立运行测试。运行边界为隔离副本、SQLite、单 JVM；没有真实 Redis/PostgreSQL、多进程或 socket 级网络测试。
- 工作树同时承载 CP0–CP2 与用户既有变更；审查未覆盖、还原或清理它们，只新增本报告。

## 源码架构与提交顺序

### 1. Application workflow 与唯一状态 authority

- `ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java:115-154` 负责 Preview/Cancel/Reset 分流和 admission；普通 Preview 在生命周期锁内授权、校验 base revision、登记 generation ticket，解锁后将 ticket 传入领域规划执行。
- 同文件 `:125-137` 在同一生命周期锁中重读授权 session、核对 revision、reset continuation 并返回 typed outcome。
- 同文件 `:175-209` 在共享锁内排序 Apply 的 session refresh、replay lookup、prepare、CAS commit、complete；随后由 Application 组装成功 receipt 并登记 replay。
- `ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java:85-93` 仍只持有一组 128 stripe lock、一个 continuation authority 及最多 256 条的内存 replay LRU。Application 与 legacy facade 共用该 service 实例，没有复制 epoch/replay 状态。
- `PlanAdjustmentService.java:923-939` 做 expected-revision repository update；CAS conflict 直接返回。`:945-964` 在 CAS 成功后清理 proposal、更新 continuation/剩余任务并返回 completion 数据。缓存清理异常捕获后仍会继续 epoch/receipt 流程（`:950-955`）。

### 2. R4 对失败重试的可观察行为

**响应丢失模拟**

- `ai/src/test/java/com/ai/guide/application/planner/PlannerPreviewUseCaseTest.java:210-260` 使用真实 `PlannerController`、application service、SQLite session repository，通过 standalone MockMvc 请求 apply endpoint。
- Filter 先调用完整 `chain.doFilter`，再 `resetBuffer()` 并抛 `IOException`（`:230-243`），即 Controller 已执行但响应 body 未交付给调用端。之后同一 MockMvc/service instance 用相同 payload/key 重试两次，均 HTTP 200 且两次响应内容相同（`:245-259`）；存储 revision/revision count 只增加一次。
- 结论限于 servlet pipeline 中的响应丢弃和同进程内存 replay。未模拟连接实际断开点、客户端中断时容器写行为、节点切换或进程重启；不能外推为网络/分布式端到端保证。

**CAS 已提交、调用方法后抛异常**

- `PlannerPreviewUseCaseTest.java:262-297` 的 spy 会先 `invocation.callRealMethod()`，使 repository update/CAS 在真实 SQLite 中完成，然后抛出异常（`:280-289`）。这不是“DB commit 内部抛错”的模拟；它精准模拟方法提交成功后、应用层完成/replay 记录之前出现异常。
- retry 使用同一 request/key 返回 `CONFLICT`，且 revision 与 revision count 不再增加（`:291-296`）。这证实不会对 stale revision 再次写入，但 receipt 尚未登记，不能重放首次的成功 body。实现报告将其标为 no durable receipt/刷新后重新预览；该边界诚实，符合 §3.4 对恢复范围明确声明的要求。
- 未覆盖数据库驱动在网络不确定状态下“服务器已 commit、客户端不知道 commit 是否成功”的提交歧义；测试是在单 JVM 里已确认 SQLite CAS 完成后由 Mockito 注入异常。

**Proposal remove 失败**

- `PlannerPreviewUseCaseTest.java:299-335` 将 `proposalWriter.remove(proposalId)` 替换为抛 `IllegalStateException`（`:315-316`），再经 Application Apply 真实走 SQLite CAS。首个 Apply 和同 key retry 都成功且 body 相同；revision/历史写入只增加一次（`:321-327`）。使用新 key 的 stale proposal 请求不能再次写入（`:329-334`）。
- `PlanAdjustmentService.completeCommittedApply` 在捕获清理异常后继续 `continuations.afterApply` 和 completion 组装（`PlanAdjustmentService.java:945-964`）；Application 才随后建 receipt/replay（`PlannerApplicationService.java:203-208`）。该次序正确避免单纯缓存清理故障将已持久化提交伪报成失败。
- 这由 mock/spy proposal port 注入，不代表真实 Caffeine+Redis 实现的删除失败行为已验证；无法证明 Redis tombstone、L2/L1 跨节点一致性、超时/半成功或 Redis 重启后的效果。

### 3. 与 §3.4 逐项对照

- §3.4 #1 的 application 编排及“共享同一个会话状态协调点”符合：Preview admission、Apply 和 Cancel/Reset 都通过原 `PlanAdjustmentService` 的同一 lifecycle stripe/continuation authority，不创建第二个 lock/epoch map。R3/R4 没有改这一生产结构。
- §3.4 #3 顺序保持：所有权 → replay/idempotency identity → revision/proposal/epoch → option/硬约束 → repository CAS → completion/remainder/receipt。具体次序见 `PlannerApplicationService.java:186-208`、`PlanAdjustmentService.java:853-939,945-964`。
- §3.4 #4 的持久化边界有清楚体现：trip CAS 是持久化提交点；proposal 清理、continuation、receipt replay 是后续内存/缓存步骤，非全局事务。
- §3.4 #5 的场景结果现在被分开定义：HTTP body 被 servlet filter 丢弃但进程继续时，同 key 重试回放成功；cache remove 失败仍成功并回放；CAS 后方法异常则不双写，但无 replay receipt，重试 conflict。没有把三种情况笼统声称为“失败重试均返回同一成功回执”。

## 阻断 / 非阻断矩阵

| 项目 | 结论 | 审查等级 |
|---|---|---|
| manifest/patch/log 与关键 slice 身份 | 指定哈希匹配；生产文件与 R3 一致 | **通过** |
| Application Apply/Cancel/Reset 编排与单一 authority | Application 排序步骤；锁/continuation/replay 未分裂 | **通过（单实例）** |
| response loss 同 key retry | Controller 完成后在 servlet filter 清空 body 并抛错；同进程 retry 回放 receipt、无第二次写入 | **模拟验证通过；非 socket/重启测试** |
| CAS 完成后方法抛异常 | 真实 SQLite CAS 后注入异常；同 key retry conflict，不双写但不恢复原 receipt | **行为边界验证通过；不等于 durable idempotency** |
| proposal remove failure | mock port 抛错，CAS 仍返回成功，epoch/replay 接续正常、同 key receipt 一致 | **模拟故障通过；非真实 Redis 演练** |
| CAS 与 receipt 是否原子 | 并非同一事务；commit-then-throw 窗口可能缺失 replay receipt | **明确的非阻断恢复限制，未解决** |
| 多实例/重启 | generation/continuation/replay 为进程内 authority | **未验证/不承诺** |
| Maven/测试独立执行 | manifest 记录 146 项全绿；本次没有运行 | **未由本审查独立验证** |

## 最终意见

CP3 R4 可通过本次架构审查。三个新增场景均明确了注入边界和状态结果；响应丢失测试验证的是同进程内 servlet response 丢弃与 replay，而 CAS-then-throw 场景明确证明“至多写一次”不等于“总能取回同一成功回执”。这一区分满足当前 §3.4 的单实例恢复边界要求。

本轮仍不能宣称持久幂等、真实 Redis 故障恢复、真实网络断连、多节点锁/epoch 一致性或数据库 commit 不确定状态已解决。若产品要求在进程重启、节点切换或 commit-then-throw 后仍返回原成功 receipt，需要单独增加持久 operation receipt/idempotency ledger 并定义事务、保留期和所有权语义；这不属于 R4 的测试增强范围。

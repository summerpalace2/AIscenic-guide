# CP3 R1 冻结候选红方复审

日期：2026-10-09  
候选：`outputs/20261009-CP3-r1-candidate.json`  
声明 JSON SHA-256：`b82a1673e4b2a306eb9fcdc226abd54fab89f75755ed9c842d8bcb3b5a604651`  
声明 patch SHA-256：`30c5764cfd4545dee9303e854f799002aa4fe4ac413258b60d9ff15d0e8338da`  
审查方式：只读核对实现、测试与既存日志；不运行 Maven、不改源代码或共享 `target`。

## 裁决

**总体：BLOCKED（CP3-R1 暂不通过）**。冻结 JSON 与 tracked patch 的 SHA-256 均匹配。Apply 的显式应用层顺序、旧入口共享锁/replay、`forceApply` 幂等区分、CAS 前 option 拒绝、remove 异常继续完成、复合动作延续等都有明显增强；当前未看到这些代码路径必然导致重复正式行程写入。

但发现一处可达的生命周期竞态：应用层 Preview 在授权并取得 `StoredSession` 后，到领域层分配 continuation generation 之前没有注册请求代次或取得共享协调器。若这段时间 Cancel/Reset 先完成，先前已进入应用服务的 Preview 随后会在取消后的状态上创建一个**新的 generation**，用取消前的旧快照生成并发布新 epoch proposal。取消后的 Apply 可接受它，因为 Cancel 不改变数据库 revision，而该 proposal 的 baseRevision 仍等于当前版本。这违反 §3.4 “晚到预览不能重新激活已取消提案”，且会让用户取消后仍能应用被取消的旧任务，属于阻断项。

按要求，**HTTP 响应丢失**与**CAS 后方法异常**分别列为 `NOT VERIFIED`，不算通过，也不是上面的阻断发现本身。

## 冻结身份

- 候选 JSON SHA-256：`B82A1673E4B2A306EB9FCDC226ABD54FAB89F75755ED9C842D8BCB3B5A604651`，匹配声明。
- `outputs/20261009-CP3-r1-tracked.patch` SHA-256：`30C5764CFD4545DEE9303E854F799002AA4FE4AC413258B60D9FF15D0E8338DA`，匹配声明。
- 头部为 `46b1653e81050e3cdd9f23506b327bc6676bcbd8`；审查未发现候选身份变化。

## 阻断发现：授权快照之后、generation 注册之前的 Cancel/Preview 竞态

**源码证据**

- `PlannerApplicationService.previewAdjustment` 先调用 `findAuthorized(actor, sessionId, token)` 并保存 `StoredSession`：`PlannerApplicationService.java:115-124`；之后才检查 base revision，并调用 `previewForApplication(session, request, proposalRepository::put)`：约 `:136-158`。
- 普通 Preview 路径没有进入 `withSessionApplyLock`。generation 在 `PlanAdjustmentService.previewAuthorized` 内才调用 `continuations.begin(session.sessionId())`：`PlanAdjustmentService.java:252-278`。
- Cancel/Reset 则在共享 session lock 内 `continuations.reset(...)`：`PlannerApplicationService.java:125-140`。重置会更新 generation/epoch。
- Proposal 最终写入也只检查 generation 当前性，然后执行 writer：`PlanAdjustmentService.java:448-538`。因此只要旧 Preview 在取消后才 begin，它持有的就是新 generation，后续 commit 会被当成当前请求。

**可控 latch 重现设计（本次未运行）**

1. 用 `PlannerSessionRepositoryPort` 测试替身记录 preview worker 的授权查询结果为 revision 1，并在把该 `StoredSession` 返回给 `PlannerApplicationService.findAuthorized` 前停在 `CountDownLatch`。只阻塞 preview worker；cancel worker 的授权读取正常放行。
2. 用同一 session/token 调用 application Cancel/Reset，等它返回 200（`cancelled:true` 或 `reset:true`），确认数据库仍 revision 1。
3. 释放 preview worker。它用刚才拿到的 revision 1 session 继续，baseRevision 校验通过；进入 `previewForApplication` 后才 `begin()`，所以得到取消之后的新 generation。使用确定性 extractor/strategy 返回可应用 proposal。
4. 断言当前实现的可观察结果：Preview 返回 200 且带 proposalId，proposal writer `put` 一次，proposal baseRevision=1、epoch 是取消后的新 epoch；再用该 proposal 和 revision 1 apply，当前会返回 200 并把数据库升至 revision 2。
5. 正确的生命周期契约应是：该 Preview 识别为取消前发起的旧请求，返回 409（或明确的 superseded outcome），proposal writer 调用 0；其 proposal id 不存在/不可应用，后续 apply 409，数据库保持 revision 1、CAS 写入 0。

现有 `LatePreviewCancellationLifecycleTest.java:27-79` 只在 extractor 中暂停。该暂停发生在 `continuations.begin()` 之后，因此它验证“已登记 generation 的 preview 被 cancel reset 后，迟到 commit 失败”，**没有覆盖 application service 授权读取后、generation begin 前的窗口**。新旧 facade 的顺序 replay 测试也无法覆盖这个窗口。

**给实施者的建议**

- 在 Preview 被接纳时就为其登记 generation/ticket，并保证其登记点与 Cancel/Reset 的 reset 使用同一个生命周期协调器；把 ticket 显式传入领域 preview 直到 commit。Cancel 先完成后，先前已登记的 ticket 必须失效。
- 确保 Preview 的接纳注册与授权快照/revision 读取有可定义的顺序，避免只把 `begin()` 移到另一段无协调的代码而保留竞态。正常授权失败不应取消他人会话中的有效轮次。
- 加入上述 application-level latch 回归：不仅 assert preview status，还 assert proposal writer=0、proposal store get=null、DB revision/trip/revisionCount 完全未变，并验证旧 proposal apply 返回 409 / update=0。

## 其他矩阵裁决

| 场景 | 状态 | 证据与边界 |
|---|---|---|
| Application Apply 显式顺序 | **PASS（代码路径）** | `PlannerApplicationService.java:175-210` 顺序为授权读取→共享锁→授权刷新→幂等 replay/身份冲突→准备与规则拒绝→CAS→提交后完成→receipt/replay。controller 将 typed outcome 映射 HTTP。正常调用下的顺序明确。 |
| Legacy/Application 共用锁与 replay | **PASS（单实例）** | Application 进入 `withSessionApplyLock`（`PlannerApplicationService.java:187`），legacy `PlanAdjustmentService.apply` 使用同一个 `sessionApplyLocks` 数组（`PlanAdjustmentService.java:998-1003`）；二者读写同一 `applyReplays`。`PlannerPreviewUseCaseTest.java` 的两个方向交叉重试覆盖 receipt 与版本只增一次。锁是进程内 stripe，不表示多实例协调。 |
| CAS 前拒绝及零持久写 | **PASS（已覆盖拒绝子集）** | `prepareApply` 先校 revision、proposal归属/TTL/base revision/epoch、option/required stops、canApply/feasibility，再由 application 调 `commitPreparedApply`。`PlannerPreviewUseCaseTest.java` 对 invalid option 与缺 proposal 核验 revision/trip/revision count 不变；owner/session/revision/epoch/硬约束所有组合仍不完整。 |
| 双向 Apply/Cancel 屏障 | **PASS（Mockito 服务级，不是 app/JDBC竞态）** | `CancellationProposalLifecycleTest.java:92-137`、`:140-185` 用 latch 证明两个先后序；分别期待 apply-first 版本只增一次、cancel-first apply 409且 update=0。测试固定在直接领域 service 和 Mockito仓储；缺 application typed flow 与真实 JDBC并发组合。 |
| 已开始 Preview 的迟到结果（generation 已分配） | **PASS（端口替身范围）** | `LatePreviewCancellationLifecycleTest.java` extractor latch 后 cancel，旧 preview 返回 409、writer/update 未调用。不能覆盖本报告阻断的 pre-begin 窗口。 |
| Proposal remove 异常 | **PASS（模拟异常范围）** | `PlanAdjustmentService.completeCommittedApply` 捕获 RuntimeException 后仍执行 continuation/replay路径（`PlanAdjustmentService.java:897-916`）；legacy apply 路径亦 catch。`CancellationProposalLifecycleTest.java:39-67` 模拟 CAS 后 remove 抛错，同 key相同 receipt、update一次。没有真实 Redis/多进程验证，且该 mock用例走 legacy service入口而非 application入口。 |
| forceApply 幂等指纹 | **PASS** | `lookupApplyReplay` 与 `fingerprint` 均包括 `force=`（`PlanAdjustmentService.java:762-786,923-926`）；`PlanAdjustmentServiceTest.java:839-864` 验证同 key不同 force 返回 409、版本不重复增长。 |
| 同进程回放与跨入口 | **PASS（进程内）** | `PlannerPreviewUseCaseTest.java` 验证 app→legacy 和 legacy→app replay body一致、revision只增一次。Replay是最多 256 项内存 Map；跨重启不承诺同 receipt。 |
| 复合动作剩余项 | **PASS（SQLite应用用例）** | `PlannerPreviewUseCaseTest.java:213-266` 首项 apply receipt返回 pendingIntent，继续 Preview/Apply 第二项，revision 1→2→3。队列为进程内，不证明重启恢复。 |
| 同 key 其他字段不匹配 | **PARTIAL** | 当前 force mismatch 有专项测试；尚未看到 proposalId/optionId/revision 分别异值的完整交叉用例。fingerprint 源码包含这些值，静态实现合理，但测试矩阵仍不全。 |
| HTTP 回执丢失 | **NOT VERIFIED** | 无 filter/MockMvc/HTTP层丢响应并重试测试。服务返回后记录同进程 replay 只能说明逻辑预期，不能证明 wire链路、客户端重试或会话桥接效果。 |
| CAS 已提交后方法/进程异常 | **NOT VERIFIED** | 无“JDBC commit 后 wrapper 抛异常”或提交后进程中断故障注入。此时进程内 replay 可能尚未登记；旧 revision原则上阻止重复写，但 receipt、proposal清理、continuation恢复未验证。 |
| Controller status/body 兼容 | **PARTIAL** | `PlannerController` 使用 typed outcome 映射，body map透传；`PlannerControllerTest` 直接调用 controller 检查 token fallback/status/body，非 MockMvc完整 wire；错误字段与所有失败码未全量矩阵验证。 |

## 既存测试结果证据

未执行测试。冻结记录中的隔离测试结果为 142 项、0 failures/errors/skipped，原始日志 `outputs/20261009-CP3-r1-targeted-tests.log` 尾部显示 `BUILD SUCCESS`。该日志只证明实现者此前在隔离副本运行的那套测试；不覆盖本报告新提出的 pre-begin latch 场景、HTTP响应丢失或 CAS后抛异常。

## 最终验收条件

修复并补上“授权查询已返回旧 session、generation 尚未登记 → Cancel 完成 → 旧 Preview 恢复”application latch测试；证明 preview 被拒绝、proposal不落库、apply不写入，之后可重新发起的新 Preview 仍正常。通过前不应把 CP3-R1 标记为全绿。其他 `NOT VERIFIED` 项继续明确保留，不可用现有 142 项全绿替代。

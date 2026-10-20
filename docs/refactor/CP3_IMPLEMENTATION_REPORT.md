# CP3 实施报告：Apply / Cancel 生命周期协调

日期：2026-10-09  
实施范围：`docs/refactor/COLA_REFACTOR_EXECUTION_PLAN.md` §3.4 的 Apply、Cancel/Reset 窄切片。此报告记录实施和本地验证，不是独立验收结论。

## 本次变更

- `PlannerApplicationService` 是新 Apply 与 Cancel/Reset 工作流的实际顺序所有者。Apply 在 application closure 中明确依次执行：持有共享会话锁、刷新已授权 session、查幂等回放、请求身份冲突处理、领域规则准备、CAS commit、提交后状态完成、组装 receipt、登记回放。领域服务提供单步的锁、授权刷新、规则校验、CAS、continuation 更新能力，不再提供完整的 application Apply workflow。
- 共享锁由 `PlanAdjustmentService.withSessionApplyLock` 暴露，仍是唯一既有 128 条 `sessionApplyLocks`。旧 `PlannerService` Apply facade 直接复用相同数组和 `applyReplays`；新 application Apply 也访问同一 replay authority。`PendingIntentContinuations`、epoch、remainingActions 和 generation 仍只有同一实例，没有新增平行锁或状态 Map。
- `PlannerApplicationService.previewAdjustment` 对 `resetConversation` 和领域纯判断 `PlanAdjustmentService.isCancellationRequest` 分流到 app Cancel/Reset 工作流；在同一锁闭包中重新授权、检查最新 revision、reset 共享 continuation authority，再由 app 组装结果。旧 `previewAuthorized` 路径保留兼容的 domain reset facade，并使用同一锁与 continuation。
- 新增 `AdjustmentLifecycleExecution` 和 `ApplyAdjustmentResult`，Application/Adapter 交界不携带数值 HTTP 状态；`PlannerController` 负责 outcome→HTTP 映射。旧 `PlannerService` 入口暂保留兼容。
- Apply 顺序由 application 代码直接表达为“授权会话刷新 → 幂等请求匹配 → revision → proposal session/TTL/epoch → option/required stop/约束 → repository CAS → proposal 清理/epoch 旋转/剩余队列 → receipt/replay”。缓存清理在 CAS 后失败时捕获并记录错误，继续轮换 epoch、处理 continuation 和生成 receipt，避免已提交行程被报告为未提交或丢失剩余队列。`completeCommittedApply` 除明确捕获 proposal remove 故障外，其他后提交步骤异常继续向上抛出，不返回伪成功；此时可能已提交但无 receipt 的恢复限制登记为未验证风险。
- 明确将 `forceApply` 纳入幂等 fingerprint。同 key、不同 force 现在返回 409，而不是把上次强制应用的 receipt 回放给语义不同的请求。这是有测试覆盖的行为修正。其它 fingerprint 字段及“同进程、最多256项内存 replay”承诺保持。

## 基线与测试证据

起点冻结见 `outputs/20261009-CP3-start.json`；起点 HEAD 为 `46b1653e81050e3cdd9f23506b327bc6676bcbd8`，原 tracked dirty patch SHA-256 为 `5bfe8d15042827e6b67264e07008fe61dfb53ca37a3bd8e3b0b49a72886484e4`。实现期间未 reset/clean 主工作区、未覆盖 CP0–CP2 或用户变更，也未使用共享 `ai/target`。

CP3 起始干净副本定向基线：110 tests，0 failures/errors/skipped，BUILD SUCCESS。返工后的最终命令在隔离临时副本 `%TEMP%\scenic-guide-cp3-r1-20261009` 执行；`clean` 只作用于该副本，SQLite 使用独立文件 `%TEMP%\cp3-r1-final2-20261009.db`：

```powershell
Set-Location "$env:TEMP\scenic-guide-cp3-r1-20261009"
.\mvnw.cmd clean test -DfailIfNoTests=true `
  "-Dknowledge.database.type=sqlite" "-Dknowledge.database.url=jdbc:sqlite:$env:TEMP\cp3-r1-final2-20261009.db" `
  "-Dspring.ai.openai.api-key=refactor-test-placeholder" "-Dspring.ai.openai.base-url=http://127.0.0.1:1" `
  "-Damap.web-service.key=" "-Damap.web-service.base-url=http://127.0.0.1:1" `
  "-Dalibabacloud.dashscope.api-key=" "-Dalibabacloud.dashscope.embedding-url=http://127.0.0.1:1" `
  "-Dbailian.rerank-url=http://127.0.0.1:1" "-Dbaidu.api-key=" "-Dbaidu.secret-key=" "-Dqdrant.api-key=" `
  "-Dspring.data.redis.host=127.0.0.1" "-Dspring.data.redis.port=1" "-Dqdrant.host=127.0.0.1" "-Dqdrant.port=1" `
  "-Dtest=PlannerPreviewUseCaseTest,PlannerControllerTest,CancellationProposalLifecycleTest,LatePreviewCancellationLifecycleTest,PlanAdjustmentServiceTest,PlannerSafetyBoundaryRegressionTest,RedTeamAdversarialAssaultTest,ConversationContinuationRegressionTest,CompoundTimeAndCancellationTest,ColaArchitectureGuardTest"
```

结果：上一冻结版为141项全绿。本次按架构复审返工后在隔离副本执行：**142 tests, 0 failures, 0 errors, 0 skipped；BUILD SUCCESS**。最终原始日志见 `outputs/20261009-CP3-r1-targeted-tests.log`；本轮首次命令参数引用错误造成的 Maven 插件解析诊断日志单独保留在 `outputs/20261009-CP3-r1-argument-diagnostic.log`，未计入测试结果。测试启动时 Qdrant 连接 `127.0.0.1:1` 被拒并走本地明确降级；没有验证真实 Qdrant/Redis。

新增或扩展的行为证据：

- `CancellationProposalLifecycleTest`：Apply 先进入 CAS、Cancel 排队，Apply 成功且版本只增一次，Cancel 以旧 revision 冲突；对称的 Cancel 先完成/Apply 排队场景取消成功、旧 proposal Apply 409、repository update 为0。两项 barrier 都是确定性 latch 顺序，但 fixture 为 Mockito 仓储，不等同真实双线程数据库集成。
- `LatePreviewCancellationLifecycleTest`：在 intent extraction latch 暂停旧 Preview，执行 Cancel 后释放；旧 Preview 返回 409，proposal writer 未调用、session 仓储未写入。
- `PlannerPreviewUseCaseTest`：应用层取消使旧提案失效且 SQLite 中 trip/revision/revision-count 不变；缺提案与无效 option 在 CAS 前拒绝且 revision/write count 不变；显式 actor Apply 成功后版本只增1，同幂等请求重试返回相同 receipt；两动作复合任务第一项 Apply 后 receipt 返回 pendingIntent，继续 Preview/Apply 第二项后版本由1增至3；旧 PlannerService facade 与 application 新入口交叉重试共享同一幂等回放与锁，两个调用方向都只写一次。
- `CancellationProposalLifecycleTest`：模拟 CAS 成功后 proposal `remove` 抛异常；仍返回成功 receipt，同 key 重试得到相同 receipt，仓储 update 仅一次。
- `PlanAdjustmentServiceTest`：相同幂等键但 `forceApply` 不同返回 409，当前版本仍只增加一次。
- `PlannerControllerTest`：验证 Apply 请求 token header fallback 与 typed outcome HTTP 映射；不是 MockMvc/真实 HTTP response filter 故障注入。

## CP3 / 红队必需项状态

| 场景 | 状态 | 证据与边界 |
|---|---|---|
| Apply-first 与 Cancel-first 两种可控顺序 | **通过（单服务/Mockito 仓储）** | 两个 latch 屏障用例；CAS 写入最多一次/取消优先时零写。真实并发 SQLite/CAS 压测未做。 |
| Cancel 完成后旧 proposal 不可应用；拒绝无写入 | **通过** | application 用例以 SQLite 检查 trip、revision 和 revision count 未变化；Apply 返回 typed conflict。 |
| Apply 成功后复合任务剩余项可续接 | **通过（SQLite 应用用例）** | 第一项 receipt 返回 pendingIntent，第二项继续并应用，revision 1→2→3。 |
| 迟到 Preview 不能在取消后重新发布旧 proposal | **通过（协调/端口替身）** | 可控 extractor latch，旧 Preview 409，proposal writer 与 session update 均未调用；非 Redis 集成。 |
| 同进程相同幂等键重试及新旧入口交叉重试 | **通过** | Application 实际用例重试返回同一 receipt；legacy→application 与 application→legacy 都共享唯一 replay authority，SQLite 版本不重复增加。内存 replay 上限及生命周期保持既有约定。 |
| 同键、不同请求身份 | **部分** | 新增只 forceApply 不同矩阵项并返回 409；proposalId/optionId/revision 的完整交叉矩阵未新增。 |
| 提交后 proposal cache remove 异常 | **通过（模拟缓存端口）** | CAS 后 `remove` 抛异常仍完成 epoch/队列/receipt，重试不重复写。没有真实 Redis/L2 故障演练。 |
| DB 已提交但 HTTP 回执丢失 | **部分/未验证网络层** | 正常返回后同进程重试覆盖相同 receipt；没有 HTTP filter 丢弃响应的测试。若进程在 CAS 后、replay 写入前崩溃，当前内存 replay 无法提供持久 receipt；重试依靠 revision/CAS 拒绝二次提交，客户端需刷新并重新预览。 |
| CAS 已提交但方法返回前抛异常 | **未验证** | 不与 HTTP 响应丢失混为一谈；需后续仓储“先 commit 后 throw”故障注入。 |
| 非所有者使用相同幂等键、跨会话提案、过期/硬约束拒绝矩阵 | **部分** | 既有领域测试覆盖部分会话、revision、过期与硬约束场景；本切片未新增完整 application/HTTP 与 DB 副作用交叉矩阵。 |
| 多进程/重启、真实 Redis tombstone、真实 PostgreSQL | **未验证/不承诺** | `PendingIntentContinuations`、generation/epoch、remainingActions 与 replay 为进程内状态；CP3 没有增加跨节点协调或持久幂等表。 |

## 触及文件与行为边界

代码和测试触及文件详见最新冻结清单。未改 BFF、Frontend、formal save/open、外部 schema；未改数据库结构。回切时撤销 CP3 专属差异（`PlannerApplicationService`、`PlanningApplicationService`、`ApplyAdjustmentResult`、`AdjustmentLifecycleExecution`、`PlannerController`、`PlanAdjustmentService` 与 CP3 tests），保留冻结起点上的 CP0–CP2 与用户既有改动。若回切 force fingerprint 行为，需同时恢复其测试并重新评估同幂等键不同 force 的风险。

## 经验使用记录

经验 Wiki 检索没有命中适用于 application/domain 生命周期拆分的主题；未采用既有经验作为实现依据。本次按当前 CP3 baseline/red-team 和源码事实完成 workflow 拆分，验证依赖隔离 clean targeted suite。任务收据为 `20261009-caf15010a077`，效果评估为 `not_used`；不据此声称自动化同步或经验带来收益。


## CP3 R2 — Preview admission / Cancel generation race closure

R2 closes the R1 red-team pre-begin race: application preview now enters the existing `PlanAdjustmentService.withSessionApplyLock(sessionId)` before actor/session authorization, revision validation, and registering a `PreviewGenerationTicket` on the existing `PendingIntentContinuations` instance. It releases the lock before the costly extraction/planning work and passes that ticket to the domain preview executor. Cancel/Reset and Apply continue using the same fixed-stripe lock and continuation authority; there is no parallel lock or generation map. Thus preview admission first means a later cancel resets/fences its ticket before it can publish; cancel first means a subsequent preview is a new request admitted against the post-cancel state. Legacy domain preview still uses the same lock for its own session refresh, revision check, and ticket registration. The legacy three-argument typed preview entry remains compatible and performs its own admission.

Added `PlannerPreviewUseCaseTest.previewTicketAdmissionAndCancelShareOneLinearizationBoundary`: a latch pauses the application at ticket registration while it holds the shared lock; a cancel request starts and is shown not to complete until registration is released; extraction then pauses while Cancel resets the shared continuation; after extraction is released the stale Preview returns conflict, proposal writer is never called, a stale Apply is rejected, and SQLite trip/version/revision count remain unchanged. The same test then submits a new preview after Cancel and verifies it is not rejected as stale. This is a deterministic single-JVM lifecycle test with the real SQLite planner session repository and a spied proposal port; it does not simulate a multi-process deployment or live Redis.

The first isolated compile attempt found four effectively-final lambda capture errors after the admitted session was rebound. They were corrected by capturing a final admitted-session snapshot. Diagnostic log is preserved separately and is not counted as a passing run. Final isolated clean targeted command used a fresh copy at `%TEMP%\\scenic-guide-cp3-r2-20261009-3`, with independent SQLite `%TEMP%\\cp3-r2-20261009-3.db`, and the same CP3 targeted class list recorded in the R2 candidate manifest. Result: **143 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. Raw log is `outputs/20261009-CP3-r2-targeted-tests.log`. Qdrant at localhost port 1 followed its configured startup degradation path; Redis/PostgreSQL, multi-process locking, and HTTP response-drop behavior were not tested.

R2 is frozen for independent review, not self-accepted. Candidate identity: `outputs/20261009-CP3-r2-candidate.json`; raw tracked patch: `outputs/20261009-CP3-r2-tracked.patch`. The manifest records the raw `git diff --binary HEAD` SHA-256 plus slice file hashes and complete untracked-file hashes to retain CP0–CP2 and preexisting dirty state in the identity.

## CP3 R3 — deterministic synchronization proof for both lock orders

R3 addresses C's partial review of the R2 barrier test without changing production behavior. The test no longer uses `Thread.sleep`. A Mockito spy signals when the later operation reaches the shared `withSessionApplyLock` call, and the test captures its thread and observes the intrinsic-monitor `BLOCKED` state within a bounded deadline before releasing the lock owner. For preview-first, preview pauses after registering its ticket while still holding the lock; cancel is observed blocked on that monitor, then proceeds after admission release and invalidates the in-flight ticket before extraction finishes.

For cancel-first, the test pauses `resetAuthorizedContinuation` after invoking the real reset while cancel still holds the shared lock. It starts a new preview, waits until that thread reaches the same lock boundary, observes the preview thread in `BLOCKED` state, then releases cancel. Cancel succeeds; the subsequent preview is asserted `SUCCESS` with a nonempty `proposalId` and `canApply=true`, then the test applies it and verifies the SQLite revision increments exactly once. No HTTP response-drop or CAS-commit-then-throw tests were added; those remain explicitly unverified.

The entire CP3 targeted suite was rerun from a fresh isolated copy `%TEMP%\\scenic-guide-cp3-r3-20261009-2`, with independent SQLite `%TEMP%\\cp3-r3-20261009-2.db`: **143 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. Command/class list is in the R3 candidate manifest and raw log is `outputs/20261009-CP3-r3-targeted-tests.log`. R3 candidate replaces R2 for review: `outputs/20261009-CP3-r3-candidate.json`, raw patch `outputs/20261009-CP3-r3-tracked.patch`. It is frozen for B/C review, not self-accepted.

## CP3 R4 — post-commit and response failure boundaries

R4 adds failure-boundary evidence without changing production code or schema.

- `droppedControllerResponseCanBeRetriedWithoutSecondCommit` uses the real `PlannerController` and application service in standalone MockMvc. A one-shot servlet filter executes the apply endpoint, clears the response body, then raises an injected I/O failure to represent a response lost after controller completion. The SQLite session already has revision 2 and one added revision; retrying the same idempotency key returns HTTP 200 with the same receipt on repeated retry, and the final revision/write count remains one. This verifies controller/application behavior at the servlet boundary, not an actual socket disconnect or process crash.
- `postCasExceptionDoesNotDoubleWriteButCannotReplayUnrecordedReceipt` wraps the real `commitPreparedApply`: it lets the repository CAS return after its real SQLite write, then throws before application completion/replay recording. The first call throws; SQLite is at revision 2 with exactly one new revision. Retrying the same request returns typed conflict (HTTP adapter mapping is separately covered) because the proposal/revision is stale; revision and write count remain unchanged. This explicitly records the limit: no durable receipt is available when failure interrupts after CAS but before replay registration; the current contract prevents a second write but does not promise the same success receipt in this window. No persistent idempotency schema was added.
- `proposalRemovalFailureStillReturnsAndReplaysCommittedReceipt` injects a ProposalRepositoryPort remove failure after CAS. The existing completion boundary catches it, still returns success, rotates continuation state, and registers replay. Same-key retry returns the identical receipt; a different key cannot reuse the retained stale proposal; SQLite revision/write count advances exactly once. This is application+SQLite evidence with a fault-injected proposal port, not real Redis.

The full CP3 targeted suite was rerun from isolated clean copy `%TEMP%\\scenic-guide-cp3-r4-20261009-2` with independent SQLite `%TEMP%\\cp3-r4-20261009-2.db`: **146 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. Initial test compilation required casting the filter's ServletRequest to HttpServletRequest before reading its header; that test-only compile issue was corrected before the passing run. The raw log and exact command are recorded in the R4 candidate manifest. Actual network-level response loss, process death after CAS, live Redis/PostgreSQL, and multi-process replay remain unverified. No CP4/BFF changes were made. R4 is frozen for B/C review, not self-accepted.

## CP3 R5 — stronger response-loss and compound-continuation assertions

R5 is a test-only response to C's R4 review. The response-loss filter now wraps the servlet response in `ContentCachingResponseWrapper`, runs the real controller/application, discards the buffered response, then throws an injected `IOException`. The test asserts the caught exception cause chain contains that exact failure class and that the one-shot drop flag was consumed; SQLite revision checks still prove the controller completed the first apply before the simulated response disappeared. The first R5 clean attempt exposed that calling `resetBuffer` on the raw MockMvc response can itself throw after it is committed, so that failed diagnostic run is preserved separately; the wrapper-based rerun passes.

The proposal-removal-failure scenario now uses a two-action compound request. With the first proposal's `remove` failing after CAS, its receipt is successful and replayed; the receipt carries a pending continuation ID. The stale first proposal is rejected under a new idempotency key. The remaining action is then previewed from the pending continuation and applied successfully; SQLite revision advances from 1 to 2 to 3, proving `afterApply` preserved and advanced the remaining queue despite the proposal cleanup failure. No production API or reflection was added.

Fresh isolated clean CP3 suite `%TEMP%\\scenic-guide-cp3-r5-20261009-2`, independent DB `%TEMP%\\cp3-r5-20261009-2.db`: **146 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. The exact command, final log, R5 diagnostic log, and candidate hashes are captured in `outputs/20261009-CP3-r5-candidate.json`. Network-level socket loss, process crash, live Redis/PostgreSQL and multi-process durability remain unverified. Production code and schema remain unchanged; R5 is frozen for B/C re-review, not self-accepted.

## CP3 R6 — assert persisted compound trip contents

R6 is a test-only closure for C's last R5 review comment. In `proposalRemovalFailureStillReturnsAndReplaysCommittedReceipt`, after the first compound action the test reads the actual stored planner session from SQLite and asserts the requested day-one stop is absent. After the second action it reads the stored session again and asserts the requested place exists in day 2 while the removed target remains absent from day 1. The helper reads `trip.days[*].day` and `trip.days[*].stops[*].name`; the assertions inspect repository state rather than response text. No production files changed.

Fresh isolated clean copy `%TEMP%\\scenic-guide-cp3-r6-20261009`, using the default SQLite file `%TEMP%\\scenic-guide-cp3-r6-20261009\\ai\\data\\knowledge.db` created inside that copy: the same CP3 10-class suite completed with **146 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. Raw log and exact command are captured in the R6 candidate manifest. This remains single-JVM SQLite evidence; the test startup logged Qdrant localhost unavailable and entered its documented degraded mode. Response loss is a servlet-filter simulation, not a socket disconnect; live Redis/PostgreSQL, process crash, and multi-process behavior remain unverified. R6 is frozen for B/C re-review, not self-accepted.

## CP3 R7 — prove compound action timing and semantic continuation payload

R7 adds only assertions in `PlannerPreviewUseCaseTest.proposalRemovalFailureStillReturnsAndReplaysCommittedReceipt` plus this report. After the first apply, the persisted day-2 stops are asserted not to contain `nextPlace`, ruling out the second action being committed early. The successful receipt's actual `pendingIntent.intent` JSON currently carries the remaining clause in `clarificationQuestion` prose; `PlanAdjustmentIntent.asMap()` does not expose `compoundActions` or direct `requestedVenueName` for that wrapper. The test extracts the preserved second-action line from that JSON field and passes it through the existing `ConversationIntentClassifier`, then asserts dayNumber 2 and requestedVenueName equals `nextPlace`. This verifies the currently serialized continuation content without changing production/wire behavior. It does not establish that the API exposes those values as dedicated JSON fields; that remains a separate contract limitation.

A first focused probe that asserted direct `intent.dayNumber` and `intent.requestedVenueName` failed and is preserved as a diagnostic; its observed JSON was a CLARIFICATION wrapper with dayNumber 1 and the second action inside clarificationQuestion. The revised focused test passed. The final clean 10-class run used fresh isolated copy `%TEMP%\\scenic-guide-cp3-r7-20261009-1` with its own default SQLite under `ai/data/knowledge.db`: **146 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. Qdrant localhost was unavailable and the documented degraded mode was used. Response loss remains a servlet-filter simulation; live Redis/PostgreSQL, process crash, and multi-process behavior remain unverified. R7 is frozen for B/C re-review, not self-accepted.

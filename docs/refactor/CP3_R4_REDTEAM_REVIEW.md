# CP3 R4 红方复审

日期：2026-10-09  
范围：只读复审 R4 新增的三类 Apply 故障边界测试；未运行测试，未修改源码或测试。  
候选 manifest SHA-256：`33034cee0e3d1dcccf2d5e61c474d017382f7f586e0b140432d1c289ded29522`（匹配）  
tracked patch SHA-256：`96f04a05e1dead4e9962aa405fbb12b76ff678de1fcd1ae4ba3879a3627dc3c1`（匹配）  
已有测试日志 SHA-256：`eb652531bc19385917b8382d09b4408148206e16b8ae115c711a30c3623bddeb`（匹配；只读取日志）

## 裁决

**三类故障场景：总体 PARTIAL（无已发现的重复写入阻断缺陷）。**

- **Servlet 层响应丢失后同幂等键重试：PASS（MockMvc/SQLite 范围）**。测试确实经过真实 `PlannerController`、应用服务和 SQLite 写入；过滤器位于 controller chain 返回之后清空响应缓冲并注入 I/O 异常；重试返回成功回执，重复重试回执相同，最终 revision/写入记录仅增加一次。它不是实际 socket/TCP 断连测试。
- **真实 CAS 完成后、应用服务完成前抛异常：PASS（SQLite/单 JVM 范围，回执恢复 PARTIAL）**。真实 `commitPreparedApply` 先完成 CAS，spy 随后抛异常；数据库 revision 和 revisionCount 已增加一次；同请求重试得到冲突且提交调用次数仍为一次。此路径刻意不承诺成功回执可重放，因为异常发生在 receipt/replay 登记之前。
- **CAS 后 proposal remove 抛异常：状态/receipt 防重放 PASS；epoch 直接断言 PARTIAL**。异常发生于 proposal writer；实现捕获后继续 `afterApply`、组装 receipt 和登记 replay；相同 key 返回相同 receipt，数据库只写一次。旧 proposal 的新 key 尝试被拒绝，但测试使用更新后的 baseRevision，revision 不匹配本身就足以导致冲突，因此这条断言没有单独证明 epoch 已轮换，也未断言 continuation 中剩余动作/epoch 的精确值。

## 逐项证据与局限

### 1. Controller 完成后丢弃响应，再用原幂等键重试

测试：`ai/src/test/java/com/ai/guide/application/planner/PlannerPreviewUseCaseTest.java:210-260`，`droppedControllerResponseCanBeRetriedWithoutSecondCommit`。

- 先通过真实应用服务创建可应用 proposal，再构造包含同一 `idempotencyKey` 的 JSON payload。使用 `MockMvcBuilders.standaloneSetup(new PlannerController(applicationService))`，所以请求走真实 controller 和 application service，但没有启动真实 HTTP socket。
- Filter 先调用 `chain.doFilter`；只有 controller 已返回后，才执行 `resetBuffer()` 并抛 `IOException`（测试 230-238 行）。请求带 `X-Test-Drop-Response:true`，one-shot `AtomicBoolean` 确保只丢弃首次响应。
- 首次 `perform` 断言抛异常（241-243 行），之后从 SQLite 查到 version +1、revisionCount +1（245-247 行）。同一 payload/key 随后获得 HTTP 200；第二次相同 key 重试响应文本一致，并包含 `applied:true` 与 `currentVersion:2`，最终 version/revisionCount 仍只增加一次（249-259 行）。这组断言覆盖了持久副作用与 retry receipt。
- **断言强度观察**：首次请求用 `assertThrows(Exception.class)`，未校验异常根因恰为 filter 注入的 I/O 异常，也未显式断言 `dropOnce` 已从 true 翻转。后续 DB + 同 key 回执证据强烈支持首个 Apply 已提交并有 replay，但更稳妥的测试可断言异常链包含预期注入消息，或显式断言 drop flag 已消费。当前不构成重复写入缺陷。
- **边界**：测试模拟 servlet/filter 层的响应丢弃，不模拟客户端 socket 断开、代理缓冲、进程中断或应用重启。幂等记录是当前进程内行为；跨进程/重启可用性不在本证据范围。

### 2. CAS 已提交后、receipt/replay 登记前抛异常

测试：`PlannerPreviewUseCaseTest.java:262-297`，`postCasExceptionDoesNotDoubleWriteButCannotReplayUnrecordedReceipt`。

- Spy 对真实 `commitPreparedApply` 执行 `invocation.callRealMethod()` 后抛异常（280-283 行）。真实方法使用仓储更新 CAS；测试在异常后直接读取 SQLite，验证 currentVersion 与 revisionCount 各增加一次（285-289 行）。因此失败点位于真实提交后，不是 CAS 前模拟失败。
- 同一 request/idempotency key 再调用 application service，结果是 typed `CONFLICT`；revision 与 revisionCount 不再变化，并验证 `commitPreparedApply` 仅调用一次（291-296 行）。这证明失败关闭与 no-double-write。
- 测试没有声称能恢复成功 receipt；方法名、断言与实施报告均说明异常发生在 receipt/replay 登记之前，后续只能冲突。没有真实进程崩溃/重启，亦没有 HTTP 5xx 的端到端断言；Controller outcome→HTTP 映射由独立测试覆盖，但这条异常路径本身在 application service 层。

### 3. CAS 成功后 proposal remove 异常

测试：`PlannerPreviewUseCaseTest.java:299-335`，`proposalRemovalFailureStillReturnsAndReplaysCommittedReceipt`。生产实现：`PlanAdjustmentService.java:945-964`。

- Proposal port 的 `remove(proposalId)` 被注入 `IllegalStateException`（315-316 行）。Apply 仍返回 `SUCCESS`；相同请求重试得到与首次相同的 body；SQLite version/revisionCount 只增加一次；remove 调用一次（321-327 行）。这是正确捕获清理异常并保留同进程 receipt 的直接证据。
- 使用不同幂等 key 和更新后的 baseRevision 再试留存 proposal，得到 `CONFLICT`，revision 不变（329-334 行）。这证明不重复应用，但由于旧 proposal 的原 baseRevision 已过期，该用例不能区分“epoch 正确失效”与“revision 检查先拒绝”。
- 实现 `completeCommittedApply` 明确捕获 `proposalStore.remove` 的 `RuntimeException` 后继续调用 `continuations.afterApply(sessionId, committedVersion)`，然后计算 pending continuation（`PlanAdjustmentService.java:950-963`）。静态代码顺序支持 epoch reset 的实现意图，但新增测试没有读取 epoch、没有在 revision 一致的受控条件下单独证明 epoch 失效，也没有断言有剩余复合任务时 pending intent 被保留。因此“receipt 及不重复写”通过，“epoch/剩余任务行为”仅部分验证。
- 这是 fault-injected port 测试，不是 Redis/L2 实际删除故障或重启恢复测试。

## 日志、身份与既有报告

- R4 manifest、tracked patch、targeted test log 的 SHA-256 均与委托的固定身份一致；manifest 所列 CP3 关键切片文件身份亦对应本轮审查版本。
- manifest 和已有日志记录隔离副本运行 **146 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**。本次没有重跑。
- 实施报告 `docs/refactor/CP3_IMPLEMENTATION_REPORT.md:87-95` 对 servlet 模拟、CAS 后异常、proposal remove 故障以及未覆盖的真实网络/进程/Redis 边界均有说明。其“epoch rotation”文字强于 remove-failure 测试本身的直接断言，应按上面所列证据范围理解。

## 剩余边界

1. R4 已测试 servlet filter 模拟，而非真实客户端连接/代理断开；也未覆盖重启后的幂等 receipt 持久化。
2. CAS 后异常只证明 SQLite 一次写入和后续拒绝重复写，不保证同一成功 receipt 可取回。
3. Remove 故障用异常 mock 端口验证，不是 Redis/缓存适配器故障演练；epoch 轮换与复合队列存活尚未由此专项测试独立断言。
4. 对三类故障注入，本轮未发现会导致第二次数据库写入的缺陷；建议后续仅补强首次响应异常根因断言，以及 remove 故障用例对 continuation/epoch 的专门断言，再将这部分由 PARTIAL 提升为完整证据闭环。

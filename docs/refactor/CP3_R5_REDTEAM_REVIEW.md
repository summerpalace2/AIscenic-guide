# CP3 R5 红方复审

日期：2026-10-09  
范围：只读复审 R5 新增/加强的响应丢失、CAS 后异常、proposal remove 故障测试；未运行测试，未修改代码。  
Manifest SHA-256：`b73004edcef0e53dafda352bfba6f336f1c8fe010d755a754695ed24e4eaba62`（匹配）  
Tracked patch SHA-256：`96f04a05e1dead4e9962aa405fbb12b76ff678de1fcd1ae4ba3879a3627dc3c1`（匹配）  
Final clean test log SHA-256：`a94f870e63ce63f14c613b465eb4dc9dd18a852eaac44fde3f0db93d8fc97acf`（匹配；仅读取）

## 裁决

**R5 总体：PARTIAL（两种提交故障无重复写入证据通过；proposal 清理失败后复合任务续接可继续，但缺少对预期行程内容的精确断言）。**

- 响应丢失：**PASS（Servlet/MockMvc 模拟范围）**。缓存响应 wrapper 让 controller/application 正常执行并写入缓存响应；filter 分支不调用 `copyBodyToResponse()`，而是清空 wrapper buffer 并抛出测试指定的 `IOException`。异常原因、one-shot flag、SQLite revision 和同幂等键重复回执均有断言。真实 socket/TCP 断连仍未覆盖。
- CAS 后异常：**PASS（SQLite 单 JVM 范围，成功回执恢复不保证）**。在真实 `commitPreparedApply` 完成仓储写入后注入异常；数据库增版一次，重试冲突且提交方法只执行一次。异常发生在登记 replay 前，测试没有声称可返回原成功回执。
- remove 故障和复合续接：**部分通过**。失败的清理调用后成功 receipt 含 pendingIntent；同 key 回放相同 receipt；第一项版本只增一次；旧 proposal 用新 key 被拒绝；随后通过该 pendingIntent 预览和应用第二项，服务返回 SUCCESS，版本/revision 再增一次。局限是未断言第二项应用后的 trip 内容确实包含预期新增地点/移除目标，也未断言 continuation 的 intent 内容明确等于原复合请求第二项。旧 proposal 的拒绝由 stale revision 足以解释，不能单独作为 epoch 轮换证据。

## 身份和测试证据

R5 manifest、tracked patch、final clean log 哈希与委托身份一致；manifest 中 `PlannerPreviewUseCaseTest.java`、应用服务、领域服务和实施报告切片哈希与当前文件一致。manifest/日志记录隔离副本 **146 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**。这是读取既有日志，本审查未复跑测试。

相关实现报告位于 `docs/refactor/CP3_IMPLEMENTATION_REPORT.md:97-103`，R5 测试位于 `ai/src/test/java/com/ai/guide/application/planner/PlannerPreviewUseCaseTest.java:211-377`。

## 1. 响应 wrapper、提交与同 key 回执

测试 `droppedControllerResponseCanBeRetriedWithoutSecondCommit`（测试 211-265 行）：

1. 使用真实 `PlannerController` 与 Spring application service 的 `MockMvcBuilders.standaloneSetup`，调用真实 Apply POST 路由；并非只调用 service 方法。
2. `ContentCachingResponseWrapper` 包装原始 servlet response，再执行 `chain.doFilter`。Filter 在 controller chain 返回之后判断 one-shot header。命中后调用 `buffered.resetBuffer()` 并抛 `IOException`，而该分支没有调用 `copyBodyToResponse()`；只有非丢弃分支才复制缓存 body（测试 231-239 行）。因此正常 JSON body 被缓存，不会由 wrapper 自动写到原始 response；丢弃分支不执行复制，MockMvc 从 filter 收到注入异常。
3. 测试断言异常 cause 链含 `IOException` 且 `dropOnce` 已消耗（244-248 行），随后断言 SQLite currentVersion 和 revisionCount 先已增加一次（250-252 行）。这把 controller 完成后的响应丢弃与已提交数据库状态区分开。
4. 同一 payload/idempotency key 的后续两次 POST 都断言 HTTP 200、body 完全相同，包含 `applied:true` 和 `currentVersion:2`，最终 version/revisionCount 仍只增一次（254-264 行）。这覆盖 wire retry receipt 和单次持久副作用。

证据边界：这是 servlet Filter + MockMvc 的响应体丢弃模拟，不是 TCP 层断连、代理中断或服务进程崩溃；不证明重启后的 replay 持久性。它没有直接断言底层 response 的 `isCommitted()` 状态，但 wrapper 缓冲且丢弃分支无 `copyBodyToResponse()`，结合异常 cause/drop flag 与首个请求后的 DB revision，足以证明本次测试没有把成功 JSON 发送回 MockMvc 客户端。

## 2. CAS 后异常与防重复写

测试 `postCasExceptionDoesNotDoubleWriteButCannotReplayUnrecordedReceipt`（测试 268-302 行）：

- Spy 先执行真实 `commitPreparedApply`，然后在其返回后抛 `IllegalStateException`（285-288 行）。真实仓储调用完成后，测试从 SQLite 断言 version/revisionCount 各增加一次（290-294 行）。
- 同请求重试得到 typed `CONFLICT`，数据库计数不变，并验证 `commitPreparedApply` 调用次数为 1（296-301 行）。这直接验证 CAS 已提交后异常不会造成第二次提交。
- 该异常发生在 application service 的 receipt/replay 生成之前（`PlannerApplicationService.java:199-208`）；因此重试冲突是刻意 fail-closed 行为，而不是遗漏的“应回放成功”。本测试不走 controller，不断言其 HTTP 409 body；wire 映射需由独立 controller 测试支撑。

## 3. proposal remove 失败、pendingIntent 与第二动作

测试 `proposalRemovalFailureStillReturnsAndReplaysCommittedReceipt`（测试 305-377 行）：

- 测试构造 3 日行程和“第 1 天删除目标、第 2 天添加新地点”的两项请求，先取 clarification，再按 `pendingIntentId` 选择第一个动作（306-338 行）。
- 首个 Apply 的 proposal port remove 被注入异常。应用结果为 SUCCESS；同 key 重试 body 与首次相同；receipt 中 pendingIntent 与 intentId 非空；version/revisionCount 只增加一次；remove 调用一次（340-355 行）。静态实现 `PlanAdjustmentService.completeCommittedApply` 捕获 remove 的 RuntimeException 后仍调用 `continuations.afterApply` 并读取 pending continuation（945-964 行），与观察到的 receipt 一致。
- 留存的首个 proposal 用不同 key 和新 revision 再 Apply，得到 CONFLICT，版本不变（357-362 行）。这验证旧 proposal 不能再次写行程。由于请求 revision 已变，revision/proposal-base 检查本身足以拒绝；该断言不能单独证明 epoch 门禁。
- 随后用 pendingIntentId 请求“执行下一个”，preview SUCCESS，Apply SUCCESS，版本/revisionCount 再增加一次（364-376 行）。所以后续 continuation 可在第一次 remove 失败后继续，并且发生了第二次持久 Apply。
- **遗漏的强断言**：没有比较第一次应用后的行程是否已移除 `targetStopName`；没有比较第二次应用后的行程是否已包含 `nextPlace`，或其他第二动作的目标字段。版本增加和成功回执证明发生第二次 CAS，但不足以证明实际计划内容与复合任务第二项精确一致。测试也只确认 pendingIntent 有 id，没有核对其 `intent` 内容或预期 baseRevision。故“第二动作继续执行”的生命周期证据较强，但语义结果验证仍部分缺失。

## 未覆盖边界

1.真实 socket/代理响应丢失、进程在 CAS 后退出、跨进程/重启幂等回放。
2.实际 Redis/L2 proposal remove 故障；当前为 fault-injected port，不证明缓存适配器或 Redis 状态恢复。
3. Remove 失败后 epoch 轮换目前主要由生产代码顺序和新 pendingIntent 行为支撑；旧 proposal 冲突可由版本变化解释。若要单独证明 epoch，应使用同 revision、不同因果路径或显式可观察 epoch 契约测试。
4. 复合任务测试应补充 plan 内容断言，以验证第一个删除和第二个添加的真实对象，而不仅仅是 revision +1。

## 最终意见

R5 有效补强了 R4 的两个主要测试弱点：响应丢失现在检查注入异常及 flag，且 remove 失败场景确实续接并再次持久应用。对“不重复写入”而言没有发现阻断缺陷。建议保留整体 **PARTIAL**，直到第二动作的计划内容断言补齐；不要把 Servlet 模拟外推为真实网络丢包，也不要把 mock port 故障当成 Redis 演练。

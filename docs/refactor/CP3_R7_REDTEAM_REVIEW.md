# CP3 R7 红方复审

日期：2026-10-09  
范围：只读复核 R7 复合任务 continuation receipt、分类与持久快照断言，并裁决“是否必须提供结构化字段”是否为 CP3 阻断项；未运行测试，未修改代码。  
Manifest SHA-256：`e79499fa2d0f9d64dd791751b336f778ed61f3a77a605f5599674f3bf793295a`（匹配）  
Tracked patch SHA-256：`96f04a05e1dead4e9962aa405fbb12b76ff678de1fcd1ae4ba3879a3627dc3c1`（匹配）  
Final clean log SHA-256：`7f35ed26b7c9d2048aabc1951096fa2bd7a7e2355bdb329fcea96ae24bb45bd1`（匹配；只读取日志）

## 裁决

**R7 复合任务语义续接与持久快照验收：PASS。CP3 当前文本字段实现：不构成本检查点阻断项。**

R7 解决了 R6 剩余的分阶段证明缺口：第一项提交后的真实 SQLite 快照明确断言 day 1 目标已经删除、day 2 尚未提前出现 `nextPlace`；receipt 的 `pendingIntent.intent.clarificationQuestion` 保留第二项原句；把该句交给现有 `ConversationIntentClassifier` 可解析为 day 2 和准确地点名；随后系统用同一个 pendingIntent ID 实际预览并应用“执行下一个”，再次读取 SQLite，确认 day 2 已出现该地点且 day 1 删除持续保留。各阶段 revisionCount 各增加一次。

`PlanAdjustmentIntent.asMap()` 当前未把 `compoundActions` 或第二项的 `requestedVenueName` 作为独立 JSON 字段序列化；它通过 `clarificationQuestion` 保留用户可读的剩余操作文本。当前 CP3 执行计划的明确退出条件是“应用第一项后剩余任务不丢失”，并要求保持既有响应字段兼容；没有要求新增结构化 continuation 字段。R7 还验证了现有 prose 可以被当前 classifier 解出，并通过真实 pendingIntent ID 完成第二项，所以缺少专用字段不是本检查点的阻断项。它仍是未来 wire/API 设计时需要明确的契约选择：若前端或第三方客户端将依赖机器可消费的第二项内容，应在单独契约版本中加结构化字段，而不能把展示文案视为永久协议。

## 证据

### 1. Receipt 真实保留第二项文本

测试：`ai/src/test/java/com/ai/guide/application/planner/PlannerPreviewUseCaseTest.java:304-399`，`proposalRemovalFailureStillReturnsAndReplaysCommittedReceipt`。

- 初始请求同时包含“第 1 天删除目标”和“第 2 天添加 `nextPlace`”；`nextPlace` 从当前行程没有的地点里选出（311-330 行）。第一项经 clarification 与“执行第一个”预览后应用；proposal remove 被注入异常。
- 首次应用结果为 SUCCESS，同 key 重放 body 相同；从该真实 Apply receipt 的 `pendingIntent.intent` 读取 `clarificationQuestion`，抽取包含 `nextPlace` 的那一行（348-367 行）。这不是从测试原始请求字符串直接断言，而是读取后提交 receipt 的序列化字段。
- 该行由现有分类器独立解析，断言 `dayNumber == 2` 且 `requestedVenueName == nextPlace`（368-371 行）。分类上下文 activeDay=1，故 day 2 结果来自文本中的显式目标天数，而不是页面当前天数默认值。

### 2. 第一步未偷跑第二项，第二步确实落库

- 第一次提交后重新从 `sessions.findAnonymousByAccessToken(token)` 获取存储快照：day 1 不包含原目标，day 2 不包含 `nextPlace`（357-361 行）。这既证实首项删除已持久化，也证实第二项尚未提前写入。
- 测试再次用 receipt 的 `pendingIntentId` 和“执行下一个”调用 application preview；结果为 SUCCESS，再 apply 第二个 proposal（381-391 行）。这是实际 continuation 路径，不只是手工调用 classifier 后声称能续接。
- 第二次提交后重新读取存储快照，revision 与 revisionCount 又各增加一次；day 2 含 `nextPlace`，day 1 删除仍保持（392-398 行）。因此按 CP3 的两阶段业务契约，第二项在第二次 Apply 后才进入持久行程。
- Remove 故障期间同 key 回放 receipt；旧 proposal 以新 key 重试得到 CONFLICT、没有额外 revision（348-379 行）。旧 proposal 拒绝可由 revision 变更保证，不能单独推导 epoch 机制；R7 的重点是剩余任务保存和执行，这部分已由下一步应用与快照证明。

### 3. 文本字段与结构化字段的契约界限

- `PlanAdjustmentIntent.asMap()` 输出包括 `clarificationQuestion`，但未输出 `compoundActions`；R7 实施报告记录了该 wire 现状（`CP3_IMPLEMENTATION_REPORT.md:113-115`）。应用运行态的 `PendingIntentContinuations` 仍保留 `remainingActions` 的结构化对象/原始动作子句，第二步实际执行通过服务端 pending ID 续接。
- CP3 执行计划 §3.4 的退出条件是屏障覆盖两种顺序、版本只增加一次、拒绝时 DB 不变、第一项应用后剩余任务不丢失、迟到预览不能复活已取消提案（`COLA_REFACTOR_EXECUTION_PLAN.md:109-120`）。未规定 `pendingIntent.intent` 必须有 `compoundActions` 或 `requestedVenueName` 专用字段。
- 因为响应兼容要求保留旧字段且没有专用字段契约，当前把剩余操作保存在 `clarificationQuestion` 文本中不违反此检查点。R7 对展示文本的具体内容建立了测试约束：文本包含第二项且可由 classifier 解析。若未来将 prose 改成纯提示语，测试会失败并提醒需要同步迁移此兼容行为。

## 验收边界

- Manifest/日志记录隔离副本 **146 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**。这是读取既有报告，不是本次运行结果。
- 本证据为单 JVM、SQLite、应用服务生命周期测试；pending continuation 使用服务端内存状态。没有证明跨进程/重启后 continuation 恢复，也没有验证真实 Redis/PostgreSQL、真实 TCP 响应丢失或进程崩溃。
- 专用 JSON 字段可提高 API 稳定性，但除非业务/前端契约要求客户端在会话恢复后独立重建待执行项，否则它属于后续 API 演进，不是 CP3 退出条件。

## 最终结论

R6 的内容断言缺口在 R7 已闭环：先有明确的中间态 absence，再有 receipt 中剩余文本与 classifier 语义断言，最后有真实第二次应用后的持久 day 2 insertion。R5/R6 关于复合任务的 PARTIAL 可在此维度解除；文本字段没有独立结构化键不阻断当前 CP3。未发现该候选在本次审查范围内的验收阻断项。

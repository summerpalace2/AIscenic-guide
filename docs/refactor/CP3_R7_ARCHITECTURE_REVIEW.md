# CP3-R7 冻结候选独立架构复审

日期：2026-10-09  
审查方式：只读核对冻结 manifest、实现报告、源码和测试源码；未运行 Maven，未修改源码或测试。  
候选 manifest：`C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP3-r7-candidate.json`  
manifest SHA-256：`e79499fa2d0f9d64dd791751b336f778ed61f3a77a605f5599674f3bf793295a`  
tracked patch SHA-256：`96f04a05e1dead4e9962aa405fbb12b76ff678de1fcd1ae4ba3879a3627dc3c1`  
最终测试日志 SHA-256：`7f35ed26b7c9d2048aabc1951096fa2bd7a7e2355bdb329fcea96ae24bb45bd1`

## 裁决

**R7 可通过本次架构复审，未发现业务或架构阻断项。** Manifest、tracked patch、final log 和指定 slice hashes 匹配。R7 只增加测试断言及实施报告；`PlannerApplicationService.java` 与 `PlanAdjustmentService.java` hash 与 R6 相同，无生产实现改动。

新增断言解决了 R6 留下的一个测试证据空档：第一项动作提交后，day 2 尚未出现 `nextPlace`；receipt 的实际 `pendingIntent.intent` JSON 中 `clarificationQuestion` 包含第二项原始要求，测试抽取该行交给现有 classifier，得到 `dayNumber == 2` 且 `requestedVenueName == nextPlace`；随后通过 pending intent ID 完成第二项，并从 repository 重读 stored trip 验证 day 2 确实新增目标、day 1 仍保留第一步删除。

**澄清文案承载第二项文本是当前可接受的兼容表示，但不是稳固的结构化 wire contract。** 现有 `clarificationQuestion` 本来就是给用户显示的字段，保持字段形状并附带剩余任务文案不破坏既有消费方式。当前实际续接由服务端 pending intent ID 与进程内 continuation 对象驱动；服务端不是把客户端回传的文案重新解析成任务，因此不会把用户可编辑文本当作权威状态。测试又独立证明现有 classifier 可从该 JSON 文案恢复 day 2 和准确 POI。这个做法可用于当前单实例 UI 兼容，但若 API 消费者需要机器读取第二步、跨进程恢复或稳定结构化语义，应新增明确的 `remainingActions`/typed next intent 字段，而不应承诺从自由文案永久反解析。

## 冻结身份核验

- 指定 manifest、tracked patch、最终测试日志 raw-file SHA-256 均匹配。
- manifest 的 CP3 slice 哈希匹配：`PlannerPreviewUseCaseTest.java` `5cfab888...3857bbe4`、`CP3_IMPLEMENTATION_REPORT.md` `4570c682...32f2aaea0`、`PlannerApplicationService.java` `3d14667d...c5c912e380`、`PlanAdjustmentService.java` `186110d8...6cc2d06f4f9bb6`。应用/领域生产文件与 R6 相同；R7 scope 为 test-only，源码核验一致。
- Manifest 报告 fresh isolated copy 的 10-class targeted suite 为 146 tests、0 failures/errors/skipped；本审查未运行测试。SQLite 位于副本内，Qdrant 不可用并走既定降级；没有真实 Redis/PostgreSQL、socket 级 response loss、进程崩溃或多进程验证。
- 工作树包含 CP0–CP2、用户既有修改及先前审查材料。本次未清理或覆盖它们，只新增本报告。

## 具体证据

### 1. 第二步 JSON 文案及 classifier 还原

测试位置：`ai/src/test/java/com/ai/guide/application/planner/PlannerPreviewUseCaseTest.java:306-399`。

- 测试在 3 天重庆行程中取 day 1 的目标站，动态挑选一个尚未计划的渝中区 `nextPlace`，以“第1天删除…，第2天添加…”发起复合请求（`:308-330`）。用户选择第一项后，首个 apply 的 proposal port remove 被替身注入抛错（`:334-349`）。
- 首次 apply 成功且同 key retry receipt 相同；receipt 有 `pendingIntent.intentId`，revision/history 各只增 1（`:348-356`）。从 SQLite-backed session repository 读回 stored trip，确认 day 1 目标已删除且 day 2 的 `nextPlace` 还未出现（`:357-361`）。这证明两步没有被作为整批副作用提前提交。
- 测试读取 receipt 的 `pendingIntent.intent` JSON，再从 `clarificationQuestion` 中提取包含 nextPlace 的剩余任务行，用 `ConversationIntentClassifier` 和 activeDay=1 解析（`:362-371`）。明确断言结果 `dayNumber == 2`、`requestedVenueName == nextPlace`，不是仅断言字符串包含地名。
- 随后使用同一 pending intent ID 请求“执行下一个”，完成第二次 preview/apply；重新读取 stored trip，确认 day 2 含 `nextPlace`、day 1 仍不含被删除地点，版本/历史只再增加一次（`:374-398`）。所以现有服务端 continuation 的实际执行结果也与 classifier 探针一致。

### 2. 澄清文案作为兼容契约的范围

- `ai/src/main/java/com/ai/guide/domain/planner/service/PendingIntentContinuations.java:136-143` 把未执行的 compound clause 保存在服务器内存中的 `remainingActions` 和下一轮 `Pending.intent`；剩余任务提示的文本会写入 `clarificationQuestion`。
- `ai/src/main/java/com/ai/guide/domain/planner/model/PlanAdjustmentIntent.java:92-127` 的 `asMap()` 暴露 `dayNumber`、`requestedVenueName` 等单项语义字段，也序列化 `clarificationQuestion`；但没有序列化 `compoundActions` 字段。
- `PlanAdjustmentService.java:943-964` 在 Apply 完成后从 continuation 取 pending intent，并把 `continuation.intent().asMap()` 放入 receipt；在后续 Preview，服务器使用 continuation service 内部保存的 `PendingIntentContinuations.Pending` / remaining action，而不是从此前 receipt 的 `clarificationQuestion` 字符串恢复任务。
- 因此 R7 通过文案行执行 classifier 是一个**序列化可读性/语义可解析性探针**，而后续真实 continuation 成功和 stored-trip 断言是一个**服务器权威状态实际执行探针**。二者相互补充，但不应误述为 JSON 已有专用结构化复合动作协议。
- 当前字段给 UI 展示剩余动作是合理兼容；客户端依赖固定中文前缀、换行或 classifier 语法去执行操作，则不属于稳固契约。JSON 字段也没有承诺可在服务重启后重建 remainingActions；CP3 单实例边界允许这一限制。

### 3. CP3 生命周期架构与其他边界

- Application 继续拥有 Preview/Cancel/Reset admission 与 Apply 的步骤排序（`ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java:115-209`）；领域服务提供 ticket、校验、CAS 与 continuation 能力。
- `PlanAdjustmentService.java:85-93` 仍是唯一 lifecycle lock/continuation/generation/replay authority；没有 R7 平行锁、epoch map 或 receipt store。CAS 与缓存/内存 continuation/replay 不是全局事务。
- CAS commit 后 proposal remove failure 可继续完成 epoch/remainder/receipt；R7 的测试验证其余任务仍可继续。但 remove 失败由替身端口模拟，真实 Redis 行为没有覆盖。
- 既有缺口继续有效：CAS 成功后、completion/replay 登记前异常时不双写，但同 key 重试会 fail closed、可能不能重取原成功 receipt；内存 replay、epoch 和 remainingActions 不支持进程重启/跨节点权威。

## 阻断 / 非阻断矩阵

| 审查项 | 结论 | 等级 |
|---|---|---|
| R7 变更范围 | application/domain production hash 与 R6 相同；仅增加测试/报告 | **通过** |
| 第一项动作期间隔离 | day 1 删除已存储、day 2 nextPlace 尚未提前落库 | **通过（SQLite）** |
| `pendingIntent` JSON 语义文本 | `clarificationQuestion` 中有第二动作原文；classifier 精确得出 day 2/nextPlace | **当前兼容可接受；不是结构化动作契约** |
| 实际 continuation 后续动作 | 使用 pending ID 续接后 stored day 2 新增目标，day 1 删除继续保留 | **通过（单 JVM/SQLite）** |
| remove failure 下 proposal/remainder | 同 key 回执、旧 proposal 新 key 拒绝及第二项续接均有覆盖 | **通过（故障端口替身）** |
| 多进程、重启、真实 Redis/PG、网络级恢复 | 未验证，且并非本候选范围 | **非阻断限制** |
| Maven/定向 suite | manifest 记录 146 项全绿；本审查未执行 | **未由本审查独立验证** |

## 最终意见

CP3 R7 可通过复审。剩余任务在 JSON 中使用 `clarificationQuestion` 呈现，符合当前人类可读兼容字段用途；字段不是 canonical structured action contract。现有 classifier 可以从该字段中的剩余文本准确恢复 day 2 与 `nextPlace`，而生产续接本身仍由服务端 pending ID/state 决定，最终 stored trip 断言证实实际效果。若未来要求客户端机器处理复合待办或跨重启恢复，应另立契约升级；这不构成 R7 当前单实例切片的 blocker。

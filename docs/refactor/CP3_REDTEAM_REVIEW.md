# CP3 冻结候选红方验收

日期：2026-10-09  
独立验收对象：`outputs/20261009-CP3-candidate.json`  
声明候选 SHA-256：`0460ce238cf38fcfe955ba44e03db7331077e480b2ca1b828dbd48559cf8a3e5`  
声明 tracked patch SHA-256：`82422219875a5c24eafc63876f151384f2b11c35b56576e7391a83f41117e7de`  
验收方法：只读源码、测试和已保存原始日志；未运行 Maven，未改源代码或共享 `target`。

## 裁决

**总体：PARTIAL（不签 CP3 全通过）。** 冻结候选身份与声明一致；所检查的 Apply/Cancel 路径没有发现会必然造成重复行程写入或取消后旧 proposal 被应用的代码阻断。实施补上了 forceApply 幂等指纹、双向取消竞态、迟到 preview 与缓存清理异常的主要防线。

未达到完整验收的原因是 §3.4 明确要求定义并验证“DB 提交但 HTTP 回执丢失”及提交后异常边界；当前有同进程顺序重放证明，但没有 HTTP 响应丢弃测试，也没有真实仓储 commit 后抛异常的故障注入。双向竞态、迟到 preview 与 cache remove 异常测试使用 Mockito/端口替身，不能证明多线程真实 JDBC/CAS 与真实 Redis 的交互。控制器状态映射也只是直接调用 controller 方法，尚不是 MockMvc/wire 集成。

独立审查后阅读了 `CP3_IMPLEMENTATION_REPORT.md` 并核对其自述：它明确把上述两种提交故障和真实 Redis/PostgreSQL列为未验证，不把替身测试宣称为真实数据库/Redis；这一范围说明与源码证据一致。报告所述 141 项隔离定向测试及日志尾部 `BUILD SUCCESS` 是既存证据，本验收没有重跑或独立复现。

## 冻结身份

- 候选 JSON 实际 SHA-256：`0460CE238CF38FCFE955BA44E03DB7331077E480B2CA1B828DBD48559CF8A3E5`，与声明匹配。
- `outputs/20261009-CP3-final-tracked.patch` 实际 SHA-256：`82422219875A5C24EAFC63876F151384F2B11C35B56576E7391A83F41117E7DE`，与声明匹配。
- 候选记录的根目录为 `D:\scenic-guide`，patch 建立于 HEAD `46b1653e81050e3cdd9f23506b327bc6676bcbd8`。审查期间未看到身份改变。

## 场景核验

| 场景 | 裁决 | 代码/测试证据与边界 |
|---|---|---|
| Apply-first / Cancel-first 屏障 | **PARTIAL** | `CancellationProposalLifecycleTest.java:92-137` 与 `:140-185` 用 latch 固定两个顺序，断言 Apply-first 只写一次且 Cancel 409；Cancel-first 返回 200 cancelled，Apply 409、update 0。都是直接调用领域 service、Mockito 仓储。还没有应用层 typed outcome + 真实 JDBC 同时竞争的测试。 |
| Cancel 完成后旧 proposal 不可应用，拒绝零写 | **PASS（应用层持久状态范围内）** | `PlannerPreviewUseCaseTest.java` 的 `cancelIsOrchestratedByApplicationAndInvalidatesOldProposalWithoutTripWrite` 生成 proposal、经应用层取消、再 apply；断言旧 apply 为 conflict，trip、version、revision count 不变。底层使用 `PlannerSessionRepository` JDBC/SQLite。 |
| 迟到旧 Preview | **PASS（端口替身范围内）** | `LatePreviewCancellationLifecycleTest.java:25-79` 在 intent extractor latch 暂停，取消后释放；旧 preview 409，proposal writer 和 session update 均未调用。它进入旧 `service.preview` 兼容入口而非 controller/HTTP；与 application 路径共用 `previewAuthorized` / continuation commit，但不覆盖真实 ProposalRepository 或 Redis。 |
| 同 key、不同 forceApply | **PASS** | `PlanAdjustmentService.java:832-835` 的 fingerprint 包含 `force=`；`PlanAdjustmentServiceTest.java` 新用例（约 839-864）验证相同其余字段、force 值不同返回 409，version 不重复增加。 |
| 同进程相同 key 回放 | **PASS（内存范围内）** | `PlannerPreviewUseCaseTest.java` `applyUseCaseUsesExplicitActorAndReturnsTypedReceipt` 走应用层和 JDBC session repository；重试返回相同 body，版本仍只增加一次。回放 map 仍是进程内有限容量状态；重启后不承诺相同 receipt。 |
| DB 成功后 HTTP 回执丢失 | **NOT VERIFIED** | 正常 service 返回后写入内存 replay，controller 返回同一个结果；逻辑上客户端重试可重放，但没有 HTTP response filter / MockMvc / 真实 HTTP 故障注入。Controller 测试见 `PlannerControllerTest.java:223-232`，直接调用 Java 方法，非网络边界。不能据此宣称 HTTP 情形已实测。 |
| CAS 已提交后仓储/方法抛异常 | **NOT VERIFIED** | 实施报告明示未做 commit-then-throw 注入。代码先调用 session CAS，再 remove、epoch、回执与 replay（`PlanAdjustmentService.java:888-962`）；CAS 后旧 revision 会阻止相同旧请求二次写，但异常窗口是否保持正确清理/continuation状态尚未测试。该边界不同于 HTTP 响应丢失。 |
| proposal cache remove 抛异常 | **PASS（端口模拟范围内）** | `CancellationProposalLifecycleTest.java:39-67` mock `ProposalRepositoryPort.remove` 抛异常；代码捕获异常并继续 `afterApply` 与 receipt（`PlanAdjustmentService.java:909-916`），同 key 重试相同 receipt，仓储 update 仅一次。无真实 Redis故障注入，不能代表跨进程 L2 清理一致性。 |
| 复合任务剩余项 | **PASS（SQLite 应用用例）** | `PlannerPreviewUseCaseTest.java:210-266` 首项 Apply 后 receipt 有 `pendingIntent`；通过返回的 intentId 续接第二项并应用；持久 revision 1→2→3。能证明继续流程可走通；没有验证各种失败/取消交错下 continuation 的持久恢复，因为队列仍是进程内状态。 |
| 拒绝路径零写 | **PARTIAL** | `PlannerPreviewUseCaseTest.java` 缺 proposal 用例对 trip/version/revisionCount 做持久快照断言；取消后旧 proposal同样如此。`PlanAdjustmentService` 有过期、revision、epoch、option、硬约束等逐项检查，但没有完整的 owner/session/revision/epoch/option/hard-constraint 应用层与 wire 状态交叉矩阵。 |
| status/body兼容 | **PARTIAL** | `PlannerController.java:188-211` outcome 映射保留预览成功/澄清/无候选的 200，以及 Apply 冲突 409等类别；`PlannerControllerTest.java:198-232` 检查 conflict/no-candidate status/body 与 token header fallback。测试直接调用 controller，未验证 Spring MVC序列化和所有旧错误码/body字段；应用层通过原 body map 做转换，源码上没有看到本切片主动改写成功 receipt schema。 |
| CAS 冲突及并发最终版本 | **PARTIAL** | 生产仓储是 JDBC并提供 revision compare-and-set；应用层的 SQLite测试核对版本/修订计数。但 CP3 两个锁竞态用 mock update，不是真实 JDBC并发竞争，也未看到 barrier 在真实 CAS commit 前交错另一变异的验收。 |

## 代码审查要点

1. Apply 和 Cancel/Reset 仍共用 `PlanAdjustmentService` 的 session stripe lock 和 `PendingIntentContinuations`；application service 通过 `cancelOrResetForApplication` / `applyForApplication` 进入同一协调点，没有发现并行创建第二套锁或 epoch map。见 `PlanAdjustmentService.java:733-806`。
2. Apply 操作身份检查现在使用 proposal、option、revision、forceApply；授权会话刷新发生在锁内的 application entry，再在领域流程中先核幂等 replay、再校 revision、proposal session/expiry/base revision/epoch、选项与硬约束，之后才做 CAS。见 `PlannerApplicationService.java:156-174`、`PlanAdjustmentService.java:782-897`。
3. CAS 成功后即使 proposal remove 抛 `RuntimeException`，代码记录清理故障并继续 epoch 轮换、剩余任务处理及 receipt/replay 写入。该处理避免客户端收到失败但 plan 已提交的普通 remove 异常路径；测试只模拟 port 抛异常，真实 L2残留与重启情况未验证。
4. Apply replay 仍是内存 Map（`PlanAdjustmentService.java` 字段定义处，容量 256）；这是文档声明的单进程能力。DB commit 后进程崩溃，重试旧 revision应冲突而不会重复提交，但客户端需要刷新/重新预览，不能称为 durable exactly-once 回执。
5. Application typed outcome 降低了 HTTP 状态对 controller 的泄漏，但 `PlanAdjustmentService.previewForApplication` / `applyForApplication` 内仍将 legacy `PlannerService.ServiceResult.status()` 转为业务 outcome（`PlanAdjustmentService.java:408-430` 与 `:768-785`）。这是残留迁移耦合，非本次观察到的 wire 回归；若 CP3 要求领域完全去 HTTP 数字状态，需后续整理。

## 未发现的阻断与保留项

本轮静态审查未发现确定的逻辑缺陷能直接推翻 Apply/Cancel 单进程生命周期：同 key 不同 force 已被拒绝；取消与应用的两个串行顺序分别有 barrier；迟到预览在取消后无法发布 proposal；CAS 后缓存移除异常不会跳过 epoch/replay处理；复合任务可完成第二项。

但因为 HTTP response-drop 与 CAS-then-throw 未验证、并发状态屏障不是应用层/真实数据库组合、拒绝/status-body 矩阵不完整，结论只能是 **PARTIAL，不满足全项 CP3 终验签收**。若主验收允许按 §3.4 的“明确单进程恢复边界”接受未实现的持久回执，可把产品契约限定为：正常网络响应丢失时同进程同 key重放原 receipt；进程在 CAS 后崩溃时旧请求返回 409、不得重复写入，用户刷新并重新预览。此契约仍建议补一条 response-drop 测试来验证 Controller/HTTP retry确实复用同一 session replay。

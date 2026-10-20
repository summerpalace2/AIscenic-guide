# CP3 冻结候选独立架构审查

日期：2026-10-09  
对象：CP3 Apply / Cancel / Reset application lifecycle candidate。只读静态审查；未运行测试、未改源码。  
候选清单：`C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP3-candidate.json`  
清单 SHA-256：`0460ce238cf38fcfe955ba44e03db7331077e480b2ca1b828dbd48559cf8a3e5`  
tracked patch SHA-256：`82422219875a5c24eafc63876f151384f2b11c35b56576e7391a83f41117e7de`

## 裁决

**状态一致性保留：通过。** CP3 没有新建平行锁、continuation、epoch 或 replay map。Application 的新入口最终调用唯一 `PlanAdjustmentService` 实例；该实例继续持有既有 128 条 `sessionApplyLocks`、同一个 `PendingIntentContinuations` 和同一个 `applyReplays`。Apply 与 Cancel/Reset 使用相同 session stripe；Preview 的晚到结果由共享 continuation generation/epoch 的 `commit/reset` 原子门禁拒绝。

**身份 ThreadLocal 回绕：通过。** 新 application 入口从 `PlanningActor` 查会话；Apply/Cancel domain 协调方法收到已授权 `StoredSession`，在锁内通过其 ownerType/ownerId 与 token 复读最新会话，没有再调用 `UserContext`。旧 `PlannerService` 兼容入口仍走旧 ThreadLocal 授权路径，这是明确保留的 legacy facade。

**Application 编排迁移：部分，未达到 §3.4 的完整目标。** Application 已负责可信 actor/session 解析、feature gate、preview/cancel 分流、apply 用例入口和 outcome 组装。但幂等重放、锁协调、revision/proposal/epoch/option/硬约束校验、CAS、CAS 后 cache cleanup、continuation/remainder 更新和 receipt/replay 构造，仍整体封装在 `PlanAdjustmentService.applyForApplication()` → `doApply()` 中。Cancel 的锁、复读、revision 校验、epoch reset 也仍由 `cancelOrResetForApplication()` 执行。Application 是真实入口编排者，但还不是跨领域端口的 lifecycle workflow owner。若 CP3 的“将 Apply 和 Cancel 的编排迁到应用层”按执行文档字面验收，这是**范围阻断项**；若接受“Application 入口 + 单一领域 lifecycle coordinator”的过渡切片，则功能与状态协调满足。

**Typed outcome / HTTP：部分通过。** 新 application result 与 domain lifecycle execution 均没有数值 status 字段，Controller 映射 typed outcome 到 HTTP；Apply/Cancel wire shape保持。可是 domain `PlanAdjustmentService` 仍先构造 `PlannerService.ServiceResult`，再按 HTTP 数值状态转换为 `AdjustmentLifecycleExecution.Outcome`（以及 legacy preview reset 同样走此转换）。所以 HTTP numeric code 未出现在新 application 接口，但 status 语义仍在领域 coordinator 内部。

按实现报告矩阵，候选测试记录为 141 tests 全绿；这是清单/报告中的实现方验证，本审查未复跑。该矩阵明确未验证真实 Redis/PostgreSQL、响应丢失、CAS 后抛异常或多进程场景。

## 冻结核对

- 候选 JSON SHA 与指定值一致。
- 清单内 CP3 slice 文件哈希全部匹配工作区；raw tracked patch 文件 SHA 与清单一致。
- 工作区包含 CP0–CP2 与其他既存用户改动；本审查未覆盖、还原或清理。

## 证据及逐项结论

### 1. Application 编排和旧入口

- `ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java:114-146`：Preview 先 feature gate、按 actor/token 读取会话并检查 baseRevision；将 reset/cancel 分流至 `cancelOrResetForApplication`，普通预览调用 `previewForApplication` 并注入 `proposalRepository::put`。
- 同文件 `:163-176`：新 Apply 用例做 actor/session 授权后调 `applyForApplication`，再组装 typed outcome。该用例确实存在，不只是 Controller 改签名。
- 但 `PlanAdjustmentService.java:736-752,790-810` 仍包办 Cancel/Apply 的同步、刷新和业务协调；`doApply :825-959` 包办完整提交协议。Application 没有协调 repository CAS、proposal cache、epoch 和 response receipt 的各个步骤。
- 旧 `PlannerApplicationService.applyAdjustment(String, ...)` 保留为兼容 facade（`:179-182`）；`PlannerService` 原 `converse/previewAdjustment/applyAdjustment` 入口也保留（`PlannerService.java:624-643`）。Controller 使用新显式 actor overload（见第 5 节）。

### 2. Actor 与 session identity

- `PlannerApplicationService.java:149-159,163-175`：匿名 actor 必须先以 capability token 找回匿名 session，再验证 path sessionId；用户 actor 按 USER owner 查询，找不到时尝试用 token claim anonymous session。Apply 将授权后的 `StoredSession` 传给协调方法。
- `PlanAdjustmentService.java:743-746,793-799`：Cancel/Apply 在取得共享锁后，用 `refreshAuthorizedSession` 按原已授权 ownerType/ownerId + 请求 token 重读当前 session，再按最新 revision 操作。
- `PlanAdjustmentService.java:1343-1347`：复读不查 `UserContext`。新路径的 authorization source 是显式 actor 对应的 server-side session tuple。
- 旧 `doApply(String, request)` 与旧 preview `findAuthorized` 仍通过 `UserContext`（`:813-819,1329-1341` 附近）。这属于保留的 legacy path；务必避免新 controller/application 路径误调它。

### 3. 锁、continuation、epoch 是否共享

- `PlanAdjustmentService.java:90-91` 只有一份 stripe 数组与一份 `PendingIntentContinuations`；apply `:781-785,790-795` 和 cancel/reset `:736-747` 对同一 session hash 取同一 stripe。
- 旧 preview 的取消分支 `:274-275` 委托同一个 `cancelOrResetForApplication`；application 直接分流也进入相同方法。因此没有复制取消状态或第二把锁。
- `PendingIntentContinuations.java:16-29,30-42`：`begin/commit/reset/afterApply` 使用 synchronized authority；取消/应用 reset generation 与 epoch，旧 proposal 由 `validProposal` 拒绝。proposal 写入仍在 `commit(ticket, action)` 门禁内（`PlanAdjustmentService.java:537`）。
- `applyReplays` 仍是 `PlanAdjustmentService` 的同一 bounded map（定义 `:83-89`），并由 session lock 串行化同会话调用。未发现 CP3 新增并行状态 store。

### 4. CAS、缓存清理、epoch、剩余任务和回执顺序

`PlanAdjustmentService.doApply(StoredSession, ...)` 当前顺序：

1. 根据已授权 session 构造 replay key/fingerprint；相同非空 key + 同 fingerprint 回放，不同 fingerprint 返回 409（`:828-844`）。fingerprint 现包含 `proposalId|optionId|requestedRevision|forceApply`（`:835`）。
2. 校验 revision，再查 proposal；验证 proposal session、绝对 TTL、revision、当前 continuation epoch（`:846-867`）。
3. 检查选项、required stop、硬约束和可行性/force 策略（`:869-886`）。
4. 生成 nextVersion，调用 repository CAS update；CAS conflict 直接返回，不做后置清理（`:887-906`）。
5. CAS 成功后 remove proposal；CP3 新增对 RuntimeException 的捕获与日志，然后无论 cleanup 是否失败继续 `continuations.afterApply`（`:908-916`）。
6. 组装成功 receipt，包括 `pendingIntent/nextActionPrompt`；最后写内存 replay（`:923-959`）。

该顺序维持了“DB CAS 是行程提交点；proposal cache 与 continuation 是后处理；HTTP 回执和 replay 最后生成”。cache remove 抛错时仍推进 epoch 并保留复合任务，避免已提交 plan 被误报失败。它不是 DB/cache/epoch/replay 的统一事务：进程若在 DB commit 后、replay entry 前崩溃，不能从内存 replay 保证相同 receipt；也未在真实存储中验证 CAS 后连接断开/异常。

### 5. Typed outcome 与 wire contract

- 新 application contract 返回 `ApplyAdjustmentResult`/`PreviewAdjustmentResult`；`AdjustmentLifecycleExecution` 是无 numeric status 的业务结果。Controller 用 outcome mapper 生成 HTTP code（`PlannerController.java:188-213`）。
- 现有 `POST /sessions/{id}/conversation`、`POST /sessions/{id}/adjust/preview` 路径保持；取消/重置仍通过这两个 POST，成功 body 保持 `ok/reset/cancelled/modified/message`。Apply `POST /sessions/{id}/adjust/apply` 保持 body/header token fallback，成功 receipt 字段保持 `ok/applied/sessionId/currentVersion/trip/changedSegments/unchangedStops/appliedPreferences/...`。
- Controller `:144-182` 从 UserContext 生成 `PlanningActor`、优先保留 body token、空缺时用 `X-Plan-Session-Token`；Apply 也改用新 typed overload。response 的业务字段来自原 domain receipt map，HTTP status 由 controller outcome mapping 决定。
- `ApplyAdjustmentResult.Outcome` 和 `AdjustmentLifecycleExecution.Outcome` 仍采用 `BAD_REQUEST/NOT_FOUND/CONFLICT` 等 HTTP 派生词汇。且 `PlanAdjustmentService.lifecycle()` 与 `applyForApplication()` 将 `ServiceResult.status()` 数字映射成这些 enum（`:768-779,800-809`）。所以数值状态已从新 application contract 拿走，但领域层仍有 transport-shaped legacy adapter。

### 6. CP2 Preview/Cancel 兼容与状态副作用

- 普通预览仍从 CP2 的显式 actor application path 进入，并沿用同一个 proposal writer 与 continuation 对象。
- Cancel/Reset 既可由 application 识别后分流，也可由旧 `previewAuthorized` 分支 `:274-275` 调共享 coordinator；没有另建第二套取消流程。
- cancel 只重置 continuation、旋转 epoch，不主动删除 proposal（`:747-750`）。这是已有逻辑契约：proposal 可能留在 cache，但 apply 的 epoch 校验必须保留。
- 新的 legacy service facade 仍可供旧 Java caller 使用；新的 HTTP controller 路径不再调用隐式 ThreadLocal Apply/Preview facade。未见路由方法、token header 名称、成功 body 字段变更。

## 阻断项与非阻断项

| 分类 | 结论 | 严重度/验收影响 | 证据 |
|---|---|---|---|
| 共享状态/竞态 | Apply、Cancel/Reset、late preview 使用同一 locks 与 continuation authority；没有平行 Map | **通过** | `PlanAdjustmentService.java:90-91,537,736-747,790-799`; `PendingIntentContinuations.java:16-42` |
| Actor 授权 | 新 Application 路径显式 actor，locked refresh 依赖已授权 session tuple，不绕回 ThreadLocal | **通过** | `PlannerApplicationService.java:149-175`; `PlanAdjustmentService.java:1343-1347` |
| Application workflow ownership | Application 仍把整个 apply/reset critical section 委托给 domain coordinator；CAS/cleanup/epoch/receipt 不在 application 编排 | **阻断（若按 §3.4 字面验收）** | `PlannerApplicationService.java:175`; `PlanAdjustmentService.java:790-959` |
| 提交顺序/副作用 | CAS 前校验；CAS 冲突无后处理；commit 后 cache failure 被捕获并继续 epoch/receipt | **通过（本地逻辑）** | `PlanAdjustmentService.java:846-916`；实现报告的 cache failure 由替身测试覆盖，未连真实 Redis |
| Typed result | 新边界无数值 HTTP status，Controller 映射；domain 内仍从 status int 适配 | **非阻断架构残留** | `PlanAdjustmentService.java:768-809`; `PlannerController.java:188-213` |
| Compatibility | 旧 Preview/Apply facade 留存；新 Controller 使用 actor overload，路径与 body 保持 | **通过（源码/定向单测证据）** | `PlannerService.java:624-643`; `PlannerController.java:144-182` |
| 分布式/崩溃语义 | 锁、epoch、remaining action 与 replay 仍为进程内状态；跨节点和 commit 后崩溃 receipt 不保证 | **明确限制，非本候选回归** | 实现报告测试限制；当前字段为服务实例内存结构 |
| 独立复验 | 141 tests 报告为实现方测试；复审未复跑 | **未由本审查验证** | `CP3_IMPLEMENTATION_REPORT.md` 的验证/限制段 |

## 最终意见

如果 CP3 的退出条件是“Apply/Cancel 状态统一、两序竞争安全、身份明确、commit 顺序与回执契约保持”，源码结构与实现报告矩阵支持通过，功能测试证据仍需由主线程按报告边界判断。

如果退出条件严格要求“Apply 和 Cancel 的业务编排由 application service 负责，领域服务只暴露规则/仓储能力”，本候选**不应以 CP3 完成归档**：application 仅做 actor 解析和门面分流，完整 lifecycle critical section 仍在 `PlanAdjustmentService`。可以把唯一共享 coordinator 抽为 application-owned lifecycle service，同时原锁/continuation/epoch state 必须作为同一单例被 Preview、Apply、Cancel 共用；不要直接在 application 重写校验与顺序。

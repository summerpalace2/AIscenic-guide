# CP3-R1 冻结候选独立架构复审

日期：2026-10-09  
审查方式：只读源码与冻结材料核验；未运行 Maven、未修改源码。  
候选清单：`C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP3-r1-candidate.json`  
候选 JSON SHA-256：`b82a1673e4b2a306eb9fcdc226abd54fab89f75755ed9c842d8bcb3b5a604651`  
tracked patch SHA-256：`30c5764cfd4545dee9303e854f799002aa4fe4ac413258b60d9ff15d0e8338da`

## 裁决

**上一轮“Application 只是整段委托”的阻断项已解决。** R1 在 `PlannerApplicationService` 中明确持有 Apply 的步骤顺序：共享会话锁 → 已授权 session 复读 → 幂等检查 → 领域准备/拒绝 → CAS commit → commit 后清理与 continuation 完成 → 应用层 receipt 组装 → replay 记录。Domain 方法被拆成可组合的规则/提交/完成步骤，application 不再调用一个包办完整 Apply 的领域方法。Cancel/Reset 也在 application 的同一锁闭包中完成复读、revision 门禁、continuation reset 和结果组装。

**共享状态与 ThreadLocal：通过。** Apply 与 Cancel 仍使用同一既有 128 条 session 锁；Preview 的 epoch/generation、pending intent、remainingActions 与 apply replay 仍由同一个 `PlanAdjustmentService` 实例持有。新 actor 路径下应用层显式授权，锁内刷新使用已授权 `StoredSession` 的 owner tuple，不回读 `UserContext`。旧 `PlannerService` facade 仍在并复用同一把锁和 replay authority。

**状态协调 API 的层次判断：没有依赖倒置破坏，但有非阻断的 API 形状债务。** `PlanAdjustmentService.withSessionApplyLock(sessionId, Supplier<T>)` 将锁留在状态所有者中，application 向下调用 domain 能力，依赖方向没有反转，也没有复制状态；但它把一个通用同步锁回调暴露为 domain service 的公共方法，且 `ApplyPreparation/ApplyCommit` 是可由其他调用方构造的 public record。当前唯一 application caller 按正确顺序调用，静态检索未发现第二个生产 caller；未来误用可跳过 prepare 直接 commit。建议后续把锁/生命周期协调封装成窄接口或不可伪造的 preparation token，这属于 P2 封装建议，不是本候选已证实的功能漏洞。

Typed Application result 已不带数值 HTTP 状态，Controller 负责 outcome 映射；不过 domain 规则拒绝仍经 `PlannerService.ServiceResult.status()` 转成 `AdjustmentLifecycleExecution.Outcome`。因此数值状态不再穿过新 application API，但 HTTP 语义仍存在于 domain coordinator 内部，属于非阻断架构残留。

**结论：CP3 的 workflow ownership 阻断项通过；当前未发现新的静态阻断问题。** 如果验收门槛还要求领域代码彻底不含 HTTP status 语义，或禁止公开锁回调/可伪造阶段对象，则需另立收敛项。父任务提供的 142 tests 全绿记录本审查未复跑，且 implementation report 明确不覆盖真实 Redis/PostgreSQL、网络响应丢失、commit 后进程崩溃或多进程协调。

## 冻结核对

- Candidate JSON SHA 与指定值一致。
- 清单所列 12 个 CP3-R1 slice 文件 SHA 与当前源码一致；tracked patch artifact SHA 与清单一致。
- 工作区含有 CP0–CP2 与用户既存修改；未覆盖、清理或修改它们。

## 证据

### 1. Application 是否真实排序小步能力

- `ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java:175-210` 是新 Apply workflow 主体。它在 `withSessionApplyLock` 回调里显式调用 `refreshAuthorizedSessionForLifecycle`、`lookupApplyReplay`、`prepareApply`、`commitPreparedApply`、`completeCommittedApply`，再由 application 调 `assembleApplyReceipt` 和 `recordApplyReplay`。这是多步编排，不是把请求整段交给 `applyForApplication`。
- 同文件 `:213-247` 由 application 组装成功 body 与 V1 metadata、force 提示和剩余任务提示。
- Cancel/Reset 在同文件 `:126-142` 先识别 reset/cancel，再在相同锁 callback 内复读授权会话、比较最新 revision、重置共享 continuation、构造 typed outcome。锁内没有调用旧的 `cancelOrResetForApplication` 大方法。
- Domain 小步能力位于 `ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java:746-754,758-789,791-915`：复读/continuation reset/revision冲突 body、replay 决策与登记、校验准备、CAS 提交、提交后状态完成。这些方法实现领域规则和资源操作，步骤排序由 application 决定。
- 兼容入口的旧 `PlanAdjustmentService.apply(String, ...)` 和完整 `doApply` 仍保留（`:998-1012` 及后续），但 controller/application 新路径不调用它。implementation report 中所述“Application 为新 workflow 顺序所有者”与当前调用图相符。

### 2. Lock / epoch / replay 是否仍为单一权威

- `PlanAdjustmentService.java:90-91` 只保留一份 `sessionApplyLocks`、`applyReplays` 与 `PendingIntentContinuations`。
- `withSessionApplyLock` 在 `:738-744` 对既有数组执行 synchronized callback。新 Apply、Cancel 在 application 中使用此入口；旧 apply facade 使用同一数组（`:998-1003`），旧 cancel 继续调用共享 reset coordinator。
- `PendingIntentContinuations` 未被复制；preview 的 `commit` proposal writer 和 apply/cancel 的 reset/afterApply 都引用同一个字段。取消会使旧 proposal epoch 失效；晚到 Preview 通过 generation commit 检查，不会重新发布被取消的 proposal。
- `PlannerPreviewUseCaseTest.java:169-202` 源码增加 legacy→application 与 application→legacy 重试共享 replay receipt 的双向覆盖；本审查只读测试，不执行它们。

### 3. PlanningActor 与 ThreadLocal

- Application 使用 `findAuthorized(actor, sessionId, token)`（`PlannerApplicationService.java:161-170`）得到 owner 已确认的 `StoredSession`。
- Apply/Cancel 在锁内调用 `refreshAuthorizedSessionForLifecycle(authorized, token)`；底层 `PlanAdjustmentService.java:1536-1539`的实现以传入 session 的 ownerType/ownerId 复读，不读 ThreadLocal。
- 新 controller Apply 路径构造 `PlanningActor(UserContext.getUserId(), UserContext.isAnonymous())`，调用显式 actor application overload（`PlannerController.java:171-182`）。这是 adapter 从认证上下文取身份；用例与锁内协调不再依赖 ThreadLocal。
- 旧 `PlannerService.applyAdjustment(String, request)` 和旧 domain `apply(String, request)` 仍保留 ThreadLocal 授权以兼容旧 Java caller；生产 controller 使用新 overload。这是显式保留，不是新路径绕回。

### 4. 提交次序、幂等、cache 和 receipt

- Application 顺序见 `PlannerApplicationService.java:187-210`。`lookupApplyReplay` 先于 `prepareApply`，且在同一个 session stripe lock 下，符合“owner → 幂等身份 → revision/proposal/epoch/rules → CAS”的顺序。
- 领域 preparation `PlanAdjustmentService.java:805-854` 校验 revision、proposal 存在/归属/TTL/epoch、option、required stop、canApply/feasible。`commitPreparedApply :875-892` 才 finalize 与 repository CAS；CAS conflict 返回而不完成后续副作用。
- `completeCommittedApply :897-915` 发生在 CAS 成功之后：proposal remove 异常被捕获记日志，随后 `continuations.afterApply` 旋转 epoch 并重建剩余任务，再返回当前版本/偏好/pending intent；application 随后组装 receipt、记录 replay。该顺序不将 DB、缓存、内存 continuation 与 HTTP receipt 误称为一个事务。
- fingerprint 包含 proposal、option、revision、forceApply（`:762-780,923-927`）；same key 不同身份被拒绝。replay 仍是同实例最多 256 项内存 LRU（`:83-89`），重启后不保证相同 receipt。
- 如果 `completeCommittedApply` 中 proposal 清理以外的后提交步骤抛异常，application 不会谎报成功，但 DB 可能已提交而 receipt/replay 未形成；implementation report 将此标为未验证。真实 response drop、commit 后崩溃没有覆盖。

### 5. Wire 与 CP2 兼容入口

- `PlannerController.java:145-182` 保持 Preview/Conversation/Apply 三条 POST path 与 `X-Plan-Session-Token` 空缺 fallback。Apply 改走 typed actor overload，success/failure body 仍由同样字段 map 构造，Controller outcome→HTTP 映射位于 `:188-213`。
- `ApplyAdjustmentResult` 与 `AdjustmentLifecycleExecution` 无 HTTP 数字字段；但 `PlanAdjustmentService.java:985-995` 的 legacy adapter 和 `prepareApply` rejection 仍从 `ServiceResult.status()` 转成 outcome。该残留不改变新 route mapping，但说明 domain 与 HTTP 的语义适配未完全清除。
- `PlanningApplicationService` 新 actor 方法和旧 `applyAdjustment(String, ...)` 并存；`PlannerService` 原入口也留存（`PlannerApplicationService.java:249-252`, `PlannerService.java:624-643`）。测试源码双向验证 legacy/new path replay 共用。

### 6. 状态协调 API 的封装风险（非阻断）

- Application 持锁依赖 `PlanAdjustmentService.withSessionApplyLock` 这种 generic `Supplier<T>` 方法（`:738-744`）。它把锁保存在现有 domain 状态 owner 中且方向仍 app→domain，所以没有重复 authority 或 DIP 倒置；但调用者可在锁里执行任意 callback，API 暴露了技术锁语义。
- `ApplyPreparation` 和 `ApplyCommit` 是 public record（`:791-803,870-873`）；`commitPreparedApply` 只拒绝 null/rejected preparation（`:875-876`），因此任一未来 Java caller 都可构造 preparation 并绕过 prepare 阶段。这条风险目前需要内部误调用才能触发，搜索到的唯一新 production sequence 是 application service 的正确调用链，不构成本次可达的 HTTP exploit。建议把阶段对象改为 service 私有签发的 opaque token，或限制协调 facade；暂列 P2 封装/防误用，不阻断当前冻结候选。

## 阻断/非阻断矩阵

| 项目 | 结论 | 等级 |
|---|---|---|
| Application 真实排序 Apply 多步流程 | 已从上一轮整段委托改为显式 workflow；通过 | **阻断项已解除** |
| Cancel/Reset application 编排 | application 同锁刷新、revision 检查、reset continuation、组装结果；通过 | **通过** |
| 锁、epoch、remainingActions、replay 单一权威 | 没有复制状态；legacy/new entry 复用同一 domain owner | **通过** |
| 显式 actor / ThreadLocal | 新 controller 路径只在 adapter 读 UserContext，Application 用 actor，locked refresh 用 owner tuple | **通过** |
| state coordination 抽象 | generic lock callback 暴露技术机制，但依赖方向正确且保持共享锁 | **P2 非阻断封装债务** |
| public preparation/commit token | 未来内部调用可跳过校验阶段，当前唯一生产顺序正确 | **P2 非阻断防误用债务** |
| HTTP 数字状态 | 新 Application API typed；domain legacy adapter 仍转换 status int | **P2 非阻断架构残留** |
| CAS → cleanup/epoch → receipt | 顺序清楚；缓存失败可继续 epoch/receipt；崩溃后无持久 replay | **逻辑通过，持久化恢复不承诺** |
| Controller wire / CP2 legacy | paths/token fallback/body map/legacy facade 保留 | **通过（实现方定向测试记录，本次未复跑）** |
| 冻结候选身份 | JSON、slice files、patch 均匹配指定值 | **通过** |
| 测试/运行时验证 | 清单报告 142 tests 通过；本次不跑测试，Redis/PG/response-drop/multi-process 未测 | **独立运行验证未做** |

## 最终建议

按 §3.4 的 workflow ownership 目标，R1 已实质完成此前被阻断的部分；Application 现在明确编排步骤，状态协调仍由唯一共享 domain owner提供，没有为了搬迁而复制锁或 epoch。可解除上一轮 architecture blocker。

合并前建议把 `withSessionApplyLock` 和 public `ApplyPreparation/ApplyCommit` 阶段 API 作为封装债务留入后续卡片；如果项目要求每条 domain result 完全不出现 HTTP 状态映射，再另行清掉 `ServiceResult` adapter。它们不是当前候选中已观察到的错误调用或状态分裂。



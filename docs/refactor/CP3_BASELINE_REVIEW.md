# CP3 状态协调基线复核

日期：2026-10-09  
范围：按 `COLA_REFACTOR_EXECUTION_PLAN.md` §3.4 盘点当前 preview/clarification、apply、cancel/reset 的状态协调、提交次序和 wire contract，供 CP3 实施前使用。只读审查；未运行 Maven、未写 target、未改业务源码。

## 当前真实调用图

- **Preview / 追问**：`POST /sessions/{sessionId}/conversation` 或 `/adjust/preview` → `PlannerController` 构造 `PlanningActor` 并补 token → `PlannerApplicationService.previewAdjustment` 通过 session port 查归属/能力并检查 baseRevision → `PlanAdjustmentService.previewForApplication` → `previewAuthorized`。后者调用 `continuations.begin/resume/current/commit`，保存 clarification 或生成 proposal；proposal writer 最终由 application 传入。
- **Cancel / Reset**：两个 preview 路径里，如果 `PlanPageContext.resetConversation` 为 true 或文本命中取消正则，进入 `PlanAdjustmentService.resetAdjustmentRound`。它取共享 `sessionApplyLocks` 条带锁，重新授权读取最新 session、校验 baseRevision，再 `continuations.reset(sessionId)`；不删除 Redis proposal，而是旋转 epoch/代次并清理 pending 与 remaining action，使旧 proposal 后续 apply 校验失败。
- **Apply**：`POST /sessions/{sessionId}/adjust/apply` → controller 的原 `ServiceResult`/HTTP 映射 → `PlannerApplicationService.applyAdjustment` 仍委托 `PlannerService.applyAdjustment` → `PlanAdjustmentService.apply` 获得同一 `sessionApplyLocks` 条带锁 → `doApply` 验权、幂等、revision、proposal、epoch、选项与约束校验 → `PlannerSessionRepositoryPort.update` CAS → 移除 proposal → `continuations.afterApply` 轮换 epoch 并续接剩余复合任务 → 组装回执并记录内存 replay。

关键源码：`PlanAdjustmentService.java:83-91,249-406,733-883`；`PendingIntentContinuations.java:10-42,46-63,136-143`；`PlannerController.java:144-179`；`PlannerApplicationService.java:108-157`。

## 权威状态与同步边界

- 目前 **同一个 `PlanAdjustmentService` 实例**持有 128 条 `sessionApplyLocks`、一个 `PendingIntentContinuations` 和最多 256 项 `applyReplays`（`PlanAdjustmentService.java:83-91`）。默认 Spring service 为 singleton。apply 与 reset/cancel 取同一按 session hash 选出的锁（`:733-749`）；不得在 application 新建另一组锁，也不得在 domain 留一组、application 再造一组。
- continuation 内部 `synchronized` 维护 pending entries、remainingActions、generations、epochs 和 resetAt（`PendingIntentContinuations.java:10-15,16-42`）。Preview 并不拿 `sessionApplyLocks`，其晚到结果靠 `begin` 生成 ticket、`commit` 检查当前 ticket 且在同一 continuation monitor 内执行动作（`:16-29`）。Cancel/apply 的 reset 会更换 generation 与 epoch，因此较早 preview 的 commit 不能复活旧 proposal。搬迁时必须共享同一个 continuation authority/epoch 和其原子 `commit/reset`。
- Session snapshot/revision 来自持久化 repository；proposal 在 cache/Redis；pending clarification、epoch、generation、remaining action、apply replay 都是 `PlanAdjustmentService` 进程内状态。跨进程重启不保留这批状态。CP3 不应承诺持久幂等或跨节点锁，除非另开设计和验证。

## Apply 当前校验与提交顺序

`doApply`（`PlanAdjustmentService.java:753-839`）当前执行顺序：

1. session 所有权/匿名 capability 校验（`:755-756`）；
2. 根据 `sessionId + idempotencyKey` 查内存 replay；同 key 不同 fingerprint 返回 409，同 fingerprint 回放结果（`:763-773`）；
3. requested revision 与 currentVersion 相等（`:776`）；
4. proposal 存在、归属 session、未过期、baseRevision 相等、epoch 当前有效（`:777-797`）；
5. option 存在、加站目标校验、重新计算 `canApply/feasible`；force 只能放行软可行性提醒（`:799-816`）；
6. 构造下一版 trip，再由 repository 以 expectedRevision CAS update；冲突直接返回（`:817-836`）；
7. DB 成功后 proposal remove、continuation `afterApply`（`:838-840`），最后组装成功 body、写 replay map、返回（`:846-882`）。

实现陷阱：数据库 CAS 与缓存 remove、内存 epoch 旋转、replay 记录不是一个事务。进程若在 DB commit 后、replay 写入前退出，重试可能看到 revision conflict；这是当前内存 replay 的边界。若更新成功但 proposal remove 失败，adapter 尽力 tombstone，随后 epoch 旋转阻止旧 proposal 再应用。CP3 应分别定义这种可观察行为，不能把 HTTP 成功响应等同数据库/缓存/内存状态全局原子提交。

当前 `requestFingerprint` 仅为 `proposalId|optionId|requestedRevision`，没有包含 `forceApply`（`:763-765`）；幂等键只在非空时生效，replay LRU 上限 256（`:83-89,766-773,879-881`）。迁移前应明确保持现有契约或单独提出兼容性变更，不能无意改变。

## Cancel / Reset 与竞争结果

- `resetAdjustmentRound` 与 apply 使用同一 session stripe lock；reset 重新读取授权 session 后校验请求 revision，再旋转 generation/epoch、清除 clarification 和 remaining queue，返回未修改行程的成功回执（`PlanAdjustmentService.java:733-742`）。
- 它没有调用 `proposalStore.remove`。因此取消是逻辑失效（epoch fencing），proposal 可能仍留在 cache/Redis；apply 必须继续先检查 `validProposal`，不可把“缓存中有 proposal”当成可应用。
- **cancel/reset 先拿锁**：epoch/generation 先旋转，随后同一旧 proposal apply 应被拒绝且不更新 DB；已开始的旧 preview 在 `commit` 处被 generation 拒绝。
- **apply 先拿锁**：CAS 成功后 proposal 被移除、epoch 旋转，版本只升一次；之后携带旧 revision 的 cancel 返回 conflict。相同幂等请求在 replay 仍驻留时返回原成功回执。
- Preview 不拿 apply stripe lock；其与 cancel 的线性化点是 continuation 的 `commit` vs `reset` monitor。不要迁移后让两者分别操作不同 continuation 实例，否则“晚到预览复活取消提案”会重现。
- `remainingActions` 在 apply 时先读出，再由 `afterApply` reset 清理旧队列并在新 revision 重新 `remember` 剩余复合动作（`PendingIntentContinuations.java:30-34`）；回执从新 pending 中返回 `pendingIntent`/`nextActionPrompt`（`PlanAdjustmentService.java:870-874`）。不能在 apply 成功后先无差别清空队列。

## CP3 必须保持的 wire contract

| 路径/行为 | 当前外部协议 |
|---|---|
| `POST /sessions/{id}/adjust/apply` | request DTO 包含 `proposalId`、`optionId`（空值默认 `option-1`）、`baseRevision`、`forceApply`、`idempotencyKey`、session token；header `X-Plan-Session-Token` 在 body token 缺失时补入（controller `:170-179`，DTO `ApplyAdjustmentRequestDto.java:12-29`）。成功 body 至少有 `ok:true`、`applied:true`、`sessionId`、`currentVersion`、`trip`、`changedSegments`、`unchangedStops`、`appliedPreferences`、规划元数据、`forced`、可选 `pendingIntent/nextActionPrompt` 与 `message`（service `:846-878`）。错误 status/body 由原 `ServiceResult` 透传；409 revision/幂等冲突、400 proposal/option/约束错误不可静默改为 200。 |
| `/sessions/{id}/conversation` 与 `/adjust/preview` 中取消/重置 | 仍用 POST、body/context 和 token header fallback；返回 HTTP 200，`ok:true, reset:true, cancelled:<bool>, modified:false, message`（`:733-742`）。`resetConversation=true` 与自然语言取消分别设置 `cancelled:false/true`。当前不单独删除 proposal。 |
| Apply replay | 同 session + 同非空 idempotency key + 当前相同 fingerprint 重放之前 ServiceResult；同 key 不同 fingerprint 409。仅进程内、最多 256 条，不是重启持久幂等。 |

## 搬迁给实施者的硬性陷阱

1. 把 apply/reset 编排移到 application 时，注入一个共享的生命周期协调器（或保持唯一的 `PlanAdjustmentService` 协调入口），让 preview 的 continuation、apply、cancel 共用同一把 session stripe lock 集合与同一 `PendingIntentContinuations`。不要在三个 use case 各建 map、锁或 epoch。
2. 保持 `continuations.commit(ticket, action)` 对 proposal 写入的原子门禁；cancel/reset 与 apply 的 epoch 旋转要与 proposal 验证及写入使用同一 authority。只搬方法但复制 `continuations` 会丢掉竞态保证。
3. `PlanAdjustmentService.resetAdjustmentRound()` 目前会再次调用依赖 `UserContext` 的 `findAuthorized`（`:736-739`），而 CP2 preview 已使用显式 `PlanningActor`。搬迁时应把已校验 actor/session context 显式传进共享协调流程，避免取消路径偷偷回退 ThreadLocal 或用另一授权结果。
4. preview 持久化 `proposalWriter` 在 continuation monitor 内执行（`PlanAdjustmentService.java:536-539`）。搬迁时保留锁顺序，勿引入“先拿 session lock、再等待 app lock”或反向顺序；当前 preview 不持 session stripe lock。
5. 保持 Apply 校验顺序与 CAS 前无副作用；只有 DB CAS 成功后才删 proposal、轮换 epoch/续接 remainingActions、记录 replay。并单独覆盖 DB 成功后缓存失败/响应丢失边界。
6. 加屏障测试覆盖 cancel-first/apply-first、慢 preview 在 cancel 后到达 commit、apply 后剩余动作延续、相同/不同幂等请求、DB CAS conflict 无 proposal/epoch 副作用。测试应使用同一协调器实例验证共享状态，另用新实例验证明确声明的重启边界。

本报告用于 CP3 实施前约束和提示，不是 CP3 完成验收。

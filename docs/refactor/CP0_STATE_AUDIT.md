# CP0 架构审查 B：规划状态一致性追踪

日期：2026-10-09  
审查范围：`D:/scenic-guide/ai` 中 Planner session、revision、proposal、conversation epoch、pending intent、remaining actions 与 apply replay 的读写生命周期。  
性质：只读源码审查；未修改业务代码，未运行 Maven，也未对整个项目作无缺陷结论。

## 结论摘要

规划状态目前分成两类权威来源：行程快照与版本由 `planner_session` / `planner_plan_revision` 持久化；提案由 Caffeine L1 加可选 Redis L2 保存；epoch、generation、追问 pending、复合操作剩余队列与应用幂等回执由 `PlanAdjustmentService` 实例内存保存。数据库版本条件更新为跨实例并发的最后防线；但对话生命周期和幂等回放没有同样的持久化权威。

单实例内，当前实现对取消、过期、版本变化和并发应用有多层失效校验，主要路径表现为拒绝旧提案、要求重新预览。若服务重启或请求被分配到另一实例，Redis 中提案可恢复，但本地 epoch 不可恢复，因而提案会被拒绝；pending 追问与复合队列直接丢失；成功应用的 replay 回执也不可恢复。这里能确认的是恢复能力不一致，不能据静态代码断言已发生线上数据错乱。

## 状态权威与生命周期

| 状态 | 当前权威位置 | 创建 / 更新 | 过期或失效 | 范围与实际影响 |
|---|---|---|---|---|
| Session 当前快照 | 持久化 `planner_session.current_trip_json`、`current_version`、`sync_revision` | `PlannerSessionRepository.create/update/linkFormalTrip` | 当前代码路径未发现 session TTL 或自动清理；版本冲突通过条件更新处理 | 可跨重启读取；session/revision 是规划快照的主要事实来源 |
| Revision 历史 | 持久化 `planner_plan_revision` | 创建时写版本 1；更新时与 session 更新处于 `@Transactional` 方法内 | 未发现清理策略 | 保留历史，但其保留期限与数据量边界未在本次范围内确认 |
| Proposal | Caffeine L1，Redis 可选 L2 | 预览写 L1，再异步写 Redis；确认成功后删除 | 业务 `expiresAt` 为创建后 15 分钟；每次读取还校验绝对过期时间、session、base revision、epoch | Redis 可跨实例读，但 epoch 仍由本地状态判定，因此 Redis 不能单独恢复完整提案生命周期 |
| Generation ticket | `PendingIntentContinuations.generations` 本地 map | 每轮对话 `begin` 换新 UUID；提交结果前用 `commit/current` 检查是否仍是最新请求 | 同 session 后续请求取代旧 ticket；最多保留约 4096 个 session，超限开始淘汰最早 key | 避免单实例内较慢的旧请求覆盖新对话结果；没有跨实例仲裁 |
| Conversation epoch | `PendingIntentContinuations.epochs` 本地 map | 首轮 `begin` 创建 UUID；`reset/afterApply` 更新 UUID | reset、apply 后旧 epoch 失效；超过 map 上限被淘汰也会失效 | proposal 中写入 epoch，但检查时要求本实例存在相同 epoch；重启或实例切换 fail closed |
| Pending intent | `PendingIntentContinuations.entries` 本地 map | 需要澄清时 `remember`，记录 intentId、baseRevision、expiresAt、intent | 10 分钟；resume 时检查 revision 与过期时间；取消、完整新指令或 reset 会清除 | 未持久化；重启、跨实例、容量淘汰后追问上下文丢失，用户需要重新说明/预览 |
| Remaining actions | `PendingIntentContinuations.remainingActions` 本地 map | 复合请求选择一项后存余项；成功 apply 后转成新的 pending intent | 没有自身 expiresAt；显式取消、放弃、队列耗尽或容量淘汰时清除；容量最多约 1024 session | 未持久化且无时间期限；多轮后重启/跨实例会丢失复合请求剩余任务 |
| Apply replay | `PlanAdjustmentService.applyReplays` 本地 access-order `LinkedHashMap` | apply 成功且请求带幂等键后保存结果 | 最大 256 条 LRU 淘汰；没有时间 TTL；重启即丢失 | 同实例可重放成功响应；另一实例/重启无法保证同键重放同一成功回执 |

## 逐项源码证据

### 1. Session 与 revision

- `ai/src/main/java/com/ai/guide/infrastructure/planner/repository/PlannerSessionRepository.java:83-104`：创建 session 并写入 `current_version=1`、`sync_revision=1`，另插入初始 revision。
- 同一 `create` 方法 `:53-105` 未标注 `@Transactional`；调用方 `PlannerService.create/createInternal` 与 `openFormalTrip`（`ai/src/main/java/com/ai/guide/domain/planner/service/PlannerService.java:135-145,585-614`）也未看到事务注解。因此 session 行插入与初始 revision 插入没有由已检查的调用链保证原子性。若第二条插入失败，可能留下已有 session、缺少初始 revision 的记录；这是根据事务边界得出的待验证风险，仍需结合实际数据库自动提交行为确认影响。
- 同文件 `:179-199`：更新先用 `WHERE session_id = ? AND current_version = ?` 做乐观并发控制，再插入 revision；更新方法标注 `@Transactional`。这可以避免并发调用基于同一版本都成功写入。
- 同文件 `:107-121`、`:166-176`：按 session/owner 查询快照、列出会话；本次检查的这些查询没有时间失效条件。PlannerSessionRepository 中未发现 session/revision 的 TTL 清理入口。
- `ai/src/main/java/com/ai/guide/domain/planner/gateway/PlannerSessionRepositoryPort.java:16-20`：端口快照仍以 `Map<String,Object>` 表示 constraints/trip；revision 与 syncRevision 是独立整数。

### 2. Proposal 与 epoch

- `ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java:451-488`：预览创建 proposal，base revision 取当前 session 版本，epoch 取 `continuations.epoch(sessionId)`，expiresAt 设为 now + `ProposalStore.DEFAULT_TTL_MS`，通过 generation ticket 校验后写入 store。
- `ai/src/main/java/com/ai/guide/domain/planner/service/ProposalStore.java:30-35`：L1 最大容量 10,000、15 分钟 expire-after-write；Redis 为可选注入。
- 同文件 `:46-64`：写入先落 L1，剩余期限计算后通过 `CompletableFuture.runAsync` 异步写 Redis；Redis TTL 最少 60 秒。
- 同文件 `:68-103`：先查 L1，再查 Redis 回补；恢复后仍检查 proposal 的 `expiresAt`。`PlanAdjustmentProposal.isExpired` 在 `ai/src/main/java/com/ai/guide/domain/planner/model/PlanAdjustmentProposal.java:56-58` 以绝对时间为准。
- 同文件 `ProposalStore.java:105-119`：`clear()` 只清 L1，不删除 Redis。这是方法当前实际行为；本次未发现业务路径依赖 `clear()` 做分布式清理。
- `PlanAdjustmentService.java:726-746`：apply 还校验 proposal 存在、未过期、session 匹配、base revision 匹配、epoch 有效。epoch 失效会返回 409，要求重新预览。
- `PendingIntentContinuations.java:35-42`：`validProposal` 只比较当前本地 epoch，`createdAt` 参数不参与判断；旧版缺 epoch 的 proposal 被拒绝。

**确定的边界行为**：Redis 恢复出的 proposal 如果落到一个没有对应本地 epoch 的实例，会被 `validProposal` 拒绝。这是当前 fail-closed 行为，保护了不一致状态下的应用，但意味着 Redis 方案缓存不能提供跨实例无缝恢复。

**可观察到的缓存边界**：Redis 写入最短 TTL 为 60 秒，可能比业务 `expiresAt` 多保留一小段时间；读取路径依然检查绝对过期，所以可确认的是过期缓存可能多占存储，不能据此推断过期 proposal 会被成功应用。异步写入与删除也没有串行化；若写入任务晚于删除完成，Redis 可能暂时出现旧 key，但 revision/epoch/绝对过期检查会继续限制其应用。此为代码竞态推断，未进行运行时复现。

### 3. Generation、epoch 与取消

- `ai/src/main/java/com/ai/guide/domain/planner/service/PendingIntentContinuations.java:10-29`：generation、epoch、resetAt 都在进程内；generation 超过 4096 个 session 时淘汰最早 session 的相关状态；`reset` 换 generation 和 epoch 并清 pending、remainingActions。
- `PlanAdjustmentService.java:254-260`：显式 reset/cancel 路径先处理取消；普通新对话会开始新 generation。
- 同文件 `:301-302`、`:342`、`:348`、`:488`：在设置 pending、清理 pending、保存 proposal 前检查 generation，阻止被更新请求取代的旧请求提交结果。
- `PlanAdjustmentService.java:682-691`：取消或 reset 检查当前 base revision 后调用 `continuations.reset`。此处没有删除 Redis/L1 中的 proposal；旧提案靠 epoch 校验而变为不可应用。
- `PendingIntentContinuations.java:29` 写入 `resetAt`，但当前扫描未发现读取该 map 的代码；它目前不构成额外的过期判断。

**并发范围**：上述 ticket 与锁均是 JVM 内状态。跨实例没有共享 generation。数据库 `current_version` 条件更新可以防止两个实例对同一版本重复推进版本，但不能阻止跨实例生成的两个对话互相取代，也不能确保只有最新一轮对话能写入 proposal/pending。

### 4. Pending intent 与复合队列

- `PendingIntentContinuations.java:49-55`：remember 时清理已过期 entries、上限 1024 淘汰最早项，创建 10 分钟 pending。
- 同文件 `:59-63`：resume 必须匹配 session 内 pending 的 base revision 且未过期；失效时删除 entry。
- 同文件 `:68-149`：处理取消、补充槽位、完整新命令、复合操作选择；用户取消时清 pending 和 remainingActions；选中复合项后把剩余项放入 `remainingActions`。
- 同文件 `:138-142`：remainingActions 以 map 保存，上限 1024，没有独立到期时间。
- `PlanAdjustmentService.java:293-296`：resume 失败后放弃 compound queue，再将当前消息作为新意图解析。
- `PlanAdjustmentService.java:787-789` 与 `PendingIntentContinuations.java:30-34`：成功应用后换 epoch、清旧 pending，再将 remaining actions 基于新 revision 重新记为 10 分钟 pending。

**影响判断**：待追问失效后行为是退回到新意图抽取，而非继续使用过期意图；这降低了错误套用旧操作的风险，但用户可能需要重述。剩余复合队列没有 TTL，生命周期主要由容量和显式处理控制；这会造成内存状态滞留或跨实例丢失，是否达到实际资源问题需要负载数据确认。

### 5. Apply replay 与确认提交

- `PlanAdjustmentService.java:80-87`：replay 是本地同步 LRU map，最多 256 条。
- 同文件 `:694-699`：apply 使用 128 个固定 JVM 锁按 session hash 分桶；这只协调当前实例，桶冲突只会增加本地串行。
- 同文件 `:712-723`：key 为 sessionId + 幂等键；fingerprint 由 proposalId、optionId、requestedRevision 组成，命中后返回以前的响应。
- 同文件 `:725-746`：先校验当前 revision、proposal 与 epoch。
- 同文件 `:772-788`：数据库更新成功后删除 proposal、推进 continuation epoch；`applyReplays.put` 在构造成功响应的末尾 `:827-830` 才执行。

**确认的可靠性边界**：数据库版本更新提供了持久化的单次版本推进保护；replay 缓存只提供“同进程、缓存项仍在”期间的成功响应重放。若数据库提交后、replay 保存前进程退出，或者重试落到另一实例，代码不能从持久层恢复该成功回执。版本大概率已推进，重试将遇到版本冲突，而不是重放原成功响应。此为从调用顺序得出的故障窗口推断，尚未通过故障注入验证。

**幂等 fingerprint 的范围**：当前 fingerprint 未包含 `forceApply`。同 proposal、option、revision 复用相同幂等键但更改 forceApply 时，会命中同一 replay 记录。对已经成功的请求，返回先前结果符合“同键重放”；对失败请求，失败结果未被放入 replay map。是否需要把 forceApply 纳入键语义，应由 API 契约明确。当前可确认 API 输入语义和 fingerprint 字段不完全同构，不能单凭这一点断言会造成重复写入。

## 评估分级

### 源码可直接证实

1. Planner trip/revision 持久化，proposal 由双层缓存承载，对话续接和 replay 状态由 JVM 内存承载。
2. proposal 的 business expiry 为 15 分钟，pending intent 为 10 分钟；remainingActions 和 applyReplays 没有时间 TTL，后两者以容量淘汰控制。
3. proposal 的适用性由 session、base revision、epoch 多重校验；reset/apply 轮换 epoch，旧 proposal 即便还在缓存也会失效。
4. 当前实例内有 generation ticket、同步方法与分桶锁；数据库乐观版本更新提供跨实例的版本竞争保护。
5. 成功 apply 的回执只进入本地 256 项 LRU，没有与 session/revision 数据库事务一同持久化。

### 合理但尚未实测的推断

1. 多实例或重启后，存活的 Redis proposal 会因本地 epoch 缺失而 fail closed；pending 和剩余动作会丢失；apply replay 无法恢复。
2. 数据库提交与缓存清理/replay 写入之间存在进程崩溃窗口，可能产生“服务端已提交、客户端重试拿不到原成功响应”的结果。
3. proposal 的异步 Redis 写和并发删除可能短暂重建旧 key；版本/epoch/过期门禁应使其不能应用，但会增加陈旧缓存残留。

### 本次不能下结论

- 未运行 Maven 或构造多实例故障注入；因此不裁定现有单元测试是否覆盖上述边界，也不报告运行时发生频率。
- 未追踪 Redis 配置是否在所有部署环境启用、是否存在其他外层粘性会话或单实例约束。
- 未检查数据库长期清理、备份与恢复策略，也不对其他后端领域作完整审计。

## 重构建议输入

进入后续切片前，建议把状态策略写成可验收契约：

1. 明确 session/revision 为行程权威；proposal 是有期限的派生预览；pending/remaining action 是否必须跨重启恢复；apply replay 需要至少覆盖客户端重试窗口。
2. 如果部署目标包含多实例/滚动重启，将 epoch、pending 的有效版本与 apply 的幂等回执放进共享持久层，并由唯一原子更新控制；proposal cache 只做可重建缓存。
3. 如果近期保持单实例部署，也要明确这是运行约束，并针对重启后的 fail-closed 行为给前端可理解的“重新预览/补充意图”路径。
4. 把 reset、proposal 过期、revision 变化、应用成功后仍有复合动作、提交成功后进程故障列为 CP0/后续验收场景；故障窗口需要注入测试才能升级为已验证结论。
5. 明确同幂等键下 `forceApply` 等字段的契约，统一 fingerprint 与被视为同一操作的请求字段。

# CP1-R4 独立架构复审：有效期、删除 tombstone 与重建边界

日期：2026-10-09  
审查范围：冻结 R4 的 proposal 缓存 adapter，重点核对绝对过期边界、未来 createdAt 校验、删除失败后跨 repository/service 对象的语义，以及 Spring 装配和单实例限制。  
冻结 JSON：`C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP1-R4候选冻结.json`  
冻结 JSON SHA-256：`fadf0bb5c03939b02ca5dc458a9addb10bddead4d7d2a55d7c0289487494a30b`  
冻结 tracked patch SHA-256（manifest）：`f8b3fcac031b73a2f3442b21db362118f3d79992e7ff97b030442d97c65acc86`

## 裁决

R4 修复了 R3 的未来时间戳缺口：Redis 快照的 `createdAt` 必须为正且不晚于当前时间；proposal 的寿命必须大于 0 且不超过 15 分钟，并且绝对过期时刻仍在未来。绝对过期边界是排他的：`now >= expiresAt` 即失效。R4 测试源码添加未来创建时间样本，验证非法快照被拒绝并尝试删除。

删除失败后的 tombstone 只在当前 repository 实例内有效。当前实例会阻止从 Redis 重新回填；新的 repository 实例没有旧 tombstone，仍能读出 Redis 残留快照。新增生命周期测试显示，同时重建 PlanAdjustmentService 后，新 service 的 epoch 拒绝旧 proposal，返回 409 且不写入 SQL revision。因此，该测试证明“新 repository + 新 service”在该场景下 fail closed；它不证明 tombstone 跨实例共享，也不保证所有多节点部署时的 proposal 删除语义。

Spring 装配保留 `@Component`、公共无参构造器及可选 setter 注入，端口接口未变。静态审查没有发现 bean/API 兼容破坏；本轮没有启动 Spring context，也没有运行 Maven 或连接真实 Redis。

**建议裁决：R4 的单实例 adapter 生命周期与快照有效期校验通过。删除 tombstone 明确属于尽力而为的进程本地负缓存，不能作为跨节点删除一致性保证。**

## 冻结与范围

- 冻结 JSON SHA 与指定值一致。
- R4 manifest 声明的 tracked patch SHA 为 `f8b3fcac...acc86`；本复审接受父任务已完成的候选身份核验，不改动冻结候选。
- 审查只读源码和测试，不执行 Maven，不修改业务源码。新增本报告是本任务唯一写入。
- 工作区中有其他既存 tracked/untracked 改动；本审查未清理或覆盖它们。

## 源码证据

### 1. 绝对有效期和边界

- `ai/src/main/java/com/ai/guide/domain/planner/model/PlanAdjustmentProposal.java:23,56-63`：默认 TTL 为 900,000ms；`isExpired(now)` 使用 `nowMillis >= expiresAt`，故到达 expiresAt 的时刻即已过期。
- `ai/src/main/java/com/ai/guide/infrastructure/planner/proposal/CaffeineRedisProposalRepository.java:91-102`：put 用 `remainingMs <= 0` 判过期，已过期对象不进入 L1，并触发 L2 删除；临界点与 `isExpired` 一致。
- `:27-30`：L1 Caffeine 有 15 分钟 expire-after-write；`get :157-168` 对 L1 对象还会做绝对时间校验。
- `:224-232`：L2 恢复同时做 lifetime 上限和绝对 expiresAt 检查，不依赖 Redis key TTL 作为唯一有效性判断。因此 Redis 最短 60 秒 TTL（`:91-94`）可能让过期 key 暂存一小段时间，但不会因此恢复已过期 proposal。对于剩余时间小于 60 秒的正常 proposal，缓存可能在绝对截止之前因 Redis TTL 秒级取整而提前 miss，影响临近过期时的可用性，不会延长授权有效期。
- `ProposalRepositoryPortContractTest.java:51-71`：源码测试断言 `expiresAt-1` 有效、`expiresAt` 与 `expiresAt+1` 均无效；本次没有运行测试。

### 2. R3 future-createdAt 缺口已补

- `CaffeineRedisProposalRepository.java:229-232`：先取 `now`；要求 `baseRevision > 0`、`createdAt > 0`、`createdAt <= now`，再要求 `0 < expiresAt-createdAt <= DEFAULT_TTL_MS` 且当前尚未过期。
- `ProposalRepositoryPortContractTest.java:438-459`：样本 `future-created` 把 createdAt 设置为当前时间后 60 秒；快照读取后应被拒绝。`:` 451-459 也涵盖错 key、缺 session/epoch/revision/expiry 和超长 lifetime。
- 因为 future-created 快照走 `isRestorable=false`，`get :181-185` 尝试删除它；如果 Redis 删除本身失败，当前 adapter 会留下 tombstone，避免同一实例继续回填，但另一个实例仍可重复读到坏快照并重复拒绝。

### 3. 删除失败与两个 repository/service 对象

- 删除流程：`CaffeineRedisProposalRepository.java:196-222` 在当前实例锁内清除 L1 并调用 Redis delete。删除成功清本地 tombstone；Redis 未配置或 delete 抛异常则写本地 Caffeine tombstone。`get :157-172` 在读 L2 之前检查该 tombstone。
- Tombstone 是 `:31-34` 的 adapter 字段，容量上限 10,000，15 分钟过期。它不写 Redis/数据库，不能跨 repository 实例、进程重启或节点共享；容量淘汰也可能提前移除。
- `ProposalRepositoryPortContractTest.java:345-403` 是本轮关键证据：同一 repository 首先从 Redis 恢复快照，删除遇到模拟 Redis 故障后，清空 L1 仍由 tombstone 阻止本地回填（`:378-382`）。随后创建第二个 repository，测试明确预期它能从同一 Redis 残留值恢复 proposal（`:384-389`）。再创建第二个 PlanAdjustmentService 后尝试 apply，断言 409、revision 仍为 1、且没有调用 sessions.update（`:391-402`）。
- 结论边界：实测源代码意图证明“新 repository + 新 service”拒绝旧 epoch，避免该测试场景写入正式计划。它**没有**证明 Redis 中删除已经全局生效；也没有覆盖“repository 重建但继续由仍持有旧有效 continuation epoch 的 service 判定”的人工组合。实际生产 Spring singleton 通常会一起持有一个 service 和一个 repository，但多节点路由、重启时序和 continuation 是否共享必须由部署架构保证。不能把本地 tombstone 当作跨节点 fencing。
- `PlanAdjustmentService.apply` 的 fail-closed 检查位置应与 `PlanAdjustmentService.java` 当前实现联读；测试证明了新 service 路径无 SQL 写入，但本报告未将其扩张为任意并发拓扑的保证。

### 4. Spring wiring、DIP 与限制

- `CaffeineRedisProposalRepository.java:21-22` 保留 `@Component` 并实现 `ProposalRepositoryPort`。
- 公共无参构造器 `:48-50` 保留给 Spring；Redis 和 ObjectMapper setter `:77-85` 仍标为 `@Autowired(required=false)`。测试专用 Executor/hook 构造器为 package-private；生产公开构造器签名仍在。
- `PlanAdjustmentService` 依赖 domain port，具体实现仍位于 infrastructure；R4 没有改变 port contract。
- Redis GET/SET/DELETE 与每个 key 对应的 stripe 锁仍同步发生；不同 ID 哈希到同一 stripe 时会被慢 Redis 请求串行化。该限制未由本轮变更解决，且未压测。
- generation、锁和 tombstone 都是进程本地状态。多实例共享 Redis 时，没有共享 generation/锁或持久化 delete marker；跨节点删除后的旧值恢复仍是架构限制。测试中 service epoch 保护了重建 service 的特定情形，不是通用分布式一致性协议。

## 问题与建议

| 级别 | 状态 | 证据/触发条件 | 说明 |
|---|---|---|---|
| 通过 | R3 future timestamp 缺口已修 | `CaffeineRedisProposalRepository.java:229-232`; 测试 `ProposalRepositoryPortContractTest.java:458-459` | `createdAt > now` 快照不可恢复。 |
| 通过 | 绝对到期边界一致 | `PlanAdjustmentProposal.java:61-63`; test `ProposalRepositoryPortContractTest.java:67-71` | exact expiresAt 时刻失效。 |
| P2 条件限制 | tombstone 不具分布式持久性 | adapter `:31-34,210-222`; two-repository test `ProposalRepositoryPortContractTest.java:378-402` | 新 adapter 能恢复 Redis 残留；新 service epoch 阻断测试场景中的写入，但跨节点一致性仍未建立。 |
| P2 可用性 | Redis TTL 秒级下限/取整可使临近到期对象提前 miss | adapter `:91-94` | 只影响 deadline 前最后不足一秒/最短 TTL 场景的读取可用性，不会接受过期 proposal。 |
| P2 性能建议 | Redis I/O 在 256 stripe 临界区内 | adapter `:126-136,154-194,196-208` | 慢 Redis 会阻塞相同 stripe 上的其他 proposal ID；需要压测后判断优先级。 |
| 未验证 | Spring 实际上下文装配、真实 Redis 故障及并发行为 | 本轮静态审查边界 | 未运行 Maven、未启动 context、未做多节点注入。 |

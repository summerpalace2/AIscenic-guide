# CP1-R3 独立架构复审：proposal tombstone 与恢复边界

日期：2026-10-09  
审查范围：冻结候选的 domain/infrastructure 依赖、删除 tombstone 的边界语义、Spring 装配、恢复数据校验及既有风险复核。  
冻结 JSON：`C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP1-R3候选冻结.json`  
冻结 JSON SHA-256：`fa9bf57a2785b0ee490fb983f888810667e6b8d4e32fe7e0569d6992f554d97a`  
冻结 tracked patch SHA-256：`dedb565477e4fc688a80d13ba5060e7537f5b44d698bb272520ec70c1da6100b`

## 裁决

**CP1 的依赖倒置目标继续满足：缓存 adapter 位于 infrastructure，PlannerService 只依赖 domain port。** Spring bean 入口保留公共无参构造器、`@Component` 和可选 setter，未发现公开 API 或端口签名破坏。

**R3 新增的本地删除 tombstone 在单实例、容量未触顶时能阻止 Redis 删除失败后通过 L2 回填同一 proposal；R2 的 same-instance get/remove、put/remove 和 generation 保护仍成立。** tombstone 有明确的容量和 15 分钟时间界限，所以它是有界负缓存，不是持久删除记录。容量淘汰、进程重启或其他节点不共享 tombstone 时，仍可能从残留 Redis key 重新读出未过期 proposal。PlannerService 的 revision/epoch 检查降低了误应用风险，但不能把本地 tombstone 变成跨实例删除保证。

本轮还发现 Redis 快照校验允许 `createdAt` 和 `expiresAt` 同时位于未来，只要二者间隔不超过默认 TTL。若缓存数据损坏/被错误写入未来时间，绝对 expiresAt 检查无法兑现“从创建时起 15 分钟”的限制。该路径以 Redis 快照异常为前提，属于 P2 数据校验缺口。

**建议裁决：架构迁移与单实例缓存生命周期切片通过；跨实例/容量淘汰删除语义及 future timestamp 校验需记为剩余项。** 本轮按要求未运行 Maven、未连真实 Redis、未做多实例故障注入。报告中的测试引用是源码覆盖检查，不是本轮复跑结论。

## 冻结一致性核验

- 冻结 JSON SHA 与用户提供值一致。
- 当前 tracked patch hash 与冻结清单一致。
- 冻结清单记录的 untracked、CP1 slice hashes 和 `git status --porcelain=v1 -uall` 均匹配。
- 本报告为本次唯一新增审查文件；未修改代码或既有工作区改动。

## 源码审查

### 1. Architecture / DIP 与调用方

- `ai/src/main/java/com/ai/guide/domain/planner/gateway/ProposalRepositoryPort.java:9-14` 定义 `put/get/remove` 端口，仅依赖领域模型 `PlanAdjustmentProposal`。
- `ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java:17,76,112-164` 只依赖 `ProposalRepositoryPort`；当前生产调用 `get/put/remove` 均通过该端口。
- 对 `ai/src/main/java` 静态检索未发现旧 `ProposalStore` 引用或 domain 对具体 `CaffeineRedisProposalRepository` 的依赖；该 adapter 位于 `infrastructure.planner.proposal` 并实现 port（`CaffeineRedisProposalRepository.java:1-22`）。
- Architecture guard 仍包含禁止 planner domain 依赖 Caffeine/Redis、要求 proposal infrastructure 实现 domain port 的规则，位置见 `ai/src/test/java/com/ai/guide/architecture/ColaArchitectureGuardTest.java:156-170`。

这证明当前 proposal 存储边界方向正确；不代表 planner domain 的其他技术依赖也已清理。

### 2. Spring wiring / API 兼容

- `CaffeineRedisProposalRepository.java:21-22` 保持 `@Component` 并实现端口。
- 公共无参构造器 `:44-46` 仍用于生产；可选 setter 注入 `StringRedisTemplate` 与 `ObjectMapper` 位于 `:77-84`。带 Executor、测试 hook 的构造器为 package-private，未扩大公开 API。
- 生产源码静态检索只发现一个 `ProposalRepositoryPort` 实现，因此按类型注入没有当前歧义。未启动 Spring context，Bean 的实际创建与 optional dependency 注入未进行运行时核验。
- Port 方法签名及 `remove` 返回 L1 旧值的既有语义未改变。Redis-only 的旧值仍可能被删除但 `remove` 返回 null；现有 PlannerService 调用方不消费返回值。

### 3. 同实例并发与 generation 生命周期

- 256 个 stripe 锁与 per-key state 定义在 `CaffeineRedisProposalRepository.java:40-41,240-249`。put/get/remove 和 Redis worker 都使用同一 proposal ID 对应的 stripe。
- `put :96-113` 把 L1 修改与 generation 写入置于同一锁内；过期 proposal 会失效 L1、推进 generation 并删除 L2 `:99-102`。
- worker `:126-142` 在同一 stripe 下核验 state 身份和 generation 后才写 Redis，finally 递减在途数量；拒绝执行会在 `:116-124` 清理计数并降级保留 L1。
- `get :154-194` 锁住 Redis 读取、快照校验和 L1 回填；`remove :196-208` 必须等待读取完成后再清理 L1/L2，因此同一 adapter 内的 delayed-get 回填竞态被关闭。
- 在途计数归零后 `removeStateIfIdle :234-238` 删除 generation state；单实例正常完成/Redis 异常/执行器拒绝路径未发现确定性的 state 泄漏。

测试源码补齐了这些路径：`ProposalRepositoryPortContractTest.java:81-200` 覆盖写入和同 ID 排序，`:202-313` 覆盖延迟 get/remove 与 Redis set/remove，`:316-334` 覆盖执行器拒绝，`:338-379` 覆盖删除失败 tombstone，`:383-421` 覆盖过期 put 清理旧缓存。此次未运行这些测试。

### 4. Tombstone 的有效范围与边界

- tombstone 是 adapter 本地 Caffeine cache，最大 10,000 条，写入后 15 分钟过期（`CaffeineRedisProposalRepository.java:31-34`）。
- 删除在 Redis 未配置时写入 tombstone；Redis delete 成功时清除 tombstone；delete 抛错时写入 tombstone（`:210-222`）。
- `get` 先查 L1，再查 tombstone，存在时不读 L2（`:157-172`）。成功 Redis set 会清掉同 key tombstone（`:126-135`），允许后续同 ID 的有效新 proposal 恢复。
- 单实例内 tombstone 从删除时起保留 15 分钟，长于一个正常 proposal 的最大剩余 TTL；但 Caffeine `maximumSize(10_000)` 允许在 15 分钟前因容量压力淘汰。tombstone 因此提供尽力阻止回填的短期负缓存，不保证任意负载下的永久删除。
- tombstone 不在 Redis/数据库中，也不随进程重启恢复；不同应用实例各自拥有该 cache。若 A 节点删除失败、B 节点没有 tombstone，B 可从残留 L2 key 回填。R3 改善单实例删除故障，未建立跨节点 delete fencing。
- 删除失败测试中，`:358-369` 只创建一个 repository；`:363` 调用的是 `clearLocalCache()`，没有重建 repository。因此该测试证明 L1 清空后同一 adapter 的 tombstone 能阻止回填，不证明 repository/进程重启后 tombstone 可恢复。测试名中的 “ReconstructedApply” 实际新建的是 `PlanAdjustmentService`，而传入的仍是同一个 repository。

**等级：P2 条件性语义限制。** 如果 port 的 `remove` 契约需要在 Redis 故障、容量压力、进程重启和多实例下仍保证 `get` 不再返回已删除项，当前方案不满足。若仅要求单节点短期 fail-closed 且 PlannerService epoch/revision 负责应用安全，当前实现可接受，但应把此限制写入契约。

### 5. Redis 快照形状与有效期验证

`isRestorable` 位于 `CaffeineRedisProposalRepository.java:224-232`，检查 proposal ID 与请求 key 一致、session 非空、epoch 非空且非 `INITIAL`、base revision 和 createdAt 为正、lifetime 为正且不超过 15 分钟、expiresAt 尚未过去。它能拒绝多数缺字段、串 key、无 epoch 和超长 TTL 快照。

**剩余 P2 校验缺口**：没有要求 `createdAt <= now` 或将其限制在允许的时钟偏差范围。示例：Redis 中的 proposal `createdAt=now+一年`、`expiresAt=createdAt+10分钟` 满足 lifetime 上限且 expiresAt 在未来，因此 `isRestorable` 返回 true。该 proposal 从当前时间看可能活一年以上。因为 Redis 是内部缓存而非浏览器输入，触发依赖异常/错误快照；但服务端对 Redis 数据进行完整性校验时，时间戳逻辑应同时验证起点不能无界处于未来。

测试 `ProposalRepositoryPortContractTest.java` 有缺失字段和超长 lifetime 样本（`:423` 后的 `restoredRedisProposalMustMatchKeyAndRequiredShape`），未见未来 createdAt 用例。本审查不判断 Redis 是否可被外部写入。

### 6. 其余先前发现复核

- **TTL**：L1 以 `DEFAULT_TTL_MS` expire-after-write；Redis TTL 仍是 `Math.max(60, remainingMs / 1000L)`（adapter `:28-34,91-94`）。Redis key 可能逻辑过期后多保留最多约一分钟的短缓存，但绝对时间校验阻止正常过期 proposal 被回填；未来时间戳缺口见上。
- **Redis 降级**：put 的 executor 拒绝和异步写异常都保留 L1；delete 异常写本地 tombstone；Redis get/read/decode 异常仍被 catch 后返回 null（`:173-193`）。当 L1 miss 且 Redis 临时不可用，服务层仍无法区分“未命中/过期”和“后端暂时不可用”，调用方得到的错误可能要求重新预览。P2 可用性语义仍未改变。
- **Stripe 头阻塞**：Redis GET、SET、DELETE 均在对应 stripe synchronized 块内执行（`:126-136,154-194,196-208`）。不同 proposal ID 若 hash 到同一条 256 stripe，会因 Redis 网络延迟互相等待。属于性能建议，未压测。
- **跨实例 generation**：`redisKeyStates` 和 stripe locks 为 adapter 实例字段；多个 JVM 共享 Redis 时无共同 generation/锁。旧异步 put 跨节点可晚于另一节点删除；本地 tombstone也不能跨节点协调。PlannerService apply 仍校验 base revision/epoch（`PlanAdjustmentService.java:725-747`），fail closed 会减少误应用风险，但不等于共享缓存的一致性保证。

## 问题表

| 级别 | 分类 | 发现 | 实际条件/建议 |
|---|---|---|---|
| 通过 | 架构方向 | domain 依赖 proposal port；Caffeine/Redis 实现留在 infrastructure，guard 防回流。 | 源码静态审查通过；Spring context 未启动。 |
| P2 | 删除契约/容量边界 | tombstone 是每进程最多 10,000 条、15 分钟的 Caffeine 负缓存，容量压力可提前淘汰，重启/其他节点不共享。 | Redis delete 失败且仍有有效旧 key；当前 epoch/revision 门禁降低应用风险。明确契约；如需跨节点保证，用共享 tombstone/version fencing。 |
| P2 | 恢复数据校验 | `isRestorable` 不限制未来 createdAt。 | Redis 快照时间戳错误且 expiresAt 仍未来；增加未来时间/时钟偏差门禁及回归样本。 |
| P2 | 可用性语义 | Redis GET 错误折叠成 null，Service 层误呈现为提案不存在/过期。 | L1 miss 且 Redis 暂不可用；可让 port 区分不可用与确实未命中。 |
| P2/P3 | 多实例 | generation、锁、tombstone 都是实例本地；Redis L2 并不具备全局顺序。 | 仅在多实例部署目标下是阻断性限制；用共享原子版本/条件写删。 |
| P3 | 性能建议 | 256 stripe 内执行同步网络 I/O，碰撞 key 头阻塞。 | Redis 慢且多个 key 共用 stripe；需压力数据判断。 |

## 验证限制

本轮没有运行 Maven、启动 Spring、连接 Redis、做并发压力或多实例测试。源码审查不能确认报告中的测试是否通过或线上部署是否启用多副本。结论只针对 CP1-R3 proposal adapter；不代表其他模块不存在问题。

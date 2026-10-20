# CP1-R2 独立架构复审：proposal 缓存生命周期与并发

日期：2026-10-09  
审查角色：B，独立只读审查  
对象：R2 冻结候选中的 `CaffeineRedisProposalRepository`、端口调用方、Spring 组件装配及先前 R1 发现  
冻结 JSON：`C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP1-R2候选冻结.json`  
冻结 JSON SHA-256：`315cd34aea84b7b3c0b334a422f45515cfe4fa1bd6de8a9a48acc4fbb9c18ffd`  
冻结 tracked patch SHA-256：`dedb565477e4fc688a80d13ba5060e7537f5b44d698bb272520ec70c1da6100b`

## 裁决

**R2 的同一 repository 实例内状态读写和 Redis 异步写入竞态修复通过静态审查。** put、get、remove 共享由 proposal ID 映射出的 256 个 stripe 锁；generation state 只保留仍有排队写任务的 key，任务完成或执行器拒绝时计数归零会清理 state。R1 的延迟 Redis get 回填、remove 与已入临界区的 Redis set、逆序同 ID put 三种路径都有可控测试源码。

**端口签名、`PlanAdjustmentService` 依赖方向及 Spring 公开构造/API 没有发现破坏性变化。** 但 R2 仍有明确边界：256 stripe 锁包住同步 Redis I/O，跨 key hash 碰撞会互相阻塞；跨进程没有共享 generation/fencing；Redis 读取失败仍折叠为未命中。它们不构成本次同实例竞态修复的失败，但对多实例一致性和 Redis 延迟下的可用性需要明示。

验收意见：**单实例 proposal cache 并发切片可通过源码审查；若验收目标包含多实例 Redis 全局顺序，当前实现仍不满足，需另外设计共享 fencing。** 本轮未运行 Maven 或启动 Spring/Redis，因此测试只核对源码，集成结论未验证。

## 冻结完整性核验

- 冻结 JSON SHA 与请求值一致。
- 当前 `git diff --binary HEAD` 与冻结的 tracked patch SHA 一致。
- 冻结清单中的 untracked 和 CP1 slice 文件 hash 全部匹配；`git status --porcelain=v1 -uall` 与冻结状态一致。
- 本报告是新增审查产物，不属于候选代码；未修改源代码或用户既有工作区文件。

## 代码证据与分析

### 1. 同实例 256 stripe 与状态生成周期

文件：`ai/src/main/java/com/ai/guide/infrastructure/planner/proposal/CaffeineRedisProposalRepository.java`

- `:36-37` 定义 `redisKeyStates` 和固定 256 个锁；`:211-218` 根据 proposal ID hash 选择 stripe。
- `put` 在 `:84-104` 的同一 stripe 临界区内，创建/读取 state、写 L1、推进 generation，并登记 `scheduledWrites`；临界区结束后才提交 executor 任务（`:106-113`）。这把 L1 顺序和 L2 generation 赋值放在同一个 per-stripe 顺序中。
- `executeRedisWrite` 在 `:115-130` 获取同一 stripe，只在 state 对象身份仍匹配、generation 仍为最新时执行 Redis set；finally 始终调用 `finishScheduledWrite`。
- `finishScheduledWrite` 在 `:132-140` 获取同一锁、减少计数，`removeStateIfIdle` 在 `:205-209` 于计数归零时删除 map 项。正常执行、Redis set 抛异常、executor submission 被拒绝都会通过对应清理路径回收 state。
- `get` 和 `remove` 也使用同一 stripe：`get :144-180`，`remove :185-194`。`get` 保持锁直到 Redis 读取、反序列化及 L1 回填完成；`remove` 因而等待正在进行的同 key get 后再失效 L1 并删除 Redis。

因此 R1 的三个同实例竞态现已被可见的临界区顺序覆盖：

1. put 已登记并等待执行时，remove 先推进 generation；旧 task 获锁后发现 generation 不匹配而跳过。
2. 两次同 ID put 的 generation 在同一锁内按 L1 写入顺序递增，异步 task 即使逆序运行，较早 generation 不能覆盖后值。
3. Redis get 持锁期间 remove 不能完成；get 回填后 remove 再清除 L1/L2，不会出现 remove 完成后旧 read 回填 L1。

新增测试源码对应 `ProposalRepositoryPortContractTest.java:81-139`、`:142-200`、`:202-261`、`:264-313`。测试使用受控 executor、latch 与 mock Redis；本轮未执行这些测试。

### 2. Pending generation 状态清理

- state 只在 `put` 需要 Redis 写入，或短暂进入 put 临界区时创建（`:91-103`）；没有 Redis 写任务时 `removeStateIfIdle` 立即清除。
- 已排队写入先递增 `scheduledWrites`，任务结束/异常或 executor 拒绝会调用 `finishScheduledWrite`（`:106-130`），归零后移除 state（`:132-140,205-209`）。
- get/remove 对没有 state 的 key 不创建 tombstone；但由于这些操作和 put 使用同一 stripe，单实例内不存在尚未登记、同时又能越过 remove 的同 key异步任务。处于在途状态的旧 task 会使计数保持大于零，state 不会过早删除。
- executor rejection 现在捕获 `RuntimeException`，调用计数清理并保留 L1（`:106-112`）；相应测试在 `ProposalRepositoryPortContractTest.java:316-340`。

在正常返回、Redis 抛出异常和 executor 拒绝路径上，未发现 generation map 的确定性永久泄漏。若 executor 接受任务后永不运行，或同步 Redis I/O 永久挂起，scheduled count/state 会保留；其期限取决于线程池和 Redis 超时配置，本次没有运行环境证据。

### 3. 256 stripe 的性能边界

`get` 会在 stripe 锁内同步执行 Redis GET（`:144-180`）；`remove` 在锁内同步 Redis delete（`:185-203`）；异步 worker 在锁内序列化并同步执行 Redis SET（`:115-130`）。同 stripe 的不同 proposal ID 也共用锁。

触发条件：不同 key hash 到相同的 256 个 stripe 之一，且其中一个 Redis 调用延迟。此时其他 key 的 get/remove/put 更新或写 worker会等待同一 monitor。对大量 key，碰撞不可避免；是否显著影响吞吐要依 Redis 延迟与并发量测量。

等级：**P2/P3 性能建议，未测量**。若实测有影响，可考虑按 key 管理锁并带 idle 清理，或把 Redis I/O 移出粗粒度锁并用条件写/版本 fencing 保持正确性。不能简单移出 get 锁，否则会重新打开 read-remove 回填竞态。

### 4. 多实例限制仍存在

- `redisKeyStates` 与 `redisKeyLocks` 均是 repository 实例字段（`:36-37`），generation 仅在一个 JVM 及一个 Spring bean 内单调。
- 两个应用实例共享 Redis 时，各自对同 key 的锁和 generation 独立，无法保证跨节点 put/remove 的全局顺序。实例 A 延迟的旧 put 仍可能晚于实例 B 的 remove 写回 Redis。
- 这是代码作用域可以直接证明的边界，影响是否可接受取决于部署契约。Planner 的 conversation epoch 仍为实例本地状态；旧 proposal 在没有匹配 epoch 的实例上会 fail closed，但 Redis 可能保留陈旧条目并导致预览/应用在不同实例之间不可恢复。

等级：**多实例部署下的 P2 条件性一致性限制**；没有证据证明目前部署多实例或此类请求已发生。若多实例属于目标部署，需 Redis Lua/事务、版本字段/条件写删或其他共享 fencing；JVM stripe 不能替代分布式锁。

### 5. Redis 降级、TTL 与错误语义（先前发现复核）

- executor 拒绝已修正为不向调用方抛出并清理 state（`:106-112`）；异步 Redis set 异常捕获后保留 L1 fallback（`:115-130`）。`remove` 的 delete 异常仍被忽略，L1 会清除（`:196-203`）。
- Redis GET/反序列化失败在 `get :159-176` 捕获后返回 null。若 L1 miss 且 Redis 临时不可用，service 仍会把“后端不可用”解释为“proposal 不存在/过期”；这项 P2 可用性错误语义仍未改变。
- TTL 仍使用 `PlanAdjustmentProposal.DEFAULT_TTL_MS` 配置 L1；Redis TTL 的取值逻辑见 `put :86-88`。绝对 `expiresAt` 在回填前校验 `:163-166`。Redis key 可能因至少 60 秒 TTL 在逻辑到期后多保留短时间，但过期 proposal 不会因此通过读取门禁。
- 删除失败可能留下 Redis key；同一实例当前 epoch/base revision 校验仍保护应用路径，但另一实例无法依赖本地 generation 阻止旧快照读取。真实 Redis 故障和多实例恢复本轮未测。

### 6. 端口、调用方和 Spring wiring

- `ai/src/main/java/com/ai/guide/domain/planner/gateway/ProposalRepositoryPort.java:9-14` 的方法签名仍是 `void put`、nullable `get` 和返回 L1 旧值的 `remove`；R2 未扩展或改变端口契约。
- `PlanAdjustmentService.java:17,76,112-164` 依赖端口接口；生产调用 `get/put/remove` 位于 `:265-270,369,489,727,736,788`。未见 domain 反向引用具体 adapter。
- adapter 保持 `@Component` 且实现端口（`CaffeineRedisProposalRepository.java:21-22`）；公共无参构造器在 `:44-45`，可选 setter 注入在 `:73-80`。有唯一可见生产实现，静态上 Spring 可以按类型注入。真实 Spring context 本轮没有启动。
- executor 和 `afterL1WriteHook` 构造器为 package-private（`:48-72`），测试 seam 未改变公开 API；生产无参 bean 仍使用 common pool 和空 hook。

## 已证实问题 / 建议

| 等级 | 结论 | 触发条件与影响 | 证据 |
|---|---|---|---|
| 通过 | 同 repository 实例内 put/get/remove 的同 key 操作按 stripe 串行，R1 的 L1/L2 竞态得到覆盖。 | 适用于同一个 adapter bean；锁会阻塞同 stripe 的不同 key。 | adapter `:84-194`；新增受控测试 `ProposalRepositoryPortContractTest.java:142-313` |
| P2/P3 | Redis I/O 在 256 stripe 锁内，碰撞 key 发生头阻塞。 | Redis 调用慢且并发 key 共享 stripe。 | adapter `:115-130,144-180,185-203` |
| P2（多实例条件） | generation 与锁只在单 JVM 生效。 | 多实例共享 Redis 时，节点间异步 put/remove 无总序。 | adapter 实例字段 `:36-37` |
| P2 | L1 miss 时 Redis GET 出错返回 null，错误无法区分未命中和存储不可用。 | Redis 唯一保留 proposal 且短暂不可用。 | adapter `:159-176`，service 处理 `PlanAdjustmentService.java:727-729` |
| 通过 | executor submission rejection 会降级保留 L1，并回收计数。 | 本地线程池拒绝新任务。 | adapter `:106-112`；测试源码 `ProposalRepositoryPortContractTest.java:316-340` |
| 信息项 | TTL/端口签名/Spring 公共 API 保持兼容。 | 代码审查范围内。 | `ProposalRepositoryPort.java:9-14`、adapter `:21-80` |

## 验证边界

本轮按要求未运行 Maven、未启动 Spring、未连接真实 Redis，也未执行压力或多实例故障注入。测试结论是对冻结测试源码的覆盖审阅，不是本轮复跑结果。本裁决只涵盖 CP1-R2 proposal 切片，不代表后端其他模块不存在问题。

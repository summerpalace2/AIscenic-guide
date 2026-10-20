# CP1-R1 独立架构复审：proposal 写入竞态修复

日期：2026-10-09  
审查范围：冻结候选中 `CaffeineRedisProposalRepository` 的 generation/lock 改动、缓存状态生命周期、Spring 装配、API 影响及 `ProposalRepositoryPort` 契约。  
冻结清单：`C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP1-R1候选冻结.json`  
冻结清单 SHA-256：`04d7c02ff4478d5412b985c59202b11c9504d1f1ee867112fc83f8522330d641`  
冻结 tracked patch SHA-256：`dedb565477e4fc688a80d13ba5060e7537f5b44d698bb272520ec70c1da6100b`

## 裁决

**候选与冻结值一致；R1 修复覆盖了同一个 repository 实例内“已排队的旧写入晚于删除”以及“同 ID 新旧写任务逆序完成”两种异步竞态。** 测试新增了手动 executor 的可控检查。端口签名和 `PlanAdjustmentService` 依赖方向未变。

**仍发现一处可证实的边界竞态和一处错误恢复缺口。** generation 的预约发生在 L1 写入之后，因而对完整并发 `put/remove` 操作并非线性化；执行器拒绝任务时，计数已增加但没有回滚，且异常会向调用方传播。当前生成的 proposal ID 是随机值，正常 API 流程一般不会对同一 ID 并发 put/remove，所以第一项不等于已确认的用户流程故障；但 repository 本身没有保证此并发语义。拒绝任务在默认 common pool 下预期少见，但实现没有完整履行 Redis best-effort 降级契约。

**验收建议：带条件通过。** 若 CP1 对 repository 的并发契约要求只覆盖“put 返回后发生 remove”以及“同一 adapter 实例”，R1 核心目标达到；若要求并发同 ID 操作线性化或多实例 Redis 全序，需要补齐下列条件并发边界。真实 Redis、多实例与 executor 故障未实测，本次未运行 Maven。

## 冻结一致性

- 当前冻结 JSON SHA 与请求值一致。
- 当前 `git diff --binary HEAD` hash 与 `tracked_patch_sha256` 完全一致。
- 冻结清单列出的 untracked hashes 和 CP1 slice hashes 全部匹配，`git status --porcelain=v1 -uall` 与清单状态一致。
- 本报告是本次新增的唯一审查产物；代码及用户已有修改未改动。

## 逐项审查

### Generation/lock 与排队写入

实现位于 `ai/src/main/java/com/ai/guide/infrastructure/planner/proposal/CaffeineRedisProposalRepository.java`：

- `:35-40` 为 repository 实例内 `ConcurrentHashMap<String, RedisKeyState>`，每个 key 状态持有 generation 与 scheduledWrites。
- `:82-89` 通过 `compute` 和 `synchronized(state)` 给每次 Redis put 分配递增 generation 并记一个在途任务。
- `:91-99` 的异步任务获取同一 state 锁，只在 generation 仍为最新时执行 Redis set；同一 key 的旧任务因此被跳过。
- `:102-110` 的 finally 递减在途计数，并在归零后移除 state；正常完成和 Redis 写异常都会走 finally。
- `:151-165` 的 remove 在同 key 状态锁内推进 generation 并执行 Redis delete；若还有旧任务在途，保留状态以便旧任务观察到 generation 变化后跳过。

新增测试 `ProposalRepositoryPortContractTest.java:81-105` 在 put 返回后调用 remove，再运行排队任务；`:109-145` 逆序运行同 ID 的新旧 put。两者分别覆盖了上述机制的主要目标路径。此处检查的是测试源码，不是本轮实际执行结果。

#### 发现 R1 未完全序列化的并发调用

`put` 在 `:73-74` 先写 L1，之后才在 `:82-89` 预留 generation；`remove` 在 `:149-152` 先失效 L1，之后才在 `:152-164` 推进 generation 与删除 Redis。

触发交错：线程 P 执行 `l1Cache.put(id, proposal)` 后被暂停；线程 R 执行 `remove(id)` 并完成 L1 invalidate、generation 增长与 Redis delete；线程 P 恢复后才预留更大的 generation 并提交 Redis set。最终 Redis 可重新出现该 proposal，而 L1 仍为空。也可以在两个并发 put 中出现 L1 可见顺序与 Redis generation 顺序不同。

这是由当前语句顺序可证实的 repository 并发窗口，但**标准 Planner API 中 proposalId 由 `PlanAdjustmentService` 生成随机 UUID，客户端只能在预览响应之后确认应用**，因此没有证据证明普通请求会对同一个 proposalId 并发调用 put 和 remove。接口本身没有声明单线程约束，测试也主动覆盖同 ID 多 put，所以建议把 put/remove 对 L1 与 generation 的修改纳入相同 per-key 临界区，或明确缩窄端口并发保证。级别：**P2，边界并发正确性；当前 API 可达性有限**。

### Executor 拒绝与状态清理

- `:82-89` 在调用 executor 之前已经递增 `scheduledWrites` 并存入 state。
- `:91` 的 `redisExecutor.execute(...)` 位于 try/catch/finally 之外。
- 如果 execute 同步抛 `RejectedExecutionException`（或其他 RuntimeException），异步 runnable 根本不会进入，因此 `:102-110` 的 finally 不会运行。结果是异常离开 `put`，L1 已写入，但 Redis best-effort 降级变成请求错误；map 中的 `scheduledWrites` 留在正数，state 永不清除。之后 remove 会一直保留该 state，虽然 generation 仍可阻止已拒绝的旧任务（因为没有旧任务），但状态泄漏；后续相同 key put 可继续累积计数。
- 默认构造器使用 `ForkJoinPool.commonPool()`（`:42-43`）。常规运行中拒绝并非预期热路径，但 JVM/执行器拒绝属于可发生的执行边界，不能因默认 executor 而视为已处理。

级别：**P2，条件性错误处理与有限 map 泄漏**。建议 catch executor submission failure、对 generation 状态做计数回滚/清理，并保留 L1 成功结果；要不要把 Redis 写失败上报指标另行定契约。

### 锁范围与多实例边界

- `remove` 在 `ConcurrentHashMap.compute` 映射函数内进行同步 Redis delete（`:152-164`）。这会在网络调用期间持有 map bucket 的更新锁和 per-key state 锁；hash 到同一 bucket 的其他 key 状态更新可能等待。高 Redis 延迟时，apply 的删除发生在 session 更新之后，可能拉长响应时间。级别：**P3 性能建议，未测量**。
- generation map 是 adapter 实例字段（`:35`）。多个 JVM 各有独立 generation/state，即便共享 Redis，无法用这套锁给跨实例异步 put/remove 建立全局顺序。这是可证实的作用域限制。proposal epoch 同样为本地状态，服务层会 fail closed，因此本次没有证据表明跨实例旧 cache 条目能直接绕过 planner 校验；若目标包含 Redis 跨实例恢复/一致性，应由共享版本或 Redis 原子条件写删承担。级别：**建议项，取决于部署目标**。

### 生命周期与 API / Bean 影响

- `redisKeyStates` 条目在正常 runnable 完成时于 `:102-110` 清除；remove 在没有 in-flight 写入时也返回 null 清理状态（`:152-164`）。因此常规成功/Redis 写异常路径没有发现永久 map 增长。提交被 executor 拒绝是上面列出的例外。
- 失败或卡住的 Redis I/O 会让任务和 state 保持在途直到调用返回；底层 Redis 超时配置不在本审查范围，不能断言其最长滞留时间。
- 普通 no-arg Spring 构造器仍可用（`:42-43`）；可选 setter 注入仍保留（`:61-68`）；组件仍为 `@Component`（`:22`）。新建的 Executor 构造器是 package-private（`:46-59`），供同包测试使用，未改变公开构造 API。静态检查只有这一处 production `ProposalRepositoryPort` 实现；真实 Spring context 本轮未启动。
- `ProposalRepositoryPort.java:9-14` 仍是原来的 `void put`、nullable `get`、返回本地旧项的 `remove`。`PlanAdjustmentService.java:76,112-164,265-270,369,489,727,736,788` 仍以端口注入和调用，没有新增对 infrastructure 的 domain 依赖。
- 生命周期 TTL 未改：L1 使用 `PlanAdjustmentProposal.DEFAULT_TTL_MS`；绝对 expiresAt 检查仍在 adapter get 路径中。Redis best-effort 错误处理原意仍保留，但 executor submission rejection 不在错误捕获内。

## 已证实项与建议项汇总

| 级别 | 分类 | 结论 | 证据/触发条件 |
|---|---|---|---|
| 通过 | 已证实 | 同 adapter 内，put 已排队后再 remove，remove 推进 generation；旧 runnable 会被跳过。 | repository `:82-110,151-165`；定向测试源码 `:81-105` |
| 通过 | 已证实 | 同 adapter 内，两个相同 ID 的 put 逆序执行时，旧 generation 不覆盖新快照。 | repository `:82-99`；定向测试源码 `:109-145` |
| P2 | 已证实的边界竞态 | 并发 put/remove 不是完整线性化操作，因 L1 操作早于 generation reservation。 | `:73-89` 对照 `:149-164`；普通 API 可达性有限 |
| P2 | 已证实的条件缺陷 | executor.execute 拒绝时异常外逸，状态计数无 finally 回滚。 | `:82-91` 与 runnable finally `:102-110`；拒绝时任务不运行 |
| P3 | 建议 | Redis delete 位于 CHM compute 中，可能因网络延迟阻塞同 bucket 的其他 key 更新。 | `:152-164`；需压测确认影响 |
| 建议 | 部署边界 | Generation 仅在单 adapter JVM 内有序，不能协调多节点 Redis 写入。 | `:35` 实例字段；每个 Spring app context 各自实例 |

## 验证限制

按任务要求未运行 Maven、未启动 Spring、未连接真实 Redis，也未并发压测。对新增测试仅核对源码中的受控 executor 场景。该报告不宣称全项目无其他问题，也不确认 R1 新增测试实际通过。

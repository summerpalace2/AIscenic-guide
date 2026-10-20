# CP1-R1 红方独立审查：提案缓存并发代次

审查日期：2026-10-09  
审查对象：R1 新冻结候选 JSON SHA-256 `04d7c02ff4478d5412b985c59202b11c9504d1f1ee867112fc83f8522330d641`。复核冻结文件 SHA 相同；当前 tracked diff SHA-256 `dedb565477e4fc688a80d13ba5060e7537f5b44d698bb272520ec70c1da6100b`，与候选记录一致；R1 adapter、contract test、其余 CP1 切片文件的冻结 hash/status 均匹配。本文为独立审查记录，不属于被审源码候选。

依据：`COLA_REFACTOR_EXECUTION_PLAN.md` §3.2、§4，CP1/R1 实施报告。方法：先从 `put/get/remove` 端口契约构造竞态，再读代次实现与测试。**只读源码和既有隔离日志；未运行 Maven/测试、未访问真实 Redis、未修改源码。**

## 行为契约与反例顺序

| 场景 | 预期 | 交错/审查结果 | 判定 |
|---|---|---|---|
| put 已提交 L2 任务队列，然后 remove | remove 返回后 `get(id)` 不可恢复该对象 | R1 新测试 `removeInvalidatesQueuedRedisPutSoItCannotResurrectTheKey` 用 ManualExecutor：put 排队、remove、再执行队列任务，验证无 Redis set、get null。代码代次已增加，任务检查后跳过。 | **PASS（该顺序）** |
| put 已进入 Redis set 临界区，然后 remove | remove 必须等已进入的 set 结束，再删除 Redis/L1 | 执行任务在 `synchronized(state)` 内完成序列化与 Redis set；remove 也在同一 state monitor 下增代次并 delete，故若 set 已持锁，remove 会等待后再删。源码顺序成立；当前 ManualExecutor 测试没有阻塞 set 并发 remove 的测试。 | **PARTIAL** |
| 同 ID 的较旧 put 任务晚于较新任务执行 | 较旧快照不能覆盖较新值 | 新测试 `olderQueuedSameIdPutCannotOverwriteNewerProposalSnapshot` 顺序调用 old/new put，然后手动先跑 newer 再跑 older，清 L1 后从 L2 读回 newer。此队列顺序被 generation 防住。 | **PASS（顺序调用）** |
| 两个同 ID put 调用重叠，先写 L1 的 old 后到 generation 登记；new 先登记，old 后登记 | L1 与 L2 应按同一条操作顺序收敛 | `put` 先在第74行 `l1Cache.put`，之后才在第82–90行登记代次/排任务；这两段不在同一 per-key 临界区。可达顺序：T1 old 写 L1 后暂停；T2 new 写 L1、取得 generation=1 并排队；T1 恢复取得 generation=2 并排队；先执行 old/gen2，new/gen1 被跳过。此时 L1 是 new，L2 是 old；清 L1/重启后读回 old。现有“newer 先执行、older 后执行”测试没有覆盖 generation 登记顺序被调用交错反转。 | **FAIL** |
| put 完成 L1/代次登记但 executor 拒绝接受任务 | Redis 可选降级不能破坏 L1 成功语义；状态最终释放 | `redisExecutor.execute(...)` 在 try/catch 外；RejectedExecutionException 会从 `put` 向调用方传播。`scheduledWrites++` 已发生，却没有 Runnable/finally 来减回，`redisKeyStates` 中该 ID 状态永久保留；后续 remove 看见计数仍大于0而不会删状态，重复拒绝会持续泄漏。L1 已写入但调用方收到失败。没有 rejecting-executor 测试。 | **FAIL** |
| Redis `set`、`get`、`delete` 抛异常 | 按 Redis best-effort 语义降级；无异常误报，且本地状态/计数释放 | Runnable 的 `set` 异常落在 lambda catch，finally 会减计数；remove 对 delete 异常 catch 并继续；get 对 L2 读取异常 catch。可是新旧测试中的 `redisUnavailableKeepsLocalL1BehaviorAndDoesNotThrow` 只 mock `opsForValue()` 抛错，并让 get 命中 L1；未覆盖 executor 内 set 异常后计数清理、L1 miss 的 Redis get 异常和 delete 后读回陈旧值。 | **PARTIAL** |
| 正常已接受任务结束时 per-key 状态清理 | 无未完成任务时删除状态；仍有任务则保留直到全部收敛 | Runnable finally 在 `scheduledWrites` 到0时返回 null；remove 在0时也返回 null。对正常接受的任务，map 计算与 state monitor 的锁顺序一致，源码路径能回收。拒绝 executor 的 phantom count 令其永远不能回收，见上项。未发现反向锁顺序导致的确定死锁。 | **PARTIAL / FAIL（拒绝路径）** |
| L2 get 已取到旧 JSON、remove 后 get 才回填 L1 | remove 已完成后，旧读取不得重新填充 L1 或返回已删除对象 | get 只在读前查 L1；随后 Redis `get` 在第129行执行，反序列化后第133行直接 `l1Cache.put`，未检查 generation。可达顺序：T1 get 读 Redis 旧值后暂停；T2 remove 清 L1、增 generation、Redis delete 并返回；T1 恢复将旧值回填 L1 并返回。此后即使 Redis 键不存在，L1 仍能持续返回已删除提案，直至 L1 TTL。generation 机制没有覆盖 L2 read/repopulate。 | **FAIL** |

## 证据细节

### 1. generation/lock 的保护范围

`CaffeineRedisProposalRepository.java`：

- 第 34–39 行维护 `redisExecutor`、`redisKeyStates` 与 `generation/scheduledWrites`。
- 第 72–90 行先写 L1，再增加 generation 和排队；这是同 ID put 次序分裂的窗口。
- 第 91–110 行异步任务在 state monitor 内校验 generation 并 set；finally 将 scheduledWrites 减回并尝试移除状态。
- 第 148–164 行 remove 先 invalidate L1，再在 map compute/state monitor 内增 generation、delete，并根据 scheduledWrites 清理状态。
- 第 115–138 行 get 从 Redis 回补 L1 时不读取/校验 state generation，因此回填可越过 remove。

**结论**：这个代次解决了一个特定已排队写删交错，但没有形成覆盖 L1 修改、L2 读回补、代次登记的统一线性化边界。

### 2. 失败 executor 的确定性状态泄漏

当 `redisExecutor.execute` 拒绝任务时，失败点位于 Runnable 之外的调用处；scheduledWrites 已递增，清理逻辑只存在于 Runnable 的 finally 中。没有其他路径回收这个计数。后果至少包括该 ID state 永久留在 ConcurrentHashMap、remove 无法将其清走、put 调用向上抛错且已留下 L1 值。当前 ManualExecutor 只接受任务，未模拟拒绝。

### 3. Read/remove 竞态的应用影响边界

删除 L1 后，旧 get 可将对象再放回 L1；所以 `remove()` 返回后仓储端口本身会读出旧对象，属于可证明的契约失败。业务层的 apply 仍另行校验 proposal epoch、session 和 revision，可能拦住已取消/已应用提案；这不能让 `ProposalRepositoryPort.remove/get` 达到线性化，也不能替代缓存层修复。当前用例没有验证此 race 最终 DB update 为零。

## R1 测试与既有日志

静态阅读 `ProposalRepositoryPortContractTest`：新增两项 ManualExecutor 测试覆盖 queued put/remove 与顺序 old/new put 任务逆序执行。ManualExecutor 可以精确调度任务，但 Redis 仍是 Mockito facade，测试不包括：

- 调用开始后、L1 put 与 generation 登记之间的并发屏障；
- 任务已进入 `set` 后并发 remove 的阻塞/删除顺序；
- 同 ID 并发 put 的 L1/代次写入顺序；
- executor rejection 和 scheduledWrites/map 状态回收；
- Redis get 已返回但回填尚未发生时执行 remove；
- L2 live server、网络超时/重连及多实例一致性。

只读检查原始日志 `outputs/20261009-CP1-R1-maven-clean-tests.log`：隔离临时副本的记录为 **85 tests, 0 failures/errors/skipped，BUILD SUCCESS**；其中 `ProposalRepositoryPortContractTest` 5 项通过，架构守卫 8 项通过。日志有 localhost:6334 的 Qdrant connection refused 后降级信息；未见真实 Redis 连接记录。该日志证明的是当时隔离副本测试结果，不证明上述未覆盖并发顺序，也没有在本次审查中重跑。

## 最终裁定

**FAIL — CP1-R1 暂不验收。** 排队后 remove 的原 R1 反例已针对性修复，但仍有两个可达并发错误：

1. 同 ID put 重叠时，L1 写入与 generation 登记不原子，导致 L1=new、L2=old；
2. L2 get 的迟到回填可越过已完成的 remove，使 `get()` 重新返回删除对象。

另外 executor 拒绝任务会让 `put` 抛出并永久泄漏 scheduledWrites 状态。推荐修复时将同 key 的 L1 写、generation 变更、L2 read 回填和 remove 纳入一致的 per-key 同步/版本校验协议；executor rejection 必须回滚预登记计数或按 L1 降级成功处理。新候选至少用 latch/barrier 或 controllable hooks 覆盖这三个确定反例，另测“set 已进入后 remove”等待及异常后状态归零；随后重冻并重新验收。

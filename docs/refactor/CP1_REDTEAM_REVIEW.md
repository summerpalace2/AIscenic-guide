# CP1 红方独立验收：ProposalRepositoryPort

审查日期：2026-10-09  
依据：`COLA_REFACTOR_EXECUTION_PLAN.md` §3.2、§4，`CP1_IMPLEMENTATION_REPORT.md`  
审查对象：`outputs/20261009-CP1候选冻结.json`；冻结文件 SHA-256：`448361d3f3c229a068db33cac26bd8eddd35193c7d83725d82a0f7461e54caa8`。复核时冻结文件 SHA 相同；按 Python `git diff --binary HEAD` 重算的 tracked patch SHA 为 `f604305ca4e6dcdfe2569a267f8539505da7b9844e4ace6210c8b840b89f3584`，与冻结记录相同；8 个 CP1 切片文件的存在/删除状态及 SHA-256 均与冻结记录一致。本文是验收报告，不属于被审候选源文件。

**审查方式：只读源码与原始日志。没有运行 Maven/测试，没有访问真实 Redis，也没有改动源码。**

## 独立预期与反例

先按 CP1 端口契约自行构造以下反例，再对照实现和测试：

1. `put(P)` 尚未完成 L2 写入时调用 `remove(P.id)`；随后让异步写完成，再 `get(P.id)`。移除后应始终不可读，且过期/应用/取消的方案不能被缓存回补成有效提案。
2. `expiresAt` 早于 L1/L2 缓存 TTL；分别从 L1 命中和 L2 恢复时，达到绝对过期时间后均应返回不可用。已经过期的对象不应被写入 L2。
3. L1 未命中且 Redis 的 `opsForValue()`、`get()`、`set()`、`delete()` 分别抛异常；调用方应得到定义好的降级结果，异常不泄漏，应用仍须 fail-closed。
4. L2 JSON 缺少 `conversationEpoch`、`sessionId`、`baseRevision` 或 `expiresAt`，以及 JSON/key 内提案 ID 不一致时，恢复对象必须被拒绝或在应用边界明确拒绝，数据库 revision 与 update 调用数不变。
5. 重建 repository/service 后，只恢复 Redis 提案而未恢复进程内 epoch 时应拒绝旧提案；取消/重置后旧 epoch 必须失效，不能用公共初始值复活。

## 裁定

| 检查项 | 判定 | 证据和限制 |
|---|---|---|
| 候选身份 | **PASS** | 冻结 JSON SHA、tracked patch SHA、全部 CP1 切片文件 hash/状态匹配。可对本次冻结候选出具审查，不要求停止。 |
| 端口方向与装配替换 | **PASS（源码范围）** | `ProposalRepositoryPort` 声明 put/get/remove；`PlanAdjustmentService` 使用端口；`CaffeineRedisProposalRepository` 在 infrastructure 实现；原 `ProposalStore` 已删除。源码可见 `@Component` 实现由 Spring 装配。冻结候选测试包含架构守卫。本次未运行 app context，部署环境装配仍以日志和代码配置为限。 |
| put/get/remove 基本语义 | **FAIL** | L1 基本路径有断言，但异步 L2 写与 remove 存在可达的竞态，详见下文 R1。测试没有并发控制覆盖它。 |
| `expiresAt` 绝对时限 | **PARTIAL** | `putGetRemoveAndAbsoluteExpiryFollowPortContract` 测试只用无 Redis 的实例，验证过期对象 L1 写后 get 返回 null；新实例用序列化数据并断言 expiresAt 原值。没有检验 Redis TTL、调度延迟、L2 过期回补或过期对象没有触发 Redis set。实现恢复 L2 时会重新检查 `isExpired()`，因此超时提案应用仍 fail-closed。 |
| 过期提案写入 | **PARTIAL** | 生产 `put` 在 `remainingMs <= 0` 后停止 Redis 写入；契约测试只断言 expired L1 get 为 null，没有 `verify(redis, never()).opsForValue().set(...)`。当前逻辑有门禁，缺少对该边界的直接断言。 |
| Redis 不可用异常降级 | **PARTIAL** | `redisUnavailableKeepsLocalL1BehaviorAndDoesNotThrow` 令 mock `opsForValue()`、`delete()` 抛异常，验证本地 L1 和 remove 不抛异常；但 `get` 命中 L1，没有触发 L2 `get()` 失败路径。没有真实 Redis。生产代码对读/写和删除均有 catch，故这项为覆盖缺口，不据此认定已观察到异常泄漏。 |
| L1/L2 TTL 一致性 | **BLOCKED** | L1 固定 `DEFAULT_TTL_MS`；L2 由剩余毫秒换算秒并 `Math.max(60, ...)`，异步任务执行后才开始 Redis TTL。每次命中仍用 proposal 内 `expiresAt` 拦截过期应用，但存储物理 TTL 并非绝对一致。没有 Redis TTL 断言/真实服务。CP1 可按安全语义 fail-closed，但不能声明 L1/L2 实际 TTL 已验证。 |
| 异步 put/remove 竞态 | **FAIL** | `put` 行 60–66 使用无序 `CompletableFuture.runAsync` 发 L2 set；`remove` 行 105–114 立即清 L1 并同步发 Redis delete。若 remove/delete 先于该异步 set 完成，之后 Redis 中会重新出现该 key；L1 miss 的 get 随即能从 Redis 回补该对象。无每 key 顺序屏障、generation/tombstone 或写入后核对。虽然后续应用仍检查 epoch，存储端口的 remove 语义已被破坏，且会造成过期记录/重复缓存命中。CP1 计划 §3.2 明确要求发现异步写删竞态时单独复现并修正。 |
| 序列化缺字段 | **BLOCKED** | 现有恢复测试只序列化完整 proposal 并验证 expiresAt；没有对缺字段 JSON 构造反例。源码可见 `conversationEpoch` 缺失会在 `validProposal` 失败关闭，缺 `expiresAt` 会成为 0 并被判断过期，缺 `sessionId`/`baseRevision` 通常会在应用边界失配；但没有专门断言，也没有验证 JSON/key proposalId 不一致。不能把完整 roundtrip 等同兼容坏/旧 payload。 |
| reset/epoch fail-closed | **PASS（单进程语义）** | `ConversationContinuationRegressionTest.resetAndNewRoundPreventOldPublication`、`epochAllowsNewProposalAtExactlyResetMillisecond`、`firstEpochIsRandomAndLegacyOrMissingAuthorityCannotValidateAProposal` 等覆盖 reset/旧 epoch 失效；新 `ProposalRepositoryPortContractTest.restoredOldProposalFailsClosedAfterServiceReconstructionWithoutSqlWrite` 重新构造 repository/service，从 mock Redis 读回 proposal，断言 409、revisionCount 不增加、repository update never。它验证的是单 JVM 中的服务重建 + mocked Redis，不是 Redis 进程重启或跨节点恢复。 |
| 最终 DB 副作用 | **PASS（该 fail-closed 用例）** | 恢复用例用 `@TempDir` 下的 SQLite 和真实 `PlannerSessionRepository`，并 spy repository；既断言 revisionCount=1，也 `verify(update, never())`。它不证明 Redis 真实行为、所有 malformed payload 或生产数据库。 |

## 已确认缺陷 R1：L2 异步写可越过 remove 复活 key

定位：`ai/src/main/java/com/ai/guide/infrastructure/planner/proposal/CaffeineRedisProposalRepository.java` 的 `put`（51–66）与 `remove`（105–114）。

可确定的交错顺序：

```text
T1 put(P): L1 写入；提交异步任务，任务暂停在 Redis set 之前
T2 remove(P.id): 清 L1；Redis delete 返回
T1 异步任务恢复：Redis set(P) 成功
T3 get(P.id): L1 miss；从 Redis 读回 P 并回填 L1
```

结果是 `remove` 返回后同一仓储又能 `get` 到对象，端口契约不再具备稳定删除语义。现有应用层 epoch 会挡住被 apply/reset 后的旧提案实际提交；这降低了直接业务风险，但不能修复缓存回生，也没有证明所有 remove 调用都已同步更新 epoch。最小验收要求是增加可控 executor/latch 测试精确制造上述顺序，观察 Redis 键及 `get()`，再以每 key 序列化写删、版本/墓碑校验或等价机制修复，并复验 DB 不发生意外更新。

同一实现还允许同 ID 多次 put 的异步任务乱序写入：旧 proposal 的 L2 snapshot 可能晚于新版本写入。proposal ID 当前用 UUID 生成，正常调用少见复用；作为通用 port 仍建议测试/防护，不能将其与 R1 的普通 put/remove 竞态混为一项已复现缺陷。

## 隔离 Maven 原始日志核对

读取：`outputs/20261009-CP1-maven-clean-tests.log`。原始日志记录 Maven 在临时副本 `C:/Users/summer/AppData/Local/Temp/scenic-guide-cp1-801c66526ce34ba7a93dfec0d3ee63c8` 执行 `clean test`；重新编译 244 个 production source、154 个 test source。结果为 83 tests，0 failures、0 errors、0 skipped，`BUILD SUCCESS`；目标 `ProposalRepositoryPortContractTest` 3 项、`ColaArchitectureGuardTest` 8 项均显示 0 failure。

日志中 Qdrant 启动检查曾连接本机 `localhost:6334` 并收到 connection refused，随后 Spring 记录降级警告；整组测试最后仍成功。日志没有真实 Redis 连接证据。合同测试里的 Redis 是 Mockito mock；SQLite 临时库是真实本地测试库。这里仅核验日志内容，没有重新执行命令。

## CP1 最终状态

**FAIL — CP1 暂不验收。** 代码确实完成了 domain→port→infrastructure 的依赖迁移，单实例的旧 epoch fail-closed 也有较强测试与 SQLite 副作用断言；但 R1 是静态可达的 put/remove 竞态，违反 port 生命周期语义和 CP1 §3.2 的竞态处置要求。修复并提供可控时序回归测试后，应对新冻结候选重审。真实 Redis/L2 TTL 与异常恢复仍属 **BLOCKED/未验证**，不应作为已通过声明。

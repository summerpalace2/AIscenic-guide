# CP1 验收报告：提案存储端口

日期：2026-10-09  
裁决：**通过，限单实例语义**

## 结果

规划领域现在依赖 `ProposalRepositoryPort`，不再直接持有 Caffeine、Redis 或序列化实现。原 `ProposalStore` 已迁移至 `infrastructure.planner.proposal` 下的 `CaffeineRedisProposalRepository`；提案 15 分钟默认 TTL 由领域模型定义一次。CP1 不改变预览/确认业务编排，不增加数据库状态表。

最终版本的同实例提案生命周期已覆盖：L1/L2 的 put/get/remove 顺序、绝对过期、Redis best-effort 降级、取消后迟到写入与读取、同 ID 写入乱序、执行器拒绝、删除失败后的本地屏障、旧 epoch 重建后拒绝应用，以及 Redis 快照字段/身份校验。精确到期时刻采用排他边界：`now >= expiresAt` 即过期；过期快照不会被返回或应用。

## 独立审查与验证

- B 的最终架构审查：[CP1_R4_ARCHITECTURE_REVIEW.md](D:/scenic-guide/docs/refactor/CP1_R4_ARCHITECTURE_REVIEW.md)。确认领域依赖方向、端口实现及单实例锁/状态范围；指出同步 Redis I/O 在 256 个锁分片内，以及锁和 tombstone 都不提供跨节点一致性。
- C 的最终红队审查：[CP1_R4_REDTEAM_REVIEW.md](D:/scenic-guide/docs/refactor/CP1_R4_REDTEAM_REVIEW.md)。确认前几轮发现的同实例竞态已关闭；删除失败场景跨 repository/service 对象恢复旧 proposal 后，新的 epoch 拒绝应用且没有 SQL 更新。
- 主智能体复跑 CP1 定向 Java 测试：`ProposalRepositoryPortContractTest`、`ProposalStoreAndChatContextTest`、`PlanAdjustmentServiceTest`、`CancellationProposalLifecycleTest`、`ColaArchitectureGuardTest`。结果 **92 tests, 0 failures, 0 errors, 0 skipped，BUILD SUCCESS**。原始主审运行结果对应本机 `ai/target/surefire-reports/`；实施方另在隔离副本执行了 `clean test`，记录于 `outputs/20261009-CP1-R4-maven-clean-tests.log`。

最终候选身份：

- 冻结清单：[20261009-CP1-R4候选冻结.json](C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP1-R4候选冻结.json)，SHA-256 `fadf0bb5c03939b02ca5dc458a9addb10bddead4d7d2a55d7c0289487494a30b`
- Git HEAD：`46b1653e81050e3cdd9f23506b327bc6676bcbd8`
- 冻结时 tracked patch SHA-256：`f8b3fcac031b73a2f3442b21db362118f3d79992e7ff97b030442d97c65acc86`；主审重跑后再次核验匹配。
- 本次范围的实现与测试细节：[CP1_IMPLEMENTATION_REPORT.md](D:/scenic-guide/docs/refactor/CP1_IMPLEMENTATION_REPORT.md)

## 验收边界

Redis 交互由 Mockito 模拟；临时 SQLite 用于断言失败时 revision 不变且 repository update 未发生。没有连接真实 Redis，没有实测 Redis TTL/网络断连，也没有运行多进程、多节点或生产 Spring 部署验证。删除失败时的 tombstone 是本机 Caffeine 负缓存，容量最多 10,000 项并在 15 分钟后过期；它不能让 Redis 物理删除成功，也不能跨 JVM 同步。旧 proposal 在重建 service 后依靠新 epoch fail-closed；这不是跨节点缓存顺序协议。

Redis 读取失败仍表现为缓存未命中，服务端可能要求用户重新生成预览；同步 Redis I/O 位于固定 256 个锁分片内，慢请求可能阻塞同分片的其他 proposal。以上是明确记录的限制，不作为真实 Redis 或多节点能力已通过的证据。语法损坏 JSON 由反序列化异常路径 fail-closed，但没有独立的乱码 JSON 回归样例。

## 回切和下一步

回切只撤销本 CP1 slice：恢复原 `ProposalStore`、改回 `PlanAdjustmentService` 的端口类型/TTL 引用，并移除 adapter、端口契约测试、架构守卫增量及对应测试迁移。不得对共享工作区使用全仓 reset/clean；CP0 和其他预存用户改动不属于此回切。

下一检查点是 CP2：把预览/澄清编排收口到 application use case，保持预览不改正式行程、追问继承原目标与旧 wire 字段兼容。CP1 通过不代表 CP2、CP3 或整个后端重构完成。

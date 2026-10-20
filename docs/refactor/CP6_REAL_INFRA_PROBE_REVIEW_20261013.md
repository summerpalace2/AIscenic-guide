# 真实基础设施探针复核与补强

## 原有覆盖裁决

`Cp6RealInfrastructureIntegrationTest` 没有使用 SQLite、Spring 测试容器或模拟 PG/Redis。原四项分别调用真实迁移器、两个 Java JVM 并行初始化、两个 Redis 仓储对象的 L1/删除一致性、以及子 JVM 提交后被 kill 再读取。迁移设置了同连接事务与 PG advisory lock；Redis 测试使用真实生产 CaffeineRedisProposalRepository，而非手工模拟缓存。

但原双 JVM 仅覆盖 DDL；原崩溃测试 PG 侧只是 crash_probe 标记，不能证明正式行程、应用事务或 HTTP/BFF 多实例恢复。原 Redis 双对象测试与队列延迟模拟也不是两个 HTTP 实例。工具已有相应限制声明，不能据此扩大结论。

## 写入边界

Target 要求 CP6_IT_ENABLE 外层 opt-in 与独立 CP6_IT_PERMISSION 授权，拒绝非 PG URL、数据库名不一致、URL 内嵌凭据/查询选项。每项创建随机 cp6_<uuid> schema，Redis 固定隔离 DB15 加随机 proposal key。cleanup 仅移除该 key、DROP 当前随机 schema，未发现 FLUSHDB、真实 .env 回退或读取个人 SQLite。连接目标是否真是一次性环境仍依赖使用者提供的专门测试环境，不能靠主机名证明。

## 确定探针缺陷与修复（仅测试文件）

- 管理连接 CREATE/DROP schema 原先直接使用裸 JDBC URL，只有 schema 业务连接设置 connectTimeout/socketTimeout。增加同样有界 adminDataSource，并加离线 URL/schema 边界断言。
- 子 JVM 原先继承全部环境，包括潜在 JAVA_TOOL_OPTIONS。现仅继承必要 OS 路径和 CP6_IT_*；工作目录仍是独立临时目录，不加载个人配置。
- 崩溃恢复原先仅断言 Redis proposal 非空。现将受控完整 proposal JSON 记录在 PG marker 中，重启后比较完整序列化结构，而非只比较对象存在。

## 新增真实正式行程并发探针

两项新增测试调用真实 TripService、TripRepository 和 TransactionTemplate，不启动 Spring 或调用供应商。子 JVM 只在第一次真实仓储读取之后加测试屏障：两个实例都读完同一原始状态才放行，避免所谓并发实际退化为先完成后 replay。

1. 同版本两份互斥站点修改：要求一个200、一个409；主表最终版本2，历史严格[1,2]，最终快照等于获胜回执，另一 owner 不可读。
2. 同幂等键首次正式保存：要求两个成功回执指向同一行程，数据库仅一份 trip/version/idempotency，幂等预留已完成且反序列化结果等于真实快照，不允许 version=0 孤儿预留。

这些是跨 JVM 领域服务/仓储事务测试，仍不是 HTTP 两实例、BFF 会话续接或基础设施自身断电持久化。Compose tmpfs 与关闭 Redis AOF 也不适用于基础设施重启恢复验收。

## 待真实 PG 验证的生产风险

TripRepository.reserveIdempotency 使用普通 INSERT；TripService.reserveIdempotency 捕获 DuplicateKeyException 后在同一事务内执行 replay 查询。当前同键屏障探针将强制走唯一键碰撞。PG 的事务错误状态可能使后续查询失败，与 SQLite 行为不同；目前只有源码机制风险，没有真实 PG 结果，不先声称漏洞已实证或已修。生产代码未改变。

## 当前证据

`cp6-infra-probe-offline-regression.log`：2026-10-13 11:03:25 BUILD SUCCESS，**10 run / 4 passed / 6 skipped / 0 failures / 0 errors**。全部测试源码已编译；实际运行的只有四项目标参数校验，六项真实 PG/Redis 探针明确跳过。未读取真实 .env、未连接或启动服务、未对共享数据写入。没有把跳过计为通过，也没有声称本板块已经验收。

root 负责更新 run-integration.ps1 与 tools 文档，取消固定四项数量假设、保留全套未运行拒绝验收语义。待获得隔离 PG/Redis 后，应先运行这六项，保留失败 SQLState 与子 JVM 日志，再裁定生产修复及后续 HTTP 级矩阵。

## 真实 PG 复现与最小生产修复

主线程随后提供并运行专用隔离环境，修前 `infra-real-before.log` 实际 **10 run / 9 passed / 1 failed / 0 skipped**。首次同键保存的子进程日志明确 SQLState **25P02**，TripService.reserveIdempotency 捕获唯一冲突后执行 replay 查询时报告 transaction aborted。首轮 JUnit 保留于 `CP6_REAL_INFRA_BEFORE_20261013.xml`，因此前述风险现已转为真实复现，不再只是源码推断。

经主线程批准，仅修改 TripRepository.reserveIdempotency：INSERT 添加精确冲突目标 `ON CONFLICT (owner_id, operation, idempotency_key) DO NOTHING`；返回0时由 Java 主动抛 DuplicateKeyException，保留领域层已有回放契约。数据库没有执行失败的唯一键 SQL，PG 当前事务可继续读取已提交的赢家。其他约束的 SQL 错误继续传播，不用笼统吞异常或事务重试掩盖失败。

补充 TripIdempotencyReservationTest 三项真实临时 SQLite 回归，验证冲突后同事务读取、原预留不被覆盖、作用域及其他约束错误。实施者自查 diff --check 退出0；按协调约定不自行运行 Maven/真实服务，修后真实测试、打包与最终计数由主线程串行执行，本报告不提前声称修后通过。

## 主线程修后实际验收结果与冻结

以下验证由根线程实际执行，本实施者只读取其日志及机器摘要核验，没有独立再次执行，也没有在验收期间并发占用 Maven 或共享服务：

- `infra-real-after.log`：11:12:34 BUILD SUCCESS，**10 run / 10 passed / 0 skipped / 0 failures / 0 errors**，包含4项目标校验与6项实际 PG/Redis 探针。之前唯一冲突导致的25P02失败已在同一强制屏障反例中修后通过。
- `infra-trip-regression-final.log`：11:13:43 BUILD SUCCESS，**54 run / 54 passed / 0 skipped / 0 failures / 0 errors**，包含新增3项 SQLite 占位兼容测试及原正式行程回归。
- `infra-package-final.log`：11:14:00 BUILD SUCCESS，jar SHA256 **4C1156FF566483DA997A789D619100601C46FEBBA8465B55AE541BDD61D81205**。
- `CP6_REAL_INFRA_HTTP_20261013.json`：使用上述同包启动两个实际 HTTP 后端，4项通过：共享用户授权、同键创建单行程/版本、同版本互斥修改一胜一冲突、peer kill后存活实例读取已提交行程。该项不包含 BFF 或真实供应商。
- `CP6_REAL_INFRA_SERVICE_CRASH_20261013.json`：专用 PostgreSQL 16.15 在 fsync/synchronous_commit/full_page_writes 开启下强制kill并从同目录恢复已提交标记；专用 Redis 7.4.3 community Windows/Cygwin 版本启用 AOF、appendfsync=always，并确认 WAITAOF 后强制kill和同目录恢复标记。测试标记与临时schema已清理，Redis原fsync设置已恢复。

这是 Windows 专用隔离环境的实测结果，不能替代生产 Linux 环境、多节点网络分区、BFF端到端或任意事务中点崩溃验收。初始“仅计划未执行”的段落保留为历史记录，最终结论以本节为准。根线程已停止专用服务；本报告与本轮源代码冻结，不再修改其他路径。

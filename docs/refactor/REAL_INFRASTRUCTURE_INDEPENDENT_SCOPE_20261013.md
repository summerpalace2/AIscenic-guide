# 真实基础设施验收：独立范围审查

2026-10-13。仅阅读现有 tools/cp6-infra 与 Cp6InfrastructureTarget、Cp6RealInfrastructureIntegrationTest、Cp6InfrastructureWorker、实际迁移器及 Redis proposal 仓储。没有读取真实 `.env` 值、连接共享服务、启动或安装基础设施、运行 Maven，也未修改实施者文件。

## 本机事实

Get-Command 对 docker、psql、postgres、initdb、pg_ctl、redis-server、redis-cli 没有匹配；服务名检索 PostgreSQL/Redis/Docker 也没有匹配。只能说明当前 PATH 与服务注册不可用，不能推断所有磁盘上都没有便携二进制。

## 现有探针判断

| 探针 | 实际执行 | 尚未覆盖 |
| --- | --- | --- |
| PostgreSQL复跑迁移 | 真实 KnowledgeSchemaInitializer、随机schema、迁移账本对比 | 应用完整启动、用户/正式行程业务事务 |
| 并发迁移 | 两个独立JVM在start屏障后初始化同schema；实际事务级advisory lock | 两个HTTP应用实例的业务竞争 |
| Redis缓存一致性 | 两个真实仓储对象、第二个L1预热、生产Lua删除墓碑与迟到发布反例 | 两进程HTTP预览/应用、网络分区 |
| worker崩溃 | PostgreSQL自建crash_probe已提交标记、生产Redis proposal写入，杀Java客户端后新JVM读取 | Trip/Planner业务提交恢复、未提交事务中断、PG/Redis服务进程丢失 |

没有把自建 crash_probe 当成生产正式行程事务；没有把两个仓储对象当成两个完整应用实例。文档现已明确这些边界，因此它们是验收缺口，并非现阶段已实证的生产缺陷。

## 隔离与运行守卫

显式 CP6_IT_ENABLE 与 DISPOSABLE_CP6_TEST_ONLY 授权、单独测试连接字段、单独数据库名交叉匹配。PG拒绝URI内凭证/query/fragment，schema必须是随机cp6标识；Redis固定DB15且只写随机proposal key，不扫描或清库。清理只删除本次schema/key。允许明确授权的远程专用测试目标，不依赖城市、个人路径或主机名单来伪装安全。

子JVM不启动Spring、不调用应用.env加载器、不发现旧SQLite，临时cwd隔离；凭证只经环境传入而非命令参数。现有worker正确使用生产迁移器与proposal仓储，但这不自动扩展成完整生产业务链。

新增显式 test-config.ps1 只接受CP6_IT白名单，无值展开/脚本执行，重复/未知/非法授权及TLS值拒绝，不回退业务配置。run-integration只在实际四项未skip且零failure/error时接受；skip返回2，不把BUILD SUCCESS称为验收通过。

## 后续最小验收矩阵

1. 先取得明确隔离PG/Redis端点或已授权启动一次性本地服务；实际连通及4项现有探针零skip执行。不要为通过而复用业务.env或关闭守卫。
2. 两个真实Java HTTP实例指向同专用库与Redis：正式保存CAS竞争、同幂等身份并发与冲突身份、proposal跨实例读取与只应用一次、取消后迟到发布、正式open回执丢失后的幂等/补偿。
3. 已确认正式保存/应用提交后强杀一个应用JVM，由另一实例或新实例恢复并继续变更，验证权威版本与站点；不能只读取自建标记。
4. 若要求PG/Redis服务本身崩溃恢复，另外提供持久化专用profile；当前PG tmpfs与Redis禁AOF/RDB的Compose不能承担该验收。服务杀进程与客户端杀进程分别统计。

在真正执行前没有新的实证生产缺陷可报告；不能据静态审查宣称真实基础设施已经通过。后续实施冻结后仍需独立验证实际日志与目标隔离。经验与统一交付记录由主智能体维护。

## 新真实HTTP探针的独立复核

`verify-http-instances.py` 启动两个实际 Java HTTP 应用，使用随机独立PG schema、Redis15和共享测试用户，验证同key创建只产生一个行程/版本、同版本编辑一赢一冲突，强杀一个应用后另一个仍能读取已提交正式行程。不是BFF或供应商验收，也不是PG/Redis服务进程恢复。

独审发现并反馈三处验收工具缺陷，主智能体最小修正后已复读：

- 原 Python assert 兼作隔离守卫与oracle，可被 `-O` 关闭。现入口显式拒绝 `__debug__=false`；独立实际运行 `python -O` 已在连接或启动服务前拒绝。
- 原Python重复宽松解析测试文件，与严格PSparser不一致。现Python不读取测试文件，统一由runner调用PSparser后临时传入CP6环境。
- 原SQL错误宣称留本地证据但丢弃stderr。现失败写入独立目录sql-error.log。

独立执行 `test-config.test.ps1` 的9项parser反例通过；Python py_compile通过。没有运行Maven或启动服务。最新Java新增双JVM正式创建/更新测试直接使用生产 TripService/TripRepository及显式TransactionTemplate，屏障位于首次权威读取后；不是手写模拟SQL替代业务操作。原worker崩溃测试仍是自建标记，两个范围不混算。

源码待实证候选：普通幂等占位INSERT唯一键冲突后，TripService在同事务catch后replay，PostgreSQL可能因事务已中止而拒绝后续读取。新增双JVM同key创建测试能验证；首次真实运行前不把此候选写成已经复现或已修复的缺陷。

本机新便携PostgreSQL/Redis由专门实施者按用户授权准备；其版本、社区Windows/Cygwin平台局限及实际运行结果等待独立证据，不能用本报告此前PATH不可用事实否认随后安装状态。

## 真实运行后裁决（11:15阶段）

此前的PostgreSQL事务中止候选已经实证，不再只是推测：`infra-real-before.log` 为10 run /9 passed /1 failure /0 skip；`CP6_REAL_INFRA_BEFORE_20261013.xml` 保留首次JUnit。子进程 `cp6-formal-7994770643265336961/first.log` 的幂等replay SELECT 明确 SQL state 25P02，因前面唯一键冲突中止了同一个事务。

独审已复核最小生产补丁：TripRepository占位INSERT仅对 `(owner_id, operation, idempotency_key)` 使用 ON CONFLICT DO NOTHING；受影响行为0时在Java抛现有DuplicateKeyException，供领域既有replay分支处理，不再用SQL错误使PG事务中止。其他SQL约束错误不捕获/不吞掉，也不修改领域结果或隔离级别。三个SQLite兼容测试分别保留原占位+同事务可读取、owner/operation分域、无关NOT NULL异常传播。

`infra-real-after.log` 实际10 run /10 passed /0 failure/error/skip，其中6项真实PG/Redis集成、4项目标守卫。独审亲读日志与before25P02；没有通过重试、清空第二仓储L1或削弱同key断言掩盖失败。`infra-trip-regression-final.log` 54/54通过，包含三个SQLite兼容测试。

实际双HTTP JVM首轮也通过四检查，证据 `infra-http-first.log` 与 `CP6_REAL_INFRA_HTTP_20261013.json`：共享用户权限、同key只创建一个trip/version、同版本编辑一200一409且仅2个版本、强杀一实例后另一实例读取v2。使用包 SHA256 `4C1156FF566483DA997A789D619100601C46FEBBA8465B55AE541BDD61D81205`。这是新增的真实业务HTTP范围；仍不包括BFF、真实供应商或全部planner proposal/open/chat多实例链。

本地服务为 PostgreSQL16.15 与 Redis7.4.3社区Cygwin，不能外推成生产Linux/容器集群验收。服务进程崩溃脚本已独审：入口拒绝优化Python；只通过测试生成端口查监听PID并核对私有运行目录下的可执行文件路径后kill，排除用户8080/3000/5173。使用随机schema/key，PG验证fsync/synchronous_commit/full_page_writes，Redis同连接SET+WAITAOF并等待本地AOF确认后强杀；恢复同数据目录并检查标记，finally仅清本schema/key且恢复原fsync。独立py_compile和 `-O` 拒绝通过，尚待该独占实际运行证据，不能把只读脚本审查算服务crash已通过。

## 服务进程强杀实证与最终独立裁决（11:18收口）

已亲读 `CP6_REAL_INFRA_SERVICE_CRASH_20261013.json`、原运行目录 `service-crash-attempt-1.log` 与 `postgres-crash-restart.log`。首轮服务强杀探针退出0，四步骤通过，未追加服务重跑。PostgreSQL PID从23964变40788，同PGDATA恢复已提交标记；原日志明确记录 interrupted、automatic recovery、WAL redo及end-of-recovery checkpoint后ready，证明经历异常中断恢复。Redis PID从72228变47636，在同连接SET+WAITAOF本地确认1之后强杀，再以同配置/目录恢复，标记完整。三个清理结果均true：本次schema、Redis标记移除，原appendfsync恢复。主智能体另执行owned runtime停止；PG原日志亦记录最终正常关闭。

结论：本板块的已确认PostgreSQL幂等事务缺陷已经实证修复；真实PG/Redis专项10/10零跳过、SQLite及Trip兼容54/54、双实际Java HTTP实例四项检查、PG/Redis服务进程强杀四步骤均有原始证据支持。独审报告在此冻结，不将初始候选或只读审查替代实际验收。

边界仍明确保留：上述服务强杀是Windows PostgreSQL16.15与社区Cygwin Redis7.4.3的已提交持久化标记验收，不能等同断电/磁盘故障、生产Linux、集群HA或完整产品全部流程崩溃恢复。HTTP业务覆盖正式行程创建、CAS更新和实例丢失后的读取；BFF、真实供应商以及其余planner/open/chat跨实例生命周期未由本轮证明。未读取业务.env值、未操作共享用户服务；统一经验记录与交付由主智能体维护。

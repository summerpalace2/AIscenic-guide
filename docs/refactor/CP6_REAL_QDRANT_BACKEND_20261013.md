# CP6 真实 Qdrant 板块：后端实施与验收入口

状态：真实 PostgreSQL/Qdrant 定向及后端全量回归通过；本地交付复核中。

## 已确认的问题与实现

1. 管理文档索引任务原先仅在启动时检查 revision，并依赖 JVM 内 static stripes。另一 JVM 可以在 embedding 期间删除文档或调整城市，旧任务随后仍向 Qdrant 写回旧资料；SQL markSynced 的条件更新不会撤回这些向量。同步刷新还使用无 revision 的状态回写。
2. 原逐碎片 embedding 异常被吞掉，全部或部分失败仍可能标记 synced；原替换先删除旧索引，供应商失败损失已有资料。
3. 种子导入与管理文档都使用 production-v2 激活命名空间，种子重建清理只按该命名空间过滤，会将管理员上传点当作 stale seed 删除；全集合 count 也不适合验证种子数。

实现保持现有 SQL 文档 revision 和 Qdrant 集合权威，不新增队列服务或城市字典：

- KnowledgeIndexPort 提供 PreparedIndex。解析、切片、全部 embedding 在数据库锁外完成，任一有效块失败都抛出异常，不发布残缺替换。
- JdbcKnowledgeDocumentStoreAdapter 使用同 DataSource 的事务行锁。更新、归档、同步 admission 与最终 publication 使用该锁；最终发布前再次检查 active/revision。排队在事务提交后进行，避免任务读取尚未提交的新版本。
- 最终发布先读取旧点 ID，写入新点成功后再按旧 ID 清理。upsert 失败不删除旧点；清理失败不报告 synced。同步在准备期间被更新或删除时明确失败，旧任务不能覆盖最新状态。
- 管理点显式 index_owner=managed-document，种子点显式 index_owner=seed-snapshot；激活命名空间不变。种子 reconciliation 与 count 限定种子 owner。历史种子仅按既有显式来源结构（无 owner、无 managed source、有 knowledge_document_id，并属于正确命名空间）识别，不根据正文猜测。
- 空种子快照对已有、符合 1536/Cosine 合同的集合清理其 seed 点，保留 managed 点；集合不存在时成功 no-op，不为零资料新建集合。
- 现有 rerank 候选内容指纹仍用于跨实例缓存隔离，真实检索矩阵同时验证另一 adapter 已预热缓存后的编辑、迁移和删除。
- 管理文档写入 Qdrant 时携带 SQL `index_revision`。检索在 Qdrant 候选之后再次查 SQL，只有活动、已同步且来源、适用范围与修订号一致的 managed 点可见；修订号为 0 的旧点允许缺省修订字段。无修订号的生产发布入口明确拒绝。
- 仅明确属于 seed-snapshot 或符合旧种子来源结构的无主点可免 SQL 校验；未知 owner 不能混入。候选检索有界补取后再过滤与重排，来源名建索引。
- 回填聚合同源向量的修订号；版本混杂时不声明可见。单来源回填使用数据库事务与锁表串行化，旧快照更新只命中相同修订号；精确来源优先，空来源同名旧行只有唯一匹配时才迁移。

## 可重复验证

离线 / 临时 SQLite：

- QdrantPublicationFailureTest：全部 embedding 失败、部分失败、旧点清理失败、upsert 失败不删旧点。
- KnowledgePublicationLifecycleTest（5项）：准备期间归档、准备期间迁移城市、同步期间归档不能谎报成功、失败后同步重试、直接执行器不继承已提交事务资源。
- QdrantSeedOwnershipTest（3项）：真实 importer 调用合同中的 owner 过滤与 scoped count、两种空快照边界。
- KnowledgeDocumentServiceAsyncTest：实际临时 SQLite + 指定执行器 + synced/chunkCount 回写。
- 独审 KnowledgeCityIsolationIndependentTest 的旧队列回归已改用 prepare/publish 合同，保留原版本与城市断言。

真实 PostgreSQL / Qdrant opt-in：Cp6RealKnowledgeQdrantIntegrationTest 共 10 项。它使用实际迁移、SQL 仓储、文档服务、Qdrant adapter/importer、scroll/count/search 与实际 rerank 缓存；只有 embedding/rerank 供应商为本地确定性实现，不调用收费供应商。

1. CRUD、候选 LIMIT 前城市隔离、GLOBAL/UNSPECIFIED、peer cache 编辑/迁移/删除及 backfill。
2. 从临时 JSON 源实际生成种子，连续导入两版和空版，保留 managed 点并清理旧 seed。
3. 根 JVM 归档 + 子 JVM embedding gate，验证旧任务不能复活。
4. 根 JVM 修改城市 + 子 JVM embedding gate，验证旧城市不能写回。
5. 真实旧 Qdrant 点在 embedding 失败后仍保留，SQL 状态为 failed。
6. 删除旧点失败造成两代物理并存时，pending/failed 状态不可召回；重新同步后只召回新版。
7. 将旧修订号点与未知 owner 点重新放入集合，已同步新版检索仍只返回当前修订。
8. SQL 文档遗失后回填保留 Qdrant 已发布修订号；迟到的旧回填不能隐藏新修订。
9. 两个并发请求对同一来源回填只生成一条活动记录。
10. 已同步来源与同名空来源旧行共存时，回填不合并两行，也不隐藏有效来源。

配置只接受显式测试环境变量；不读取业务 .env：

- 现有 CP6_IT_PERMISSION、CP6_IT_PG_* 与 CP6_IT_REDIS_* 由 Cp6InfrastructureTarget 统一验证；本组不连接 Redis，但复用该安全配置对象。
- CP6_IT_QDRANT_ENABLE=true
- CP6_IT_QDRANT_PERMISSION=DISPOSABLE_CP6_TEST_ONLY
- CP6_IT_QDRANT_HOST：仅 loopback
- CP6_IT_QDRANT_GRPC_PORT：独立实际端口
- CP6_IT_QDRANT_API_KEY：隔离实例的明确认证值；缺失时在连接前拒绝，不输出值。子 JVM 继承同 CP6_IT_* 认证配置。

每个用例内部生成 cp6_ 加 32 位十六进制的 schema 和 collection，创建与清理仅针对这些资源。collection 创建回执丢失时仍按该随机名尝试清理，清理失败作为测试错误保留。子 JVM 只继承 OS 基础变量和 CP6_IT_*，从临时目录启动，不加载应用主程序、不读取开发 .env、不接共享供应商。

执行示例（根线程统一加载隔离配置后）：

    .\mvnw.cmd test "-Dtest=QdrantPublicationFailureTest,QdrantSeedOwnershipTest,KnowledgePublicationLifecycleTest,KnowledgeDocumentServiceAsyncTest,KnowledgeCityIsolationIndependentTest,Cp6RealKnowledgeQdrantIntegrationTest"

## 实际验收记录（2026-10-13）

- 原 Luna 运行的 209 份 Surefire XML 中有 1270 个实际 `<testcase>`：1260 通过、10 跳过、0 失败、0 错误。此前按 `<testsuite tests>` 相加仅得 1218，是 6 份 `@Nested` 父报告的头部错误标为 `tests="0"`，其中仍包含 52 个实际用例；计数差异已查明，不存在遗漏失败。
- 修订号、回填并发等后续补丁的定向回归：35 项通过，0 失败、0 错误、0 跳过，其中真实 PostgreSQL/Qdrant 集成 10 项。
- 最终后端全量 `mvnw.cmd test`：BUILD SUCCESS；209 份 Surefire XML 实际含 1275 个 `<testcase>`，1265 通过、10 跳过、0 失败、0 错误，与 Maven 控制台 1275 项一致。
- 独立只读复审逐次发现并复核了修订号竞态、无主点、旧回填快照、重复来源及同名旧行误合并；相关修复均已落地，源码审查不代替实际测试。

## 必须保留的边界

- SQL 与 Qdrant 不具备跨库原子提交。新点写入和旧点删除之间可短暂并存；管理点检索以 SQL 发布状态及修订号作最终可见性门禁，因此不能由旧/失败代向量进入结果。物理孤儿点仍需后续同步清理，进程崩溃后没有自动重试保证。
- 候选补取上限 200 个 Qdrant 点；若更高分的不可见点超过上限，排在其后的纯种子点可能无法召回。这是有界召回限制，不是越权可见性问题。
- 城市迁移先撤销旧适用范围；其后进程崩溃或新索引失败可导致暂时缺少向量，不能为可用性恢复而继续提供旧城市资料。
- 内存执行队列不持久，进程崩溃后 pending/syncing 文档需要显式同步恢复。本板块不引入持久队列。
- 本轮只验证了 CP6 隔离 PostgreSQL 与本机 Qdrant；Redis 用于同一套隔离设施，但 Qdrant 集成用例不依赖 Redis。未验证 Linux/生产多节点部署、持久队列和进程崩溃后的自动恢复。

# 知识库显式城市隔离实施与自查

日期：2026-10-13。实施范围：Java 知识库写入、SQL 检索、向量入库/检索、来源回填及管理 API。测试只使用临时 SQLite、模拟 Qdrant/Embedding/重排服务和 MockMvc，没有修改用户开发数据库、真实供应商或 8080/3000/5173 服务。

## 确认的问题与最终契约

此前 `RagRetrievalService` 仅把 city 拼入查询正文，本地文档没有城市字段，Qdrant 过滤也没有城市条件。语义相似的异城内容仍可能被检索；这不是更换提示词能保证解决的问题。

文档与向量使用相同显式字段：

| scope | city | 检索适用性 |
| --- | --- | --- |
| CITY | 非空明确城市 | 本城市查询可用 |
| GLOBAL | 空 | 所有城市及无城市查询可用 |
| UNSPECIFIED | 空 | 仅管理可见，不进入回答证据 |

城市别名复用现 taxonomy，匹配时规范为目录规范城市名。未匹配的明确城市字段保留 trim 后精确值，不要求先加入目录，不擅自补后缀，不从正文、标题或默认城市推断。长度上限 128，拒绝控制字符。CITY 缺少城市、GLOBAL/UNSPECIFIED 带非空城市或非法 scope 返回参数错误。

创建 multipart `/ai/knowledge`、编辑 form `/ai/knowledge/{id}`、现有 BFF 使用的 JSON `/ai/rag/knowledge/{id}` 均接收 `scope/city`；列表和详情返回同名字段。未传创建字段保持兼容，写 UNSPECIFIED；编辑同时省略两字段则保持现值。管理列表仍可查看全部记录。无 city 的 `/ai/rag/retrieve` 不使用默认重庆，只检索 GLOBAL。

## 检索、同步与缓存

- SQL WHERE 在相关性排序和 LIMIT 之前筛选 GLOBAL 或当前 CITY，避免异城前 N 项占满候选导致同城资料饿死。
- `ScenicKnowledgePort.searchScoped` 接收 `KnowledgeScope`，返回带 scope/city/provenance 的 `KnowledgeFragment`。Qdrant payload filter 在搜索 limit 前应用城市与 GLOBAL 分支，同时保留 production namespace 与 RAG eligibility 条件。
- 向量结果仍做元数据一致性校验，防御提供方异常返回；这不是主要隔离机制。RAG facts 保留 scope/city/source/id，local facts 同步附元数据。
- `queryKnowledge`、旧 `searchFragments/searchFragmentsDeep`、ParallelRagService、structuredKnowledge 与无 city 的本地搜索旧入口最低兼容为 GLOBAL-only，没有无过滤回退。旧 queryKnowledge 的重排 timeout 参数保留。
- 文件和正文同步携带数据库权威 scope/city。scope 修改先成功撤销旧 source 的向量，再单条 SQL 同时更新正文、scope/city 与 revision；撤销失败明确报错，旧 SQL 归属不变。无正文参数时仍重建当前正文/原文件，显式清空正文优先清向量而不恢复旧正文。
- 沿用现索引 revision/文档锁，排队的旧 revision 不得重新写回；归档后旧任务不得复活。相同标题/文件摘要在 scope+city 域内去重，允许不同城市的同名资料。
- scope、内容与删除变更清理现重排缓存。向量重排继续使用候选指纹，显式 scope 也进入缓存查询键，不另建缓存系统。
- 向量 source 摘要仅在全部片段声明有效且完全一致时继承 metadata。混合、缺失、非法保持 sticky UNSPECIFIED；回填仅新 INSERT 继承，已有管理员/旧记录不重新分类。

## 数据迁移与部署边界

迁移 9 增加 `kb_document.scope/city` 与 `(scope,city,status)` 索引，沿用现事务、版本登记、可重复执行及 PostgreSQL advisory lock 机制。旧记录保持原 id/正文/数量，默认为 UNSPECIFIED，不删除或擅自认定城市。

现 `knowledge_documents.jsonl` 本来具有显式城市字段，本次在同一资产上补 scope 并按现城市目录规范 city。新 seed INSERT 携 metadata，重复 seed 使用 ON CONFLICT DO NOTHING，既有正文与管理员分类不被覆盖。真实知识资产仍以重庆为主，隔离机制通用不代表全国资料已补齐。

Production V2 importer 使用文档显式 scope/city，或实体已有显式 city；不从内容猜。显式 GLOBAL/UNSPECIFIED 不继承实体城市。未标注内容 payload 不具备 RAG eligibility。现存远端 Qdrant 点不会被本次离线操作更新，需要运维在审核 metadata 后受控重索引；旧无 scope 点被排除，不能称已完成远端数据迁移。

升级会让未标注历史资料暂时退出回答检索，这是防止跨城误用的预期行为。运维可在管理界面逐项标注并同步；不得将全库一键标为重庆或 GLOBAL。回滚应用版本可能恢复旧的不隔离读取，故不能将旧代码回滚视为仍满足隔离要求；保留数据列并前滚修复优先。

## 验证与已知限制

专项 `knowledge-city-contract-regression.log`：2026-10-13 08:57:25 BUILD SUCCESS，96 run / 93 passed / 3 skipped / 0 failures / 0 errors。包括独立审查 12 项、HTTP 字段契约、SQLite 迁移重复/旧数据不改、同 query 多城、GLOBAL/未标注、220 异城候选截断、scope 撤销失败、单 SQL trigger 失败原子性、scope+清空组合、旧任务与归档、同名/同文件跨城、seed 重复保全、Production metadata→payload、来源一致性回填、GLOBAL 旧入口及 timeout。

失败过程如实保留：`knowledge-city-independent.log` 首轮 protobuf 文本打印转义造成独审 oracle 假阴性，已改直接读取 nested keyword；`knowledge-city-regression.log` 暴露旧 mock 重载与手建 schema fixture 不匹配；`knowledge-city-regression-final.log` 新 seed 测试遗漏 IOException 声明导致编译失败。修后没有关闭断言或恢复无过滤检索。

尚未验证：真实 PostgreSQL、Qdrant payload 索引及远端历史数据重标、多实例跨库并发写、远端网络故障。跨 SQL/Qdrant 没有分布式事务：先删向量成功而 SQL 写入失败可能暂时缺向量，数据仍留 SQL，可重同步；不会以新 city 的 SQL 归属继续保留旧 city 向量。管理员对 metadata 真实性负责，代码不能替代资料审核。

源码定位：`domain/rag/model/KnowledgeScope`、`KnowledgeFragment`，`KnowledgeDocumentService`、`RagRetrievalService`，`JdbcKnowledgeDocumentStoreAdapter`，`QdrantScenicKnowledgeAdapter`，`ProductionV2ChunkService/ProductionV2QdrantImporter`，`KnowledgeSchemaInitializer/KnowledgeSeedDataInitializer`，`KnowledgeController`、`KnowledgeApplicationServiceImpl`、`RagApplicationServiceImpl`。

最终验证（2026-10-13）：

- `knowledge-city-full-regression.log` 首轮全量为 1,215 run / 2 failures / 0 errors / 14 skipped。两处旧检索 fixture 没有显式 scope，迁移后成为 UNSPECIFIED，被新的 GLOBAL-only 入口正确排除；仅将这些测试资料声明为 GLOBAL，保留相关性、长正文与英文分词断言，未放松生产隔离。
- `knowledge-city-fixture-regression.log` 修后专项 115 run / 112 passed / 3 skipped / 0 failures / 0 errors，09:01:49 BUILD SUCCESS。
- `knowledge-city-full-final.log` 最终全量 **1,215 run / 1,201 passed / 14 skipped / 0 failures / 0 errors**，09:03:23 BUILD SUCCESS。跳过项不计入通过数。
- `knowledge-city-final-package.log` 在上述全量通过之后执行 `mvnw.cmd -pl ai package -DskipTests`，09:03:50 BUILD SUCCESS。该打包步骤不另计测试通过数。
- 产物 `D:/scenic-guide/ai/target/scenic-guide-ai-0.0.1-SNAPSHOT.jar`，143,649,446 字节；SHA256：`96907B1BA0AB59FAFD36D73ACDBB4BA2000F2EA31C3641958747317B48ECE131`。
- `git diff --check` 退出 0，仅存在既有 LF/CRLF 提示。没有重启用户服务、修改真实数据库或推送部署；产物是本地可审阅版本，不能据此声称远端基础设施已验收。

# B 检索实验准备度审计（2026-10-17）

结论：可以进入**资产准入与实验协议冻结**，当前不能把 dense / sparse / hybrid / rerank 四路真实同语料比较称为已经就绪或已通过。现有资源比旧基线 30 篇大，但官方可追溯、时效、运行时及正式数据库 schema 均有实际缺口。续接模型采纳不在本次修改范围。

## 审计边界与证据

只读检查 D:/scenic-guide 当前源码、资源、既有评测证据及 `ai/data/knowledge.db`（SQLite `mode=ro`）；仅探测本机端口与 Python 模块可发现性。未运行 Maven、未启动服务、未调用模型、未下载安装、未迁移或写入生产库。`.env` 未读取，故不报告真实配置值或凭据可用性。当前仓库已有大量用户修改，本审计仅新增本报告及 `evidence/retrieval-readiness-20261017/inventory.json`。

审计盘点 JSON 包含资源/源码 SHA-256、结构化来源清单、城市分布、重复与时效统计及运行时探测。`evidence/retrieval-readiness-20261017/document-readiness.json` 另含222条逐文档盘点行：原docId、正文hash、城市、类别、来源ID/URL、有效期、检查日期与事实金标准入原因，仅复用原资产标识，不复制正文。外部源页面没有重新抓取，本报告统计的是**仓库保留的出处证据**，不是今日官方信息核验。

## 数据资产：222 篇资源与 218 条旧库，不能相加

- `ai/src/main/resources/knowledge_documents.jsonl`：222 篇、222 个唯一 docId，重庆市 218，北京/杭州/西安/武汉各 1；全为 CITY。原始正文精确重复 0；同 entityId 正文 SequenceMatcher 相似度 ≥0.85 的成对重复 0。这个粗检不能证明没有模板重复、语义重复或相邻块泄漏。
- 类别包括 63 dynamic、68 accessibility/transport，以及静态、餐饮等；四城的 4 篇是简短静态 overview。五城分布不等于五城具有等量可用检索证据，重庆占 98.2%。
- 原有 `ai/data/knowledge.db`：`kb_document=218`，正文与 JSONL 前述重庆资产的 218 篇全部匹配，属于同一资产的存储副本，不能累加为 440 篇。全部 active、vector_status=pending，chunk_count 合计 0；此状态不是向量成功发布证据。
- 旧库 kb_document 缺少当前仓储 SQL 所需的 scope/city/source_url，也缺 index_request_mode/index_lease_until 等恢复列。源码有迁移器，但本次没有启动或迁移；直接把此库拿给当前源码跑检索不能假定兼容。该文件不一定是部署环境当前实际连接库，环境未读取，故只对检查到的文件下结论。
- 本机库未见 prod_v2_* 表。`work/` 当前为空；多项 ProductionV2 测试明确依赖 `../work/production-v2-source`，并以 assumeTrue 在缺失时跳过。对应五文件 entity_index/source_documents/fact_claims/knowledge_documents/qa_issues 真实外部资产在本仓库未找到。production v1 原始包也未定位。不得用测试内临时 JSON 或 frozen-source Java 副本冒充这些资产，不能自动创建替代知识库。
- `attractions.json` / `dining_venues.json` 与数据库 attraction/dining_venue 是规划目录资产，不应未经事实字段、出处和许可盘点就合并为检索真值。

## 来源、时效与许可

JSONL 共有 51 种 sourceIds：4 个是直接官方 URL（故宫、文旅部西湖、秦陵博物院、湖北文旅厅），47 个为符号 ID。可直接落到 URL 的知识文档仅 4 篇，不能把 sourceId 数或知识篇数视为官方页面数量。其余文档出现 src-gov / src-authorized 等命名，但本仓库没有与之对应的 source_documents 注册表资产；符号名不能独立证明官方性质、访问成功、授权转载或摘录准确性。

136 reviewed、22 APPROVED、若干 claims.verified 是资产自述状态。本次不能确认这些标记由谁审核，**不称为独立人审**。其余 64 needs_review。所有 222 篇均无明确顶层 license/copyright 字段；所谓 authorized、来源 URL 的存在也不能代替使用许可证明。

以审计日 2026-10-17 比较 expiresAt，65 篇已经分别在 2026-09-13/19 到期。其余 153 篇有未来到期日期，4 篇只有 updatedAt。未到期是记录的元数据，并不保证今日价格、管制、电梯排队或开放情况仍正确；四条静态官方简介也不足以支持实时出行条件。动态字段须将 validAt/freshness 纳入标签和拒答协议。原始 SQLite 218 条没有 URL，正文相同也不代表出处元数据已完整入库。

## 当前检索实现与真实证据的界限

| 层 | 当前已有 | 尚不能声称 |
| --- | --- | --- |
| 本地关键词 | JdbcKnowledgeDocumentStoreAdapter SQL 字符匹配、同城/GLOBAL 过滤后有界候选；KnowledgeDocumentService + RagQueryExpander 加权评分 | 不是 BM25；没有 sparse vector / FTS5 / RRF 实现的源码证据 |
| dense | QdrantScenicKnowledgeAdapter 调 DashScope text-embedding-v2、1536 维，Qdrant Cosine 候选，后接重排，默认 production_v3_rebuild 集合 | 有代码不等于供应商密钥、网络、同语料向量索引或真实质量已验证 |
| rerank | RerankService + BailianRerankAdapter，HTTP 供应商、超时/缓存/回退代码 | 回退保留原顺序不能计为成功重排；需单列实际供应商成功率、超时和缓存命中 |
| hybrid | 当前主链是向量成功则用向量、失败走本地 fallback | 两路回退不是双路召回融合；未见 BM25+dense 的 RRF/加权合并 |
| 语义证据 gate | RagRetrievalService + LlmSemanticEvidenceAdapter；有真实聊天模型 gate 测量 | gate 通过不能证明真实检索 Recall/MRR 或官方事实准确率 |

`RetrievalBaselineEvaluationTest` 与 `tools/ai-evaluation` 有固定数据 hash、文档 ID、Recall@3/5、MRR、未知问题与跨城泄漏、耗时等脚手架。旧 30 篇包含 25 篇合成、5 篇仓库原文，100 问来自 20 个语义 family；标签为 agent-authored provisional，明确无独立人工标注。既有 baseline-20261017-03 记录 localFallback EXECUTED，dense/bm25/hybrid 均 NOT_RUN。它测的是受控 SQLite 路径；该历史运行与当前已改变 gate 的源码须区分，不能直接宣称重跑仍会得到同分。

`LiveSemanticGateEvaluationTest` / `ClaimGateLiveEvaluationTest` 使用真实聊天模型，但 mock ScenicKnowledgePort / KnowledgeDocumentService，并提供预先选择的合成 evidence。它们是 gate 实验，未实际执行查询 embedding → 候选检索 → rerank 的端到端质量测量。`Cp6RealKnowledgeQdrantIntegrationTest` 的历史材料说明真实 PostgreSQL/Qdrant 上做过生命周期/隔离实验，但 embedding/rerank 为本地确定性替身；这是基础设施合同证据，不是自然语言语义排序质量。`SqliteRetrievalBenchmark` 也有独立 runner，但需要新 benchmark DB 与 Qdrant 文档 ID 清单，本次未执行。

## 本机运行时现状

Python sqlite3/numpy 可发现；torch、transformers、sentence_transformers、rank_bm25、qdrant_client、onnxruntime 均不可发现。标准 HuggingFace cache 看到的是 OCR 资产，未见检索 embedding/rerank 模型；标准 Ollama model 目录未发现模型。该搜索范围有限，不断言整台机器没有其他位置的模型。

127.0.0.1 的 6333/6334/11434/8080/8081 TCP 探测均无监听。当前 PATH 未发现 docker/ollama 命令。SQLite 能只读打开不等于当前应用 schema 已兼容；本地 Qdrant / embedding / rerank 无已验证可用运行时。配置源码可见 QDRANT_*、DASHSCOPE_EMBEDDING_URL、BAILIAN_RERANK_URL 与数据库变量，但本次未读取 `.env`、未探测远程服务，不能断言远程供应商不可用或已可用。

## 最小下一板块：先冻结准入和评测，后执行同一份四路实验

1. **恢复来源链而不扩库**：先确认外部 production v1/v2 包是否仍存在及真实位置；拿现有 222 篇做 manifest，按原 docId、原文 hash、sourceId→官方 URL、采集/发布时间、license/使用依据、失效日期建审计映射。缺来源/过期的文档分层标记，保留原生产资料。四个官方 overview 只能做 smoke；不能拿它们当完整真实评测集。没有新增官方源，就明确只能在218篇偏重庆遗留资产做条件实验，不能发布“真实官方检索已验收”。
2. **固定最小可信集**：优先复核当前资产中约 50–80 个独立官方页面/实体事实组；若实际只有 4 个可追溯来源，先停止真实质量验收并报告不足，不能合成扩写成80条。在足够来源准入后冻结约 60–100 个独立语义 family（含跨实体、同名异城、改写、过期冲突、无答案、近义硬负例），按 source/entity/family 切 dev 与 holdout，同一来源的重叠切块不得跨分区。重庆与四城薄覆盖分别报告；真实人工复核只有发生并有记录时才称人审。
3. **单一规范资产、隔离索引**：在得到执行授权和现有运行时核验后，以同一份冻结 docId/chunk/hash 清单建立可销毁的实验索引；不得另起一套长期知识权威或导入生产集合。先跑当前生产本地 fallback 作为基线，再加 BM25、dense、BM25+dense RRF，以及融合后 rerank。候选/最终 topK、切块、城市过滤、查询集保持一致；分别保存重排前后 ID/score、来源、耗时与实际 provider 状态。若使用本地模型，先核实已安装模型、版本和许可证；现环境不满足时明确申请运行时准备范围，不静默下载。
4. **避免小库满分**：报告 NDCG@10、Recall@5/10/20、MRR、候选召回上限、无答案误答/误拒、跨城泄漏、时效违规、p50/p95 与费用。按来源/实体 family bootstrap 区间；对 hard negatives 与长尾切片独报。每个查询的真值来自原始来源独立标注，不由本次排序输出生成；已用于调参的 holdout 永久转 known，下一轮另取未见 family。样本规模和不足必须展示，满分不能自动代表泛化。
5. **执行前 runtime gate**：只读确认最终实际 DB URL/schema、原索引文档集合、Qdrant dimensions/collection 与 embedding/rerank smoke 成功，再启动质量实验；区分实际模型与替身、真实官方与候选合成、供应商失败与检索错误。Maven 由 root 按当前串行边界决定，本子任务未运行。

当前可直接执行的最小交付是第1–2步的审计映射与协议；四路实验需要来源准入及运行时补齐，不能通过重复旧30篇基线绕过。

经验说明：本子任务读取了 ggy-workflows 项目与文件规范；未独立采用 Wiki 经验结论，经验使用登记由 root 总任务统一完成。该审计 JSON 是项目事实记录，不是新增知识库或人审证明。

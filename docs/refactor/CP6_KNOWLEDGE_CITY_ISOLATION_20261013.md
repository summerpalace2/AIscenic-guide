# CP6 知识库城市隔离实施与验收

日期：2026-10-13。状态：本板块实现、实施自查、独立审查与主线程复核完成；仅限下述受控验证范围。

## 目标与范围

修复上轮真实聊天暴露的异城知识召回：城市不能只是查询文字，必须成为文档与索引的显式归属。范围包括 Java 文档存储、历史迁移、种子资产、索引写入、标准/深度/兼容检索、缓存边界，以及 active web 管理端经 BFF 的实际字段传递。保留原有未提交修改；不提交、推送、部署或修改用户服务与真实数据。

## 已确认事实与设计

原知识文档没有 city 字段，RAG 只是把城市拼入 query；Qdrant payload 只过滤生产命名空间、rag_eligible 与可选 scenic_id；本地回退无城市 WHERE。结构化聊天、并行深度与本地搜索旁路也必须核验。

文档 scope 采用 CITY / GLOBAL / UNSPECIFIED，CITY 携带规范化 city。复用现有 taxonomy 城市别名；不根据正文、标题、文件名猜归属。旧未标注资料仍可管理，但不得作为任意城市的事实。城市查询仅允许同城 CITY 与显式 GLOBAL；无城市兼容入口仅允许 GLOBAL。全国通用是管理员明确声明，不能将空城市自动视为全国通用。

SQL 与向量过滤均在候选 LIMIT/TOP N 前执行；scope 进入索引载荷与检索契约。编辑归属需撤旧向量、清理缓存并通过现有 revision 同步新索引，失败不能报告成功。历史迁移只添加结构并安全重试；种子归属由外置资产声明，不能覆盖管理员编辑。

## 执行安排

1. 后端实施智能体负责 Java、迁移、索引、调用方兼容及针对性回归。
2. 前端实施智能体负责 active web 管理表单、BFF 字段白名单及真实 HTTP 序列化回归。
3. 独立审查智能体只读复核设计与源码，构造跨城市、未标注、全国通用、归属编辑失败及旁路反例；必要时在单独测试文件留证据。
4. 主线程核对端到端契约、迁移与失败证据，修复确定问题。生产源码冻结后做最终回归与独立验收，再导出本地审阅版本。

## 退出条件

- 同一个问题的不同城市请求不会返回异城或未标注文档；别名归一，未知城市不退回默认城市。
- 标准、深度、本地降级与旧兼容入口遵守同一范围规则；全国通用仅显式声明。
- 管理端能查看、创建和修改归属；省略编辑字段保持原值，非法组合返回错误，保存失败保留编辑而不假报成功。
- 旧数据库迁移重复执行不丢数据；检索在 LIMIT 前过滤；归属变更与异步索引不复活旧归属。
- 编译、相关回归、全量后端与独立检查有真实记录。真实 PostgreSQL/Qdrant、多实例故障仍须单列，不能以 SQLite 或 mock 冒充。

最终结果、日志和限制在本文件后续追加。

## 实际实现与复核

- 文档、迁移、SQL、向量载荷和查询统一使用显式 CITY/GLOBAL/UNSPECIFIED。范围过滤在候选截断前执行；无城市入口只检索 GLOBAL，HTTP 层也不再自动补默认城市。未知明确城市不受现有目录名单限制。
- 归属与正文、revision 使用单条 SQL 更新。撤旧向量失败时不改归属；旧队列、已归档来源不能复活。向量回填只有全部片段归属一致且合法时才继承；混合、缺失或非法元数据保留 UNSPECIFIED，已有管理员记录不被覆盖。
- 管理端经 multipart/form/JSON 显式传递归属；编辑先读取完整正文，避免用列表摘要截断正文；保存失败保留编辑，不假报成功；局部编辑身份守卫防止退出登录后的旧回执污染界面。
- 主线程补查 HTTP 入口，发现并移除遗留默认城市回退，新增 3 项真实 MockMvc 契约回归。独立审查构造 12 项反例，含 SQLite 迁移、220 条异城高分候选、数据库 trigger 原子失败和向量请求字段断言。

## 验证分层与首轮失败

专项最终 knowledge-city-contract-regression.log：96 run，93 passed，3 skipped，0 failures/errors。Node 枚举全部测试 knowledge-city-node-final.log：65 passed，0 failed/skipped。最终管理浏览器 cp6-knowledge-metadata-browser-final-20261013.log：2 passed；此前组合浏览器 8 passed（包含同样的管理路径，不能累加成 10 个独立用例）。前端 build 与类型检查通过。

首轮全量 knowledge-city-full-regression.log：1215 run，2 failures，0 errors，14 skipped。两项检索旧测试插入未标注资料，与新的 GLOBAL-only 兼容入口合同冲突；修订测试数据声明，保留检索相关性与摘要原断言，再执行全量。原失败证据保留。专项此前出现的 protobuf 中文显示 oracle、旧 mock/fixture 与编译错误也保留在原日志，不能描述成所有尝试一直通过。

本地 HTTP 测试使用实际 BFF 与伪 Java 上游；Java MockMvc 使用实际 Controller/应用与隔离依赖；SQLite 测试使用真实临时数据库。上述证据不等同真实 BFF→Java→Qdrant/生产数据库的完整联调。

## 运行与资产限制

本轮未修改真实数据库或共享向量索引、未启动或重启用户 8080/3000/5173 服务，未调用付费模型或高德。现存未标注文档需管理员明确归类并重新同步；旧运行进程与旧索引不会因源码变更自动升级。打包种子为 218 条重庆知识资料，通用隔离机制不代表其他城市知识资产已经补齐。真实 PostgreSQL、Qdrant、专用 Redis、多实例及崩溃恢复仍未验收。

## 最终全量验收

knowledge-city-fixture-regression.log：115 run / 112 passed / 3 skipped / 0 failures/errors。knowledge-city-full-final.log：1215 run / 1201 passed / 14 skipped / 0 failures/errors，2026-10-13 09:03:23 BUILD SUCCESS。跳过项不计通过；受影响专项、独立 12 项和 HTTP 3 项都包含在全量中，不能重复累加。

最终静态扫描：规划范围 122 个 Java 文件无所列规则命中；全后端 353 个生产 Java、22 个资源文件、18 个 JSON/JSONL 校验无 findings/非法 JSON。规则范围与资产/配置的单独分类见两个 CP6_KNOWLEDGE_*_LITERAL_SCAN_20261013.json；不是任意硬编码不存在的证明。

经验采用：FQ2 跨层契约核验，用实际请求序列化和 Java HTTP 回归定位字段遗漏及默认城市回退。任务 20261013-450f4fd5e73c 已 closed，assessment=benefited；看板聚合因历史 kind 字段缺失报错，没有宣称同步成功。

## 本地交付

最终全量通过后 knowledge-city-final-package.log 打包成功（-DskipTests 不另计测试），jar SHA256 96907B1BA0AB59FAFD36D73ACDBB4BA2000F2EA31C3641958747317B48ECE131，143649446 字节。机器统计见 CP6_KNOWLEDGE_FINAL_TEST_SUMMARY_20261013.json。

审阅导出目标：C:/Users/summer/Documents/Codex Deliveries/CP6-20261013-knowledge-city-review-final。导出包含三个仓库当前全部既有脏改动，不能把全部归为本板块；manifest 记录各仓库 HEAD、文件 hash 与排除项。日志、env、运行数据库和生成产物按规则排除，原始验证日志仍在工作区；未执行提交、推送或部署。

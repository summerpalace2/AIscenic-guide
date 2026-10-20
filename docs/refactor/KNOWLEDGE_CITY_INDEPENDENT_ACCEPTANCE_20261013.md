# 知识库城市隔离独立验收（2026-10-13）

本报告区分实施者单测、独立真实本地数据库验证与静态调用链审查。所有实验只用内存/临时 SQLite、mock vector client/provider，不访问真实模型、高德、共享 Qdrant、用户 Redis 或用户服务。

## 修复前只读事实

- `KnowledgeDocument`/`kb_document` 无结构城市归属；`RagRetrievalService` 仅把 city 拼入 query 文本，然后调用不带 scope 的 local/vector/deep API。城市词不是权限过滤，无法阻止热门其他城市资料占满 topN。
- `JdbcKnowledgeDocumentStoreAdapter.searchCandidates` SQL 只有 active 和文本匹配，先候选 limit 后 service 排序。结构过滤必须进入 SQL LIMIT 前，否则大量异城高分资料可令同城结果被饿死。
- `QdrantScenicKnowledgeAdapter.productionSemanticFilter` 原来只检查 production namespace、rag_eligible、可选 scenic_id；queryKnowledge、searchFragments、searchFragmentsDeep 共用该过滤，但不隔离城市。
- 旁路必须纳入：结构化聊天 `chatStructured→structuredKnowledge→queryKnowledge`；`ParallelRagService` 子问题 deep；`BaiduSearchService→LocalKnowledgeSearchAdapter` 本地优先。仅修改主流式 RAG 不能证明所有入口安全。
- Rerank 缓存已有候选正文 SHA256 与候选包含校验，有助避免不同候选集串用，但不能代替上游城市过滤。城市归属变更须同步索引/失效；旧向量 payload 不可在后台更新未完成或失败时继续作为原城市权威证据。

## 独立反例矩阵

| 场景 | 必须成立 | 验证方式 |
| --- | --- | --- |
| 同一 query 两个城市 | 北京只得北京+GLOBAL，杭州只得杭州+GLOBAL | 真 SQLite；vector filter captor |
| 全国通用 | 仅管理员显式 GLOBAL 可跨城，不以空 city 推断 | scope 参数/API/SQL |
| 未标注 legacy | 迁移默认 UNSPECIFIED；正文提及城市也不得自动授权 | legacy schema migration；未标注行同问 |
| limit 饥饿 | 异城高分行大于 limit，仍召回同城低分行 | 真 SQLite limit=1 |
| city alias | canonical 与 alias 同一 scope；未配置但明确的结构城市按 trim 后精确隔离，不把 alias 表当准入名单 | taxonomy/service 实参 |
| local/vector/deep/旧API | 所有路径过滤先于 limit/rerank，旧无 city 接口仅 GLOBAL | 调用链与真实 adapter request |
| scope 编辑 | 修改立即禁止旧城命中；不改正文也变索引 revision；失败不回旧归属 | 队列阻塞/失败索引，authoritative-source gate |
| 缓存 | 相同 query 城市更正不返回前城重排结果 | 独立候选集/缓存 key 审查及回归 |
| schema | 新建和旧库重复迁移幂等，UNSPECIFIED 不丢原文 | SQLite 实跑；PostgreSQL 方言只读/fixture |
| 聊天衔接 | 当前否定/多城歧义/未知不退回旧城；global-only 不注入城市资料 | 上轮 city captor 回归及当前路由审查 |

本轮不将 mock Qdrant filter 实参验证称为真实 Qdrant 服务验收；不把 SQLite 迁移通过外推成真实 PostgreSQL 双实例通过。生产实现冻结后补独立执行结果与剩余限制。

## 独立本地验证及真实失败保留

独立文件 `ai/src/test/java/com/ai/guide/domain/rag/service/KnowledgeCityIsolationIndependentTest.java` 使用真实内存 SQLite schema initializer、JDBC repository 和 document service；索引/provider 为 mock，不连接供应商。

首轮日志 `knowledge-city-independent.log`：11 run、10 passed、1 failed。唯一失败是独立验收代码用 protobuf 的 `toString().contains("杭州市")` 检查过滤器；protobuf 文本默认转义中文，结果及城市隔离断言此前已经通过。这是验收 oracle 错误，不能归咎于生产过滤失败。改为严格遍历 nested should/must/field/match.keyword，同时检查 GLOBAL 分支和 CITY+杭州市 AND 分支；不删断言、不改生产。另轮日志 `knowledge-city-independent-final.log`：**11 run、11 passed、0 failed/errors/skipped（08:48:41 BUILD SUCCESS）**。

这 11 项包括：旧库迁移9与重复初始化；正文提及城市不授权；两个城市同问与 GLOBAL-only 旧API；220条异城高分候选不能在 limit=1 时饿死同城；local 归属修改及归档；撤销旧向量失败保持旧city/revision并报错；scope+清空不重建旧正文；真实 SQLite trigger 拒绝归属更新时正文/city/revision不部分提交；过期队列和归档队列不复活；同标题与MD5跨city允许、同scope拒绝；alias和未配置明确新城市；标准/deep向量 filter 在 SearchPoints.limit 前且丢弃供应商错城/未标注payload，重排仅接收当前城市及GLOBAL。

本轮独审与根审同时识别两个组合边界：scope+清空曾重索引 existing 旧正文；scope和正文分两SQL导致第二SQL失败时正文已写入旧city。实施修正为空正文优先、带scope的单条UPDATE，以上独立测试均通过。

旧无city queryKnowledge/searchFragments/searchFragmentsDeep 现在统一 GLOBAL-only，因此结构化聊天、ParallelRagService 和 Baidu 本地优先旁路不会恢复无过滤；这些旧接口不会自行从 query 猜测城市。主流式RAG使用显式 scoped port。回查上轮 ChatRetrievalCoordinator：当轮否定/多城歧义仍不回退历史城市；无城市仍不请求城市供应商，未引入旧默认重庆回退。

另追加打包 seed 真实初始化专项（第12项），预插与首条seed相同id但UNSPECIFIED的管理员原文，初始化两次后保留该旧行，其余217条seed归属重庆市且重庆local能命中、杭州不能命中。当前打包资源共218行，全部显式CITY/重庆市；该数量来自实际数据检查，测试使用资源行数而非写死218作为业务阈值。**独立最终12 run、12 passed、0 failed/errors/skipped**，Surefire `com.ai.guide.domain.rag.service.KnowledgeCityIsolationIndependentTest.txt`，包含在 `knowledge-city-regression-final2.log` 的最终专项中。没有把纯JSON检查冒充数据库初始化验证。

## 最终交叉复核与裁决

- 后端受影响专项 `knowledge-city-regression-final2.log`：**90 run、87 passed、3 skipped、0 failed/errors，08:53:42 BUILD SUCCESS**，包含独立12项与实施者scope3项（含真实seed local）。此前 `knowledge-city-regression-final.log` 的实施测试fixture未声明IOException导致编译失败记录保留；它不是生产逻辑失败，也没有被跳过测试掩盖。完整后端全量结果另由根审追加，不将此90项称为全量。
- 前端实施与根审汇总：最终管理浏览器 **2 passed**、根枚举 Node **65 passed**，build/typecheck通过。浏览器使用真实页面与受控 HTTP fixture，BFF契约测试实际启动临时 HTTP server 并用 Java stub 收发请求，不是本轮连接真实Java/PostgreSQL/Qdrant全链路。
- 独立只读管理链路：列表摘要不可作为编辑正文；先GET受管理员鉴权的详情接口取得完整正文，失败不打开编辑、不退回摘要。详情捕获用户对象与局部owner，logout清editor/owner，旧finally不能清新owner；保存回执校验同用户/同editor才发布。撤回初审中“当前logout不重置loading仍会并发新detail”的未经成立推断；最终局部owner处理的是logout清理后的真实生命周期，不引入全admin通用generation框架。
- scope JSON/multipart参数仅白名单转发；更新遗漏字段保持旧归属。ProductionV2ChunkService 从显式document scope/city或关联entity结构city生成归属，importer将scope/city写入point，UNSPECIFIED不能rag_eligible。旧向量payload不会因正文出现城市词而自动授权；启用新向量归属仍需运维对真实目标集合执行受控重新索引，本轮没有修改共享集合。

**范围裁决：本轮已识别的知识库城市隔离、管理编辑及scope变更一致性问题，在上述源码和离线/受控证据范围内闭环，未发现剩余确定阻断缺陷。** 未运行真实Qdrant过滤/重新索引、真实PostgreSQL迁移、多实例更新与进程崩溃恢复，因此不声称已完成这些基础设施验收。GLOBAL是否准确代表全国适用依赖管理员标注与数据审校；隔离机制不能替代内容真实性校验。alias仅规范已配置同义名，未配置明确城市按精确值隔离；自然语言任意新城市解析不在本轮完成声明内。

独立文件及报告现已冻结；未调用真实模型/高德、未改用户服务、未推送或部署。经验记录由根智能体合并维护：本轮复用“结构事实与正文分离、过滤先于limit、保留失败并区分oracle错误”的验收方法，实际揭示并闭环scope+清空与原子更新边界，基础设施部分仍未验证。

## 追加的向量回填归属闭环

实施者自查发现新source回填文档没有继承已标注向量scope，根审批准本轮最小补齐。独立只读复核最新链路：`listSourceSummaries` 按source聚合，所有片段声明一致才继承；混合、缺失CITY归属或非法声明转UNSPECIFIED，后续片段不能把该source恢复成CITY/GLOBAL。service再校验归属；repository仅新INSERT写scope/city，existing分支不覆盖管理员/legacy正文和归属；archive tombstone仍阻止删除文档复活。没有从正文猜城市。

以上是源码独立复核；追加实现的专门测试及最后完整后端全量统计由根审在取得最终日志后登记，不能以先前90项和独立12项替代尚未完成的最后全量。本报告在该新增范围内未发现剩余确定阻断，独立生产审查收口，不扩大板块。

## 主线程最终证据补记

追加回填、HTTP 与旧 fixture 合同复测：knowledge-city-fixture-regression.log 115 run / 112 passed / 3 skipped，无失败/错误。最终 knowledge-city-full-final.log 于 09:03:23 BUILD SUCCESS：1215 run / 1201 passed / 14 skipped / 0 failures/errors；含本报告独立 12 项。首轮全量 2 项旧 fixture 失败保留在 knowledge-city-full-regression.log，仅给检索测试数据声明 GLOBAL，原相关性和摘要断言保留。此段为主线程取得实际日志后的补记，不冒称独审另行执行了全量。

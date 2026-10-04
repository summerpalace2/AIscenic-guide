# 重构状态账本 (REFACTORING_LEDGER)

> 本账本严格遵循《保持 UI 与功能一致的大规模重构流程（定稿 v2.1）》。  
> 核心原则：以 A 的代码与运行现场为唯一真理；不搞大设计先行；先 A 后 B 提取核验项；严格四分类对账；不擅自修复原有疑似缺陷。

---

## 阶段 0：基准与当前信息

| 记录项 | 填写内容 |
|---|---|
| **A 的对照基准版本** | `42d69a9` (已包含环境自适应加载与意图规则基线修复) |
| **B 的当前分支** | `refactor/cola-architecture` |
| **当前重构切片** | **Slice 5: RAG 知识检索增强域 (适配层控制器迁移、应用层用例编排、高德Web与Qdrant导入器基础设施下沉)** |
| **当前推进状态** | **阶段 2·第五步：独立质检子智能体待验收 (Pending Adversarial Review)** |
| **全量自动化测试结果** | **386 passed / 0 failed / 0 error / 10 skipped (21.1s, BUILD SUCCESS)** |
| **最后更新时间** | 2026-10-04 20:14 |

---

## 一、 当前切片核验项清单 (RAG 知识检索增强域)

| 编号 | 条件与操作 | A 的表现与依据 (`42d69a9`) | B 的表现 (`refactor/cola-architecture`) | 核验方式与版本 | 结果 |
|---|---|---|---|---|---|
| V-RAG-01 | `POST /ai/rag/retrieve`<br/>多路语义召回与重排核验 | 接收可选 `body` Map，提取 query/prompt、city（默认重庆）、deep（默认 false）以及 constraints 偏好字典；委派检索服务，异常捕获返回 500。 | `RagController` 迁入适配层，委派 `RagApplicationService`，参数提取、默认值逻辑及 500 回退完全一致。 | `RagControllerTest.testRetrieveSuccess` + `RagApplicationServiceImplTest.testRetrieveSuccessAndNullFallback` | `[已验证一致]` |
| V-RAG-02 | `GET /ai/rag/stats`<br/>知识库健康与缓存统计 | 校验 `UserContext.isAdmin()`，非管理员返回 403（"无管理员权限"）；返回 totalChunks, cacheStats, sourceCount, retrieval 监控指标。 | 保持完全一致的鉴权文案与状态指标字典映射。 | `RagControllerTest.testGetStats` + `RagApplicationServiceImplTest.testGetStatsAdminCheck` | `[已验证一致]` |
| V-RAG-03 | `PUT /ai/rag/knowledge/{id}`<br/>文档元数据更新 | 校验管理员权限（403）；提取 title, category, content, tags；捕获 `IllegalArgumentException` 返回 404，其他异常返回 500。 | 保持完全一致的状态码分支（403/404/500/200）。 | `RagControllerTest.testUpdateKnowledge` + `RagApplicationServiceImplTest.testUpdateKnowledge` | `[已验证一致]` |
| V-RAG-04 | 碎片管理 REST 端点<br/>(`/rag/document/fragments*`) | 包含 `GET /fragments` (page 默认1, size 默认20, keyword), `PUT /fragments/{id}` (400 校验), `DELETE /fragments/{id}` (400 校验), `DELETE /document/all`；全部要求管理员权限。 | 保持完全一致的路径、默认分页值、异常 400 映射与文案。 | `RagControllerTest.test*Fragment*` + `RagApplicationServiceImplTest.testFragmentOperations` | `[已验证一致]` |
| V-RAG-05 | 缓存清理端点<br/>(`POST /ai/admin/cache/clear`) | 管理员校验 403；触发百炼 Rerank 三级缓存（L1 精确 / L2 归一化 / L3 SimHash）全部清空，返回 200。 | 保持完全一致的方法与响应载荷。 | `RagControllerTest.testClearCache` + `RagApplicationServiceImplTest.testCacheOperations` | `[已验证一致]` |
| V-RAG-06 | `GET /ai/knowledge` 系列<br/>知识文档列表/分类/详情 | `GET /knowledge`（category, keyword, page=1, size=20）；`GET /categories` 分布统计；`GET /{docId}`（不存在返回 404）；公开查询不要求管理员权限。 | 保持完全一致的路径映射、查询入参及 404 分支。 | `KnowledgeControllerTest.testList*`, `testGet*` + `KnowledgeApplicationServiceImplTest.test*` | `[已验证一致]` |
| V-RAG-07 | `POST /ai/knowledge` 与 `PUT /ai/knowledge/{docId}`<br/>文档创建与更新 | 统一校验管理员权限（非管理员返回 403 "需要管理员权限"）；支持纯文本与 MultipartFile 文件上传；参数校验失败返回 400，不存在返回 404。 | 保持完全一致的鉴权文案、MultipartFile 支持与 400/404 状态码映射。 | `KnowledgeControllerTest.testCreate*`, `testUpdate*` + `KnowledgeApplicationServiceImplTest.test*` | `[已验证一致]` |
| V-RAG-08 | `DELETE /ai/knowledge/{docId}` 与同步端点 | 管理员鉴权 403；`DELETE /{docId}` 软删除；`POST /{docId}/sync` 手动切片同步（404 捕获）；`POST /backfill` 历史 Qdrant 碎片元数据回填。 | 保持完全一致的鉴权、状态码与回填委托。 | `KnowledgeControllerTest.testDelete*`, `testSync*`, `testBackfill*` | `[已验证一致]` |
| V-RAG-09 | 文件导入与缓存管理<br/>(`POST /ai/import`, `GET /ai/cache/stats`, `POST /ai/cache/clear`) | 管理员鉴权 403（"需要管理员权限"）；空文件拦截 400；文件名自动解析提取；Rerank 缓存指标与重置操作。 | 保持完全一致的端点、400 空文件防御与缓存统计契约。 | `ImportControllerTest.test*` + `RagApplicationServiceImplTest.testImportFile` | `[已验证一致]` |
| V-RAG-10 | 适配层重构 (Adapter) | 原 3 个 Controller 位于 `domain.rag.api`，混合领域层代码。 | `RagController`, `KnowledgeController`, `ImportController` 迁移至 `com.ai.guide.adapter.rag.web`，彻底物理清理旧 api 目录。 | 架构包扫描校验 | `[明确获准的变化]` |
| V-RAG-11 | 应用层编排提炼 (Application) | 领域层服务直接作为外部门面，无统一用例接口。 | 提炼 `RagApplicationService` 与 `KnowledgeApplicationService` 应用层接口及对应实现类，隔离事务、鉴权上下文与领域对象响应转换。 | 架构分层校验 | `[明确获准的变化]` |
| V-RAG-12 | 第三方技术设施下沉 (Infrastructure) | `ProductionV2QdrantImporter`（Qdrant GRPC 客户端）与 `AmapWebServiceClient`（高德 Web API 客户端）原混杂在 `domain.rag.pipeline` 中。 | 分别下沉至 `com.ai.guide.infrastructure.rag.qdrant` 与 `com.ai.guide.infrastructure.rag.amap`，单元测试同步镜像迁移至基础设施测试包，领域层不再直接耦合底层 GRPC 与第三方 HTTP 客户端。 | 架构依赖图检查 | `[明确获准的变化]` |
| V-RAG-13 | 全量回归与测试安全基线 | 确保 Chat、Preferences、Trip、Planner 及 RAG 全链路 100% 绿灯。 | 全工程 386 项测试全部绿灯通过（0 errors, 0 failures, 10 skipped，耗时 21.1s），新增 27 项控制器与应用服务自动化用例。 | `mvn test` 自动化验证 | `[已验证一致]` |

---

## 二、 历史切片核验项清单

### 1. Planner 规划引擎域 (Slice 4)
- V-PLAN-01 ~ V-PLAN-14：14 个 REST 端点、X-Plan-Session-Token 请求头自动注入、空 Body 兜底、DIP 纯净化 `RouteGatewayPort`/`HydrationResult`、高德与会话仓储下沉全部通过验收（Commit `9b6fefd`）。

### 2. Trip 行程生命周期域 (Slice 3)
- V-TRIP-01 ~ V-TRIP-12：9 大 REST 动词、401/404/409 异常映射、局部重规划数据外露、PDF 导出、CAS 并发控制、仓储与 PDF 基础设施下沉全部通过验收（Commit `2afbe66`）。

### 3. Preferences 偏好画像域 (Slice 2)
- V-PREF-01 ~ V-PREF-09：5 大 REST 动词、401 鉴权拦截、409 OCC 冲突结构、400 校验、200/201 状态码、仓储端口依赖倒置全部通过验收（Commit `bfbb2ef`）。

### 4. Chat 对话流试点 (Slice 1)
- V-CHAT-01 ~ V-CHAT-08：10 参数流式契约、槽位隔离、情绪分流、双层超时容灾、RedisChatMemory 基础设施下沉全部通过验收（Commit `eda0277`）。

---

## 三、 疑似原有问题清单 (Suspected Legacy Issues)
> 规则：保留原行为，记录供用户调试判断，不擅自“修复”。

| 涉及编号 | 涉及接口/模块 | 现象与存疑原因 | A/B 是否保留 | 复现与验证方式 | 用户判断与状态 |
|---|---|---|---|---|---|
| ISSUE-01 | GET `/ai/chat/structured`<br/>(结构化对话槽位提取) | 原 `A_ChatController.java` [L569] 在调用 `slotTrackingService.extractAndSave(userId, message)` 时未像流式接口那样进行 `userId != null` 防御性校验，匿名用户会落库 `user:null:slots`。 | A/B 完全保留现状 | 匿名请求 `/ai/chat/structured` 观察 Redis key `user:null:slots` | 待用户决策是否在后续画像域统一治理 |
| ISSUE-02 | GET `/ai/preferences`<br/>(异常转译状态码) | 原 `PreferencesController.java` [L55-56] 在捕获 `IllegalArgumentException` 时返回 401（未登录文案），而写操作捕获同类型异常返回 400。 | A/B 完全保留现状 | 无凭证直接调用 GET `/ai/preferences` | 属于既有契约边界，忠实保留 |
| ISSUE-03 | DELETE `/ai/trips/{tripId}`<br/>(物理级联删除) | 原系统未提供回收站/软删除机制，一旦验证 `expectedVersion` 成功，物理直接删除 `trip`、`trip_version` 与 `trip_idempotency`。 | A/B 完全保留现状 | DELETE 请求成功后查询数据库对应主键记录均被物理抹去 | 属于既有 Phase 4 契约，原样保留 |
| ISSUE-04 | POST `/ai/planner/v1/sessions/{sessionId}/save`<br/>(匿名会话转正限制) | 匿名用户调用 `/save` 时返回 401“请先登录后保存行程”，草稿不会丢失，需带 Token 在登录后由同一用户调用 `/open` 重新绑定。 | A/B 完全保留现状 | 匿名用户创建会话后直接调用 `/save` | 属于既有鉴权契约，原样保留 |
| ISSUE-05 | 管理员鉴权拦截文案微小差异<br/>(`/ai/rag/*` vs `/ai/knowledge/*`) | 原工程中 `RagController` 的鉴权拦截文案为 `"无管理员权限"`，而 `KnowledgeController` 与 `ImportController` 的鉴权拦截文案为 `"需要管理员权限"`。 | A/B 完全保留现状 | 分别以非管理员身份请求 `/ai/rag/stats` 与 `/ai/knowledge` 创建端点，验证文案分毫不差 | 属于切斯特顿围栏保护范畴，严格分别保留原样 |

---

## 四、 最小共享约定与架构演进记录 (Contract Drift)
- `2026-10-04 (Slice 1 - Chat 域)`: 拆分 `ChatApplicationService`，下沉 `RedisChatMemory` 至 `infrastructure.chat.memory`。
- `2026-10-04 (Slice 2 - Preferences 域)`: 抽取 `PreferencesRepositoryPort`，下沉 `PreferencesRepository`，规范化 `UserPreferencesPatch` 领域模型。
- `2026-10-04 (Slice 3 - Trip 域)`: 拆分 `TripApplicationService`，抽象 `TripRepositoryPort` 与 `TripPlanPdfExportPort`，下沉仓储持久化与 PDF 手册生成。
- `2026-10-04 (Slice 4 - Planner 域)`: 迁移 `PlannerController` 至 `adapter.planner.web`，抽取 `PlanningApplicationService` 门面与 8 个 Request DTO，纯化 `RouteGatewayPort` 领域端口与 `HydrationResult` 领域模型，下沉 `PlannerSessionRepository` 与高德集成套件至 `infrastructure.planner.*`。
- `2026-10-04 (Slice 5 - RAG 域)`:
  - `com.ai.guide.adapter.rag.web`: 承接 `RagController`, `KnowledgeController`, `ImportController`，纯净处理 REST 请求路由与 HTTP 参数校验。
  - `com.ai.guide.application.rag`: 新增 `RagApplicationService` 与 `KnowledgeApplicationService` 应用服务及实现，统筹检索增强、知识库管理与 Rerank 缓存编排。
  - `com.ai.guide.infrastructure.rag.qdrant`: 承接 `ProductionV2QdrantImporter`，隔离 Qdrant GRPC 外部存储集成。
  - `com.ai.guide.infrastructure.rag.amap`: 承接 `AmapWebServiceClient`，解耦高德外部 Web Service HTTP 通信。
  - 物理清理已过时的 `domain.rag.api` 目录。
  - 全工程 386 项测试全部通过（0 errors, 0 failures, 10 skipped，耗时 21.1s），新增 27 项适配层与应用层自动化测试。

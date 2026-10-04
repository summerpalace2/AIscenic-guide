# 重构状态账本 (REFACTORING_LEDGER)

> 本账本严格遵循《保持 UI 与功能一致的大规模重构流程（定稿 v2.1）》。  
> 核心原则：以 A 的代码与运行现场为唯一真理；不搞大设计先行；先 A 后 B 提取核验项；严格四分类对账；不擅自修复原有疑似缺陷。

---

## 阶段 0：基准与当前信息

| 记录项 | 填写内容 |
|---|---|
| **A 的对照基准版本** | `42d69a9` (已包含环境自适应加载与意图规则基线修复) |
| **B 的当前分支** | `refactor/cola-architecture` |
| **当前重构切片** | **Slice 7: Search 文旅多源搜索域 (控制器迁移适配层、抽象搜索应用服务、纯化POI领域端口、解耦基础设施)** |
| **当前推进状态** | **阶段 2·第六步：独立质检子智能体复核通过 (Final Passed - Ready to Commit)** |
| **全量自动化测试结果** | **401 passed / 0 failed / 0 error / 10 skipped (23.3s, BUILD SUCCESS)** |
| **最后更新时间** | 2026-10-04 20:45 |

---

## 一、 当前切片核验项清单 (Search 文旅多源搜索域)

| 编号 | 条件与操作 | A 的表现与依据 (`42d69a9`) | B 的表现 (`refactor/cola-architecture`) | 核验方式与版本 | 结果 |
|---|---|---|---|---|---|
| V-SEARCH-01 | `GET /ai/search/baidu`<br/>百度与本地文旅融合搜索 | 参数 `q`（必填）与 `limit`（默认 10）；聚合本地知识检索、高德实景 POI 与百度全网直达卡片；结果条数限制在 `[3, 15]` 之间；返回 200 "百度文旅检索成功" 与卡片列表。 | `SearchController` 迁入适配层，委派 `SearchApplicationService` 与领域服务 `BaiduSearchService`，参数默认值、卡片来源类型与成功文案 100% 一致。 | `SearchControllerTest.testSearchBaidu` + `SearchApplicationServiceImplTest.testSearchBaidu` + `BaiduSearchServiceTest` | `[已验证一致]` |
| V-SEARCH-02 | `GET /ai/search/amap`<br/>高德实景 POI 地理搜索 | 参数 `q`（必填）、`city`（默认 "重庆市"）、`types`（可选）；<br/>• `q` 为空或空白：返回 **200** "请输入搜索关键词" 与空列表（切斯特顿围栏：非 400！）；<br/>• 成功：返回 200 "高德POI检索成功" 与候选列表；<br/>• 非法：返回 200 "请输入有效搜索关键词" 与空列表；<br/>• 失败/未配置密钥：返回 **503** "高德检索暂不可用: ..."。 | 保持完全一致的 200/503 状态码映射，严格保留对空 query 响应 200 的历史契约。 | `SearchControllerTest.testSearchAmap*` + `SearchApplicationServiceImplTest.testSearchAmap*` | `[已验证一致]` |
| V-SEARCH-03 | 适配层迁移 (Adapter) | 原 `SearchController` 位于 `domain.search.api`，混合领域层代码。 | 统一迁移至 `com.ai.guide.adapter.search.web`，彻底物理删除旧 `domain.search.api` 目录。 | 架构包扫描校验 | `[明确获准的变化]` |
| V-SEARCH-04 | 应用层服务提炼 (Application) | 控制器直接编排 `BaiduSearchService` 与底层 `AmapRouteService`。 | 提取 `SearchApplicationService` 门面与 `SearchApplicationServiceImpl`，隔离 Web 请求与业务编排。 | 架构分层校验 | `[明确获准的变化]` |
| V-SEARCH-05 | 领域端口依赖倒置 (DIP) | 原 `BaiduSearchService` 直接 import 底层设施 `infrastructure.planner.amap.AmapRouteService`。 | 在 `domain.search.gateway` 建立 `PoiSearchGatewayPort` 接口，`AmapRouteService` 实现该端口，`domain.search` 对 `infrastructure` 反向依赖归零。 | 反向依赖扫描通过 | `[明确获准的变化]` |
| V-SEARCH-06 | 全量回归与测试安全基线 | 原系统无搜索域单元测试。 | 新增 11 项全覆盖自动化测试（控制器、应用层、领域服务各层单测），全工程 401 项测试 100% 绿灯（0 errors, 0 failures, 10 skipped，耗时 23.3s）。 | `mvn test` 自动化验证 | `[已验证一致]` |

---

## 二、 历史切片核验项清单

### 1. User 用户身份认证与账户鉴权域 (Slice 6)
- V-USER-01 ~ V-USER-08：4 个 REST 认证端点、切斯特顿围栏校验差异、Scrypt 验证器下沉、`LegacyPasswordVerifierPort` 领域端口依赖倒置全部通过验收（Commit `0cf2008`）。

### 2. RAG 知识检索增强域 (Slice 5)
- V-RAG-01 ~ V-RAG-13：19 个 REST 端点（检索、指标、文档生命周期、碎片分页、双清空端点）、管理员 403 文案围栏差异、Qdrant 客户端与高德客户端下沉全部通过验收（Commit `3b2179c`）。

### 3. Planner 规划引擎域 (Slice 4)
- V-PLAN-01 ~ V-PLAN-14：14 个 REST 端点、X-Plan-Session-Token 请求头自动注入、空 Body 兜底、DIP 纯净化 `RouteGatewayPort`/`HydrationResult`、高德与会话仓储下沉全部通过验收（Commit `9b6fefd`）。

### 4. Trip 行程生命周期域 (Slice 3)
- V-TRIP-01 ~ V-TRIP-12：9 大 REST 动词、401/404/409 异常映射、局部重规划数据外露、PDF 导出、CAS 并发控制、仓储与 PDF 基础设施下沉全部通过验收（Commit `2afbe66`）。

### 5. Preferences 偏好画像域 (Slice 2)
- V-PREF-01 ~ V-PREF-09：5 大 REST 动词、401 鉴权拦截、409 OCC 冲突结构、400 校验、200/201 状态码、仓储端口依赖倒置全部通过验收（Commit `bfbb2ef`）。

### 6. Chat 对话流试点 (Slice 1)
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
| ISSUE-06 | 注册与登录输入校验差异<br/>(`/register` vs `/login`) | 注册端点同时校验 `null || isBlank()`，如果为空字符串返回 400；而登录端点仅校验了 `null`，如果密码传入空字符串 `""` 则会进入 `UserService.login` 触发后续密码比对失败逻辑（返回 401）。 | A/B 完全保留现状 | 传入 `password: ""` 分别请求 `/register` 与 `/login`，观察状态码差异（400 vs 401） | 属于切斯特顿围栏保护范畴，忠实保留历史行为差异 |
| ISSUE-07 | GET `/ai/search/amap`<br/>(空查询返回200状态码) | 原 `SearchController.java` [L43-45] 在 `q == null || q.isBlank()` 时返回 `Result.success("请输入搜索关键词", List.of())`，HTTP 状态码为 200 而非 400。 | A/B 完全保留现状 | 传入空字符串请求 `/ai/search/amap` 验证响应 200 与提示文案 | 属于切斯特顿围栏保护范畴，原样保留 |

---

## 四、 最小共享约定与架构演进记录 (Contract Drift)
- `2026-10-04 (Slice 1 - Chat 域)`: 拆分 `ChatApplicationService`，下沉 `RedisChatMemory` 至 `infrastructure.chat.memory`。
- `2026-10-04 (Slice 2 - Preferences 域)`: 抽取 `PreferencesRepositoryPort`，下沉 `PreferencesRepository`，规范化 `UserPreferencesPatch` 领域模型。
- `2026-10-04 (Slice 3 - Trip 域)`: 拆分 `TripApplicationService`，抽象 `TripRepositoryPort` 与 `TripPlanPdfExportPort`，下沉仓储持久化与 PDF 手册生成。
- `2026-10-04 (Slice 4 - Planner 域)`: 迁移 `PlannerController` 至 `adapter.planner.web`，抽取 `PlanningApplicationService` 门面与 8 个 Request DTO，纯化 `RouteGatewayPort` 领域端口与 `HydrationResult` 领域模型，下沉 `PlannerSessionRepository` 与高德集成套件至 `infrastructure.planner.*`。
- `2026-10-04 (Slice 5 - RAG 域)`: 迁移 `RagController`, `KnowledgeController`, `ImportController` 至 `adapter.rag.web`，新增 `RagApplicationService` 与 `KnowledgeApplicationService` 应用服务，下沉 `ProductionV2QdrantImporter` 与 `AmapWebServiceClient` 至 `infrastructure.rag.*`，物理清理旧 api 包。
- `2026-10-04 (Slice 6 - User 域)`:
  - `com.ai.guide.adapter.user.web`: 承接 `AuthController` 与 `UserProfileController`，处理 `/ai/auth/register`, `/ai/auth/login`, `/ai/auth/logout`, `/ai/auth/me` 请求。
  - `com.ai.guide.application.user`: 抽象 `UserApplicationService` 门面与 `UserApplicationServiceImpl`，统筹注册、账密鉴权、JWT 签发注销与个人资料读取。
  - `com.ai.guide.domain.user.gateway`: 定义 `LegacyPasswordVerifierPort` 领域端口，确保领域层无任何对基础设施层的反向 import。
  - `com.ai.guide.infrastructure.user.security`: 承接 `LegacyNodeScryptVerifier`（实现 `LegacyPasswordVerifierPort`），封装底层 BouncyCastle Scrypt 加密技术细节。
  - 物理清理旧 `domain.user.api` 与 `domain.user.security` 目录。
  - 全工程 390 项测试全部绿灯通过（0 errors, 0 failures, 10 skipped，耗时 21.3s），新增 8 项控制器与应用服务自动化用例。
  - 红方独立质检审查员现场比对与二次复核，判定最终 PASS（准予合并）。
- `2026-10-04 (Slice 7 - Search 域)`:
  - `com.ai.guide.adapter.search.web`: 承接 `SearchController`，处理 `/ai/search/baidu` 与 `/ai/search/amap` 检索请求。
  - `com.ai.guide.application.search`: 抽象 `SearchApplicationService` 门面及 `SearchApplicationServiceImpl`，编排本地知识库、高德POI与百度搜索聚合。
  - `com.ai.guide.domain.search.gateway`: 定义 `PoiSearchGatewayPort` 领域端口，`AmapRouteService` 实现该端口，彻底消除 `domain.search` 对基础设施层的依赖。
  - 物理清理旧 `domain.search.api` 目录。
  - 全工程 401 项测试全部绿灯通过（0 errors, 0 failures, 10 skipped，耗时 23.3s），新增 11 项全覆盖自动化测试。



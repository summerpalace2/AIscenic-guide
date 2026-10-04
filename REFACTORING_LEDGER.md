# 重构状态账本 (REFACTORING_LEDGER)

> 本账本严格遵循《保持 UI 与功能一致的大规模重构流程（定稿 v2.1）》。  
> 核心原则：以 A 的代码与运行现场为唯一真理；不搞大设计先行；先 A 后 B 提取核验项；严格四分类对账；不擅自修复原有疑似缺陷。

---

## 阶段 0：基准与当前信息

| 记录项 | 填写内容 |
|---|---|
| **A 的对照基准版本** | `42d69a9` (已包含环境自适应加载与意图规则基线修复) |
| **B 的当前分支** | `refactor/cola-architecture` |
| **当前重构切片** | **Slice 10: Memory 旅行记忆与偏好沉淀域 (记忆控制器迁移至适配层、抽象旅行记忆应用服务、纯化用例编排、物理清理旧 api 包)** |
| **当前推进状态** | **阶段 2·第六步：独立质检子智能体复核通过 (Final Passed - Ready to Commit)** |
| **全量自动化测试结果** | **454 passed / 0 failed / 0 error / 10 skipped (21.1s, BUILD SUCCESS)** |
| **最后更新时间** | 2026-10-04 21:12 |

---

## 一、 当前切片核验项清单 (Memory 旅行记忆与偏好沉淀域)

| 编号 | 条件与操作 | A 的表现与依据 (`42d69a9`) | B 的表现 (`refactor/cola-architecture`) | 核验方式与版本 | 结果 |
|---|---|---|---|---|---|
| V-MEM-01 | `GET /ai/memories`<br/>已确认记忆列表 | 已登录返回 200 与记忆列表（7 天 TTL）；匿名请求拦截返回 401 "请先登录后查看旅行记忆"。 | `TravelMemoryController` 迁入 `adapter.memory.web`，委派应用服务，状态码与文案完全一致。 | `TravelMemoryControllerTest.testList*` + `TravelMemoryApplicationServiceImplTest.testList` | `[已验证一致]` |
| V-MEM-02 | `GET /ai/memories/candidates`<br/>待确认记忆建议列表 | 已登录返回 200 与 MemoryCandidate 列表；匿名请求拦截返回 401 "请先登录后查看记忆建议"。 | 委派应用服务，状态码与文案完全一致。 | `TravelMemoryControllerTest.testCandidates*` + `TravelMemoryApplicationServiceImplTest.testCandidates` | `[已验证一致]` |
| V-MEM-03 | `POST /ai/memories/candidate`<br/>单句记忆即时提取推荐 | 切斯特顿围栏：**无需登录即可使用**；提取 message 入参；调用 fast-path 规则或 smart-path LLM；返回 200 与 Candidate 对象。 | 保持免登开放契约，入参提取与返回完全一致。 | `TravelMemoryControllerTest.testCandidateSuggest_NoLoginRequired` + `TravelMemoryApplicationServiceImplTest.testSuggest` | `[已验证一致]` |
| V-MEM-04 | `POST /ai/memories/observations`<br/>后台记忆线索捕获 | 已登录接收 message 与 sessionId，入队并返回 HTTP 202 "记忆线索已进入后台整理队列"；参数异常返回 400；匿名拦截返回 401 "请先登录后使用旅行记忆"。 | 严格保持 HTTP 202 异步接收语义、异常 400 与 401 鉴权文案。 | `TravelMemoryControllerTest.testObserve*` + `TravelMemoryApplicationServiceImplTest.testCaptureObservation` | `[已验证一致]` |
| V-MEM-05 | `POST /ai/memories`<br/>显式新增已确认记忆 | 接收 content, category (默认 CUSTOM), sourceType (默认 CHAT_CONFIRMED), sourceRef；已登录返回 HTTP 201 "旅行记忆已保存"；参数异常 400；匿名拦截 401 "请先登录后保存旅行记忆"。 | 严格保持 HTTP 201 创建状态码、参数默认值、异常 400 与 401 鉴权文案。 | `TravelMemoryControllerTest.testConfirm*` + `TravelMemoryApplicationServiceImplTest.testConfirm` | `[已验证一致]` |
| V-MEM-06 | `PATCH /ai/memories/{id}`<br/>更新已确认记忆 | 接收 content, category；已登录返回 200 "旅行记忆已更新"；参数异常 400；匿名拦截 401 "请先登录后编辑旅行记忆"。 | 严格保持 PATCH 动词、更新行为、异常 400 与 401 鉴权文案。 | `TravelMemoryControllerTest.testUpdate*` + `TravelMemoryApplicationServiceImplTest.testUpdate` | `[已验证一致]` |
| V-MEM-07 | `DELETE /ai/memories/{id}`<br/>删除旅行记忆 | 已登录调用 delete 并返回 200 "旅行记忆已删除"；匿名拦截 401 "请先登录后删除旅行记忆"。 | 严格保持 DELETE 动词、删除调用与 401 鉴权文案。 | `TravelMemoryControllerTest.testDelete*` + `TravelMemoryApplicationServiceImplTest.testDelete` | `[已验证一致]` |
| V-MEM-08 | `POST /ai/memories/candidates/{id}/(confirm\|dismiss)`<br/>建议确认与忽略 | 确认：已登录返回 200 "旅行记忆已确认"（参数异常 400，匿名 401 "请先登录后确认记忆建议"）；<br/>忽略：已登录返回 200 "记忆建议已忽略"（匿名 401 "请先登录后忽略记忆建议"）。 | 严格区分确认与忽略两个端点操作、状态码与细微提示文案。 | `TravelMemoryControllerTest.test*Candidate*` + `TravelMemoryApplicationServiceImplTest.test*Candidate*` | `[已验证一致]` |
| V-MEM-09 | `(GET\|POST) /ai/memories/prepend-prompt`<br/>前置提示词管理 | GET：查看前置提示词，匿名 401 "请先登录后查看旅行偏好提示词"；<br/>POST：保存或重生成前置提示词（action=resynthesize 返回 200 "偏好提示词已重新生成"，默认 save 返回 200 "旅行偏好提示词已保存"；匿名 401 "请先登录后保存旅行偏好提示词"）。 | 保持完全一致的 action 分支判断、返回值 Map key（prependPrompt）与提示文案。 | `TravelMemoryControllerTest.test*PrependPrompt*` + `TravelMemoryApplicationServiceImplTest.testPromptMethods` | `[已验证一致]` |
| V-MEM-10 | 适配层迁移 (Adapter) | 原 `TravelMemoryController` 位于 `domain.memory.api`，混合领域代码。 | 统一迁移至 `com.ai.guide.adapter.memory.web`，彻底物理删除旧 `domain.memory.api` 目录。 | 架构包扫描核验 | `[明确获准的变化]` |
| V-MEM-11 | 应用层服务提炼 (Application) | 控制器直接依赖领域服务 `TravelMemoryService` 暴露领域实现细节。 | 抽象 `TravelMemoryApplicationService` 门面及 `TravelMemoryApplicationServiceImpl`，隔离 Web 层与 Domain 核心。 | 架构分层校验 | `[明确获准的变化]` |
| V-MEM-12 | 全量回归与测试安全基线 | 确保既有 7 天记忆淘汰定时任务、规则直通车、大模型提炼等核心逻辑全量通过。 | 全工程 454 项测试全部绿灯通过（0 errors, 0 failures, 10 skipped，耗时 21.1s），新增 29 项端点与应用层测试。 | `mvn test` 自动化验证 | `[已验证一致]` |

---

## 二、 历史切片核验项清单

### 1. Voice 语音识别与合成域 (Slice 9)
- V-VOICE-01 ~ V-VOICE-06：ASR 与 TTS 双端点、Base64 防御转译、dev_pid 与 voice 白名单、BaiduVoiceClient 基础设施下沉全部通过验收（Commit `9feb197`）。

### 2. Attraction 文旅地标与百科域 (Slice 8)
- V-ATTR-01 ~ V-ATTR-06：多条件景点列表、受控与动态详情分支路由、跨域事实投影与匿名登录态隔离、应用层编排全部通过验收（Commit `c9e4515`）。

### 2. Search 文旅多源综合搜索域 (Slice 7)
- V-SEARCH-01 ~ V-SEARCH-06：百度与高德搜索 2 大端点、空查询 200 响应围栏、`PoiSearchGatewayPort` 领域网关解耦高德路线服务、应用层编排全部通过验收（Commit `4c8d93c`）。

### 2. User 用户身份认证与账户鉴权域 (Slice 6)
- V-USER-01 ~ V-USER-08：4 个 REST 认证端点、切斯特顿围栏校验差异、Scrypt 验证器下沉、`LegacyPasswordVerifierPort` 领域端口依赖倒置全部通过验收（Commit `0cf2008`）。

### 3. RAG 知识检索增强域 (Slice 5)
- V-RAG-01 ~ V-RAG-13：19 个 REST 端点（检索、指标、文档生命周期、碎片分页、双清空端点）、管理员 403 文案围栏差异、Qdrant 客户端与高德客户端下沉全部通过验收（Commit `3b2179c`）。

### 4. Planner 规划引擎域 (Slice 4)
- V-PLAN-01 ~ V-PLAN-14：14 个 REST 端点、X-Plan-Session-Token 请求头自动注入、空 Body 兜底、DIP 纯净化 `RouteGatewayPort`/`HydrationResult`、高德与会话仓储下沉全部通过验收（Commit `9b6fefd`）。

### 5. Trip 行程生命周期域 (Slice 3)
- V-TRIP-01 ~ V-TRIP-12：9 大 REST 动词、401/404/409 异常映射、局部重规划数据外露、PDF 导出、CAS 并发控制、仓储与 PDF 基础设施下沉全部通过验收（Commit `2afbe66`）。

### 6. Preferences 偏好画像域 (Slice 2)
- V-PREF-01 ~ V-PREF-09：5 大 REST 动词、401 鉴权拦截、409 OCC 冲突结构、400 校验、200/201 状态码、仓储端口依赖倒置全部通过验收（Commit `bfbb2ef`）。

### 7. Chat 对话流试点 (Slice 1)
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
| ISSUE-08 | POST `/ai/asr`<br/>(非法 Base64 引发 500) | 原 `AsrController.java` [L63] 未防御 `Base64.decode` 抛出 `IllegalArgumentException`，异常落入兜底 catch 返回 500。 | B 防御性捕获返回 400，文案借用百度 3312 的 `"音频格式参数异常，请重新录制"` | 传入非法 Base64 请求 `/ai/asr` | 属于防御性参数改进，记录差异 |
| ISSUE-09 | POST `/ai/asr`<br/>(非法 dev_pid 静默回退) | 原 `AsrController.java` [L110-119] 遇到非法语种代码（如 `999`）时静默回退默认值 `1537`，不返回错误码。 | A/B 完全保留现状 | 传入 `dev_pid: "999"` 请求 `/ai/asr` | 属于既有业务逻辑，原样保留 |
| ISSUE-10 | POST `/ai/tts`<br/>(固定音频采样与音量参数) | 原 `TtsController.java` [L60] 将 `spd=5&pit=5&vol=5&aue=3` 硬编码在百度 URL 中，未暴露给调用方动态调节。 | A/B 完全保留现状 | 传入不同参数验证百度合成 URL 生成行为 | 属于原有业务设计，原样保留 |

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
- `2026-10-04 (Slice 8 - Attraction 域)`:
  - `com.ai.guide.adapter.attraction.web`: 承接 `AttractionController`，处理 `/ai/attractions`, `/ai/attractions/{id}`, `/ai/attractions/{id}/facts` 请求。
  - `com.ai.guide.application.attraction`: 抽象 `AttractionApplicationService` 门面及 `AttractionApplicationServiceImpl`，收拢受控地标多条件查询、富媒体装配与跨域事实核验投影用例。
  - 物理清理旧 `domain.attraction.api` 目录。
  - 全工程 411 项测试全部绿灯通过（0 errors, 0 failures, 10 skipped，耗时 21.1s），新增 10 项端点与应用层自动化测试。
- `2026-10-04 (Slice 9 - Voice 域)`:
  - `com.ai.guide.adapter.voice.web`: 承接 `AsrController` 与 `TtsController`，处理 `/ai/asr` 音频识别与 `/ai/tts` 语音合成流式响应。
  - `com.ai.guide.application.voice`: 抽象 `VoiceApplicationService` 门面及 `VoiceApplicationServiceImpl`，统筹参数白名单校验、百度错误码映射与语音服务编排。
  - `com.ai.guide.domain.voice.gateway`: 抽象 `VoiceGatewayPort` 领域端口，确保领域层无反向依赖。
  - `com.ai.guide.infrastructure.voice.baidu`: 承接 `BaiduVoiceClient`（实现 `VoiceGatewayPort`），封装百度 Token 自动刷新与 API 调用技术细节。
  - 物理清理旧 `domain.voice.api` 目录。
  - 全工程 425 项测试全部绿灯通过（0 errors, 0 failures, 10 skipped，耗时 23.1s），新增 13 项单元与端点覆盖测试。
  - 红方独立质检审查员现场比对复核：纠正账本文档契约漂移，确认代码实现忠实一致，判定准予放行 (CONDITIONAL PASS)。
- `2026-10-04 (Slice 10 - Memory 域)`:
  - `com.ai.guide.adapter.memory.web`: 承接 `TravelMemoryController`，映射 11 大记忆生命周期端点（列表、建议、即时提取、线索收集、确认、编辑、删除、前置提示词管理）。
  - `com.ai.guide.application.memory`: 抽象 `TravelMemoryApplicationService` 门面及 `TravelMemoryApplicationServiceImpl`，隔离 Web 请求与底层记忆规则/LLM/仓储服务。
  - 严格保持切斯特顿围栏：单句记忆即时提取 `/ai/memories/candidate` 无需登录；后台线索收集返回 HTTP 202；显式确认返回 HTTP 201。
  - 物理清理旧 `domain.memory.api` 目录。
  - 全工程 454 项测试全部绿灯通过（0 errors, 0 failures, 10 skipped，耗时 21.1s），新增 29 项单元与端点覆盖测试。



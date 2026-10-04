# 重构状态账本 (REFACTORING_LEDGER)

> 本账本严格遵循《保持 UI 与功能一致的大规模重构流程（定稿 v2.1）》。  
> 核心原则：以 A 的代码与运行现场为唯一真理；不搞大设计先行；先 A 后 B 提取核验项；严格四分类对账；不擅自修复原有疑似缺陷。

---

## 阶段 0：基准与当前信息

| 记录项 | 填写内容 |
|---|---|
| **A 的对照基准版本** | `42d69a9` (已包含环境自适应加载与意图规则基线修复) |
| **B 的当前分支** | `refactor/cola-architecture` |
| **当前重构切片** | **Slice 4: Planner 规划引擎域 (适配层迁移、应用层编排、路线网关端口纯净化与高德/仓储基础设施下沉)** |
| **当前推进状态** | **阶段 2·第五步：独立质检子智能体验收通过 (APPROVED)，准予集成提交** |
| **独立审查结论** | **0 回归差异、0 尚未核实、10 项已验证一致、4 项明确获准的变化 (APPROVED)** |
| **最后更新时间** | 2026-10-04 18:48 |

---

## 一、 当前切片核验项清单 (Planner 规划引擎域)

| 编号 | 条件与操作 | A 的表现与依据 (`42d69a9`) | B 的表现 (`refactor/cola-architecture`) | 核验方式与版本 | 结果 |
|---|---|---|---|---|---|
| V-PLAN-01 | `POST /ai/planner/v1/plan`<br/>多天初始行程排程 | 接收自由 prompt、天数、同行人等；支持 `X-Plan-Session-Token` 请求头提取；请求体为 null 时自动回退默认对象并补全 token；调用应用服务并返回包含会话状态的 ResponseEntity。 | `PlannerController` 迁移至适配层，保留完全相同的 token 回退与默认请求补全逻辑，委派 `PlanningApplicationService`。 | `PlannerControllerTest.planDelegatesToApplicationServiceAndInjectsToken` | `[已验证一致]` |
| V-PLAN-02 | `POST /ai/planner/v1/shadow` 系列<br/>无状态候选规划与编辑 | 包含 `/shadow`, `/shadow/replan`, `/shadow/stops`；专供前端 Shadow 流程使用，绝不产生持久化 Session 或 formal Trip。 | 控制器端点路径、入参 RequestBody 与委派方法完全一致。 | `PlannerControllerTest.shadow*` | `[已验证一致]` |
| V-PLAN-03 | `POST /ai/planner/v1/trips/{tripId}/open`<br/>正式行程反向解构为草稿 | 校验登录态；将已固化的 Trip 解构并在规划引擎中重新载入草稿工作区。 | 路径映射与异常状态码完全一致。 | `PlannerControllerTest.openFormalTripDelegatesToApplicationService` | `[已验证一致]` |
| V-PLAN-04 | `GET /ai/planner/v1/sessions` 与 `GET /sessions/{sessionId}` | 查询当前用户会话列表或指定会话详情；路径参数与 `X-Plan-Session-Token` 透传鉴权；支持匿名能力凭证校验。 | 保持完全一致的路径契约与 Header 读取。 | `PlannerControllerTest.getSessionPassesToken` | `[已验证一致]` |
| V-PLAN-05 | `POST /ai/planner/v1/sessions/{sessionId}/dynamic-refresh` | 触发指定会话的高德路线/天气/POI水化刷新；透传 Session Token。 | 保持完全一致的路径契约与 Header 读取。 | `PlannerControllerTest.refreshDynamicDataPassesToken` | `[已验证一致]` |
| V-PLAN-06 | `POST /sessions/{sessionId}/replan` 与 `/stops` | 针对具体某天某景点的局部重规划或原子增删替换；请求体为 null 时自动初始化并补全 `sessionAccessToken`；触发 CAS 乐观锁版本并发控制。 | 保持完全一致的空请求体防御与 token 回退逻辑。 | `PlannerControllerTest.replanInjectsTokenWhenNull` + `stopsInjectsTokenWhenNull` | `[已验证一致]` |
| V-PLAN-07 | `POST /ai/planner/v1/sessions/{sessionId}/save` | 将满意规划草稿转正持久化为正式行程 (Trip)。 | 透传 token，委派保存用例。 | `PlannerControllerTest.saveDelegatesToApplicationService` | `[已验证一致]` |
| V-PLAN-08 | `POST /sessions/{sessionId}/conversation`, `/adjust/preview`, `/adjust/apply` | 对话式意图提取、1~3 个调整方案预览与草稿确认升版；透传 Header Token。 | 保持完全一致的端点、入参 DTO 与应用服务编排。 | `PlannerControllerTest.converse*`, `previewAdjustment*`, `applyAdjustment*` | `[已验证一致]` |
| V-PLAN-09 | 适配层重构 (Adapter) | 原 `PlannerController` 位于 `domain.planner.api`，混合领域层代码。 | `PlannerController` 迁移至 `com.ai.guide.adapter.planner.web`，彻底删除旧 api 包。 | 架构包扫描校验 | `[明确获准的变化]` |
| V-PLAN-10 | 应用层门面与用例编排 (Application) | 领域层服务直接作为门面，接口与 DTO 散落在领域层。 | 抽取 `com.ai.guide.application.planner.PlanningApplicationService` 门面接口，由 `PlannerApplicationService` 统筹编排用例；8 个 Request DTO 迁入 `application.planner.dto`。 | 架构分层校验 | `[明确获准的变化]` |
| V-PLAN-11 | 路线网关端口纯净化 (DIP) | 原 `RouteGatewayPort.java:13` 直接依赖实现类 `AmapPlannerGateway.HydrationResult`，存在典型依赖倒置反例。 | 提取纯领域模型 `com.ai.guide.domain.planner.model.HydrationResult`，使领域网关接口 `RouteGatewayPort` 完全纯净。 | 依赖图检查通过 | `[明确获准的变化]` |
| V-PLAN-12 | 规划会话仓储端口纯净化与下沉 | 原 `PlannerSessionRepositoryPort` 依赖具体实现类中的内部 `StoredSession`、`CreateResult`、`MutationResult`。 | 规范化 `domain.planner.gateway.PlannerSessionRepositoryPort` 端口契约；具体 JDBC 持久化实现 `PlannerSessionRepository` 下沉至 `infrastructure.planner.repository`。 | 依赖图检查通过 | `[明确获准的变化]` |
| V-PLAN-13 | 高德地图第三方集成下沉 | `AmapPlannerGateway`, `AmapRouteService`, `AmapRequestCache`, `AmapPlannerConfig` 混杂在领域层。 | 统一下沉至 `com.ai.guide.infrastructure.planner.amap`，彻底消除领域层对外部高德 Web Service 的直接技术耦合。 | 依赖图检查通过 | `[明确获准的变化]` |
| V-PLAN-14 | 测试套件与回归安全核验 | 保证全量单元测试与规划领域算法全绿通过。 | 规划引擎相关 197 个测试 100% 绿灯（含新增 15 个 Controller 适配器测试）；全工程 353 个测试全部绿灯通过（0 errors, 0 failures, 10 skipped）。 | `mvn test` 自动化验证 | `[已验证一致]` |

---

## 二、 历史切片核验项清单

### 1. Trip 行程生命周期域 (Slice 3)
- V-TRIP-01 ~ V-TRIP-12：9 大 REST 动词、401/404/409 异常映射、局部重规划数据外露、PDF 导出、CAS 并发控制、仓储与 PDF 基础设施下沉全部通过验收（Commit `2afbe66`）。

### 2. Preferences 偏好画像域 (Slice 2)
- V-PREF-01 ~ V-PREF-09：5 大 REST 动词、401 鉴权拦截、409 OCC 冲突结构、400 校验、200/201 状态码、仓储端口依赖倒置全部通过验收（Commit `bfbb2ef`）。

### 3. Chat 对话流试点 (Slice 1)
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

---

## 四、 最小共享约定与架构演进记录 (Contract Drift)
- `2026-10-04 (Slice 1 - Chat 域)`: 拆分 `ChatApplicationService`，下沉 `RedisChatMemory` 至 `infrastructure.chat.memory`。
- `2026-10-04 (Slice 2 - Preferences 域)`: 抽取 `PreferencesRepositoryPort`，下沉 `PreferencesRepository`，规范化 `UserPreferencesPatch` 领域模型。
- `2026-10-04 (Slice 3 - Trip 域)`: 拆分 `TripApplicationService`，抽象 `TripRepositoryPort` 与 `TripPlanPdfExportPort`，下沉仓储持久化与 PDF 手册生成。
- `2026-10-04 (Slice 4 - Planner 域)`:
  - `com.ai.guide.adapter.planner.web`: 承接 `PlannerController`，纯净处理 14 个 REST 端点、X-Plan-Session-Token 请求头提取与 HTTP 状态码映射。
  - `com.ai.guide.application.planner`: 新增 `PlanningApplicationService` 门面与 `PlannerApplicationService` 实现，统筹多天规划、草稿会话、局部重排与方案提案用例；DTO 迁入 `dto` 子包。
  - `com.ai.guide.domain.planner.gateway`: 抽象 `RouteGatewayPort` 与 `PlannerSessionRepositoryPort`，彻底消除对高德 SDK 及具体持久化类的反向依赖。
  - `com.ai.guide.domain.planner.model`: 提取 `HydrationResult` 纯领域结果记录。
  - `com.ai.guide.infrastructure.planner.repository`: 承接 `PlannerSessionRepository`，维护规划草稿多版本快照与 SQLite 持久化。
  - `com.ai.guide.infrastructure.planner.amap`: 承接 `AmapPlannerGateway`, `AmapRouteService`, `AmapRequestCache`, `AmapPlannerConfig`，封装高德第三方地图 API 与并发防抖。
  - 全量 353 个单元测试全部绿灯通过（22.775s），新增 15 个控制器自动化用例。

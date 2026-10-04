# 重构状态账本 (REFACTORING_LEDGER)

> 本账本严格遵循《保持 UI 与功能一致的大规模重构流程（定稿 v2.1）》。  
> 核心原则：以 A 的代码与运行现场为唯一真理；不搞大设计先行；先 A 后 B 提取核验项；严格四分类对账；不擅自修复原有疑似缺陷。

---

## 阶段 0：基准与当前信息

| 记录项 | 填写内容 |
|---|---|
| **A 的对照基准版本** | `42d69a9` (已包含环境自适应加载与意图规则基线修复) |
| **B 的当前分支** | `refactor/cola-architecture` |
| **当前重构切片** | **Slice 3: Trip 行程生命周期域 (适配层瘦身、应用层用例编排、PDF渲染与仓储持久化下沉)** |
| **当前推进状态** | **阶段 2·第五步：独立质检子智能体验收通过 (APPROVED)，准予集成提交** |
| **独立审查结论** | **0 回归差异、0 尚未核实、14 已验证一致、5 明确获准的变化** |
| **最后更新时间** | 2026-10-04 17:38 |

---

## 一、 当前切片核验项清单 (Trip 行程生命周期域)

| 编号 | 条件与操作 | A 的表现与依据 (`42d69a9`) | B 的表现 (`refactor/cola-architecture`) | 核验方式与版本 | 结果 |
|---|---|---|---|---|---|
| V-TRIP-01 | `GET /ai/trips`<br/>查询用户行程列表 | 校验登录态；未登录返回 401 及“请先登录后查询行程”；成功返回当前 owner 的全部行程列表。 | `TripController` 委派 `TripApplicationService.listTrips`，401 拦截文案与返回结构 100% 对齐。 | `TripControllerTest.listIsScopedToCurrentOwner` | `[已验证一致]` |
| V-TRIP-02 | `POST /ai/trips`<br/>创建新行程与版本 1 | 校验登录态；未登录返回 401 及“请先登录后保存行程”；支持 `idempotencyKey` 幂等创建与版本 1 初始化。 | `TripController` 委派应用服务，成功返回 201 状态码与“行程已保存”提示。 | MockMvc 自动化断言 | `[已验证一致]` |
| V-TRIP-03 | `GET /ai/trips/{tripId}`<br/>单条行程快照查询 | 校验登录态；行程不存在返回 404 及“行程不存在”；成功返回当前 owner 拥有的 Trip 实体。 | 保持严格的 owner 隔离，404 与成功数据结构一致。 | MockMvc 自动化断言 | `[已验证一致]` |
| V-TRIP-04 | `PUT /ai/trips/{tripId}`<br/>行程更新与 CAS 并发 | 校验登录态；支持 `expectedVersion` 校验；版本冲突时返回 409 及 `{tripId, expectedVersion, currentVersion, operationStatus}`。 | 保持冲突元数据字段与 HTTP 409 状态码完全一致。 | `TripControllerTest.updateReturnsCasConflictMetadata` | `[已验证一致]` |
| V-TRIP-05 | `DELETE /ai/trips/{tripId}`<br/>物理清理 (切斯特顿围栏) | 校验登录态与 `expectedVersion`（缺少返回 400）；版本匹配时物理级联清理 trip、trip_version、trip_idempotency。 | 严格保留原有的物理级联清理既有契约，未擅自改造为软删除。 | `TripControllerTest.deleteUsesOwnerScopeAndExpectedVersion` | `[已验证一致]` |
| V-TRIP-06 | `GET /ai/trips/{tripId}/versions`<br/>历史版本列表与单版本快照 | 支持查询全量历史版本与单版本详情；未登录返回 401；不存在返回 404（“行程不存在” / “行程版本不存在”）。 | 路径映射与 404 错误提示文案保持一致。 | MockMvc 自动化断言 | `[已验证一致]` |
| V-TRIP-07 | `POST /ai/trips/{tripId}/replan`<br/>局部重规划元数据暴露 | 重规划成功时，除 Trip 实体外，在外层暴露 `replacementVenueId` 与 `changedSegments`。 | `TripController` 保留完全相同的 `successData` 条件判断与字段提取逻辑。 | `TripControllerTest.replanExposesServerSelectedReplacementMetadata` | `[已验证一致]` |
| V-TRIP-08 | `GET /ai/trips/{tripId}/export`<br/>PDF 行程手册导出 | 匿名请求返回 401.build()；不存在返回 404.build()；文件名正则清洗（非法字符转空格、trim、截断 80 字符、拼接 `-行程手册.pdf`）。 | 保持完全一致的 Content-Disposition 头、安全文件名算法与 application/pdf 字节流。 | `TripControllerTest.exportReturnsPdfWhenFound` + `TripPlanPdfServiceTest` | `[已验证一致]` |
| V-TRIP-09 | 幂等控制生命周期 | `reserveIdempotency` 预占、SHA-256 指纹比对、幂等命中重放、冲突 409、落盘 `completeIdempotency`、回滚 `deleteIdempotency`。 | 在 `TripService` 与 `TripRepository` 中原样保留全部幂等状态流转。 | `TripServiceTest` 真实数据库断言 | `[已验证一致]` |
| V-TRIP-10 | 仓储端口依赖倒置 (DIP) | 原 `TripRepository` 位于领域层直接依赖 `JdbcTemplate`。 | 在领域层抽象 `TripRepositoryPort` 纯接口；具体持久化下沉至 `infrastructure.trip.repository`。 | COLA 规则验证与依赖检查 | `[明确获准的变化]` |
| V-TRIP-11 | PDF 导出接口依赖倒置 (DIP) | 原 `TripPlanPdfService` 混在领域服务中，直接依赖 OpenPDF / iText 与 `java.awt.Color`。 | 在领域层抽象 `TripPlanPdfExportPort`；具体渲染实现下沉至 `infrastructure.trip.pdf`。 | 领域纯粹性校验通过 | `[明确获准的变化]` |
| V-TRIP-12 | 应用服务层引入 | 控制器直接依赖领域服务与具体 PDF 服务。 | 引入 `TripApplicationService` 统一编排行程生命周期用例。 | `TripApplicationServiceTest` (10/10 绿灯) | `[明确获准的变化]` |

---

## 二、 历史切片核验项清单

### 1. Preferences 偏好画像域 (Slice 2)
- V-PREF-01 ~ V-PREF-09：5 大 REST 动词、401 鉴权拦截、409 OCC 冲突结构、400 校验、200/201 状态码、仓储端口依赖倒置全部通过验收。

### 2. Chat 对话流试点 (Slice 1)
- V-CHAT-01 ~ V-CHAT-08：10 参数流式契约、槽位隔离、情绪分流、双层超时容灾、RedisChatMemory 基础设施下沉全部通过验收。

---

## 三、 疑似原有问题清单 (Suspected Legacy Issues)
> 规则：保留原行为，记录供用户调试判断，不擅自“修复”。

| 编号 | 涉及接口/模块 | 现象与存疑原因 | A/B 是否保留 | 复现与验证方式 | 用户判断与状态 |
|---|---|---|---|---|---|
| ISSUE-01 | GET `/ai/chat/structured`<br/>(结构化对话槽位提取) | 原 `A_ChatController.java` [L569] 在调用 `slotTrackingService.extractAndSave(userId, message)` 时未像流式接口那样进行 `userId != null` 防御性校验，匿名用户会落库 `user:null:slots`。 | A/B 完全保留现状 | 匿名请求 `/ai/chat/structured` 观察 Redis key `user:null:slots` | 待用户决策是否在后续画像域统一治理 |
| ISSUE-02 | GET `/ai/preferences`<br/>(异常转译状态码) | 原 `PreferencesController.java` [L55-56] 在捕获 `IllegalArgumentException` 时返回 401（未登录文案），而写操作捕获同类型异常返回 400。 | A/B 完全保留现状 | 无凭证直接调用 GET `/ai/preferences` | 属于既有契约边界，忠实保留 |
| ISSUE-03 | DELETE `/ai/trips/{tripId}`<br/>(物理级联删除) | 原系统未提供回收站/软删除机制，一旦验证 `expectedVersion` 成功，物理直接删除 `trip`、`trip_version` 与 `trip_idempotency`。 | A/B 完全保留现状 | DELETE 请求成功后查询数据库对应主键记录均被物理抹去 | 属于既有 Phase 4 契约，原样保留 |

---

## 四、 最小共享约定与架构演进记录 (Contract Drift)
- `2026-10-04 (Slice 1 - Chat 域)`: 拆分 `ChatApplicationService`，下沉 `RedisChatMemory` 至 `infrastructure.chat.memory`。
- `2026-10-04 (Slice 2 - Preferences 域)`: 抽取 `PreferencesRepositoryPort`，下沉 `PreferencesRepository`，规范化 `UserPreferencesPatch` 领域模型。
- `2026-10-04 (Slice 3 - Trip 域)`:
  - `com.ai.guide.adapter.trip.web`: 承接 `TripController`，纯净处理 9 大 REST 端点、鉴权拦截与 HTTP 响应状态码映射。
  - `com.ai.guide.application.trip`: 新增 `TripApplicationService`，统筹行程全生命周期用例与报表导出调度。
  - `com.ai.guide.domain.trip.gateway`: 抽象 `TripRepositoryPort` 与 `TripPlanPdfExportPort`，彻底剥离领域层中的关系数据库与报表排版中间件依赖。
  - `com.ai.guide.infrastructure.trip.repository`: 承接 `TripRepository`，实现原子多版本快照与幂等性 CAS 持久化。
  - `com.ai.guide.infrastructure.trip.pdf`: 承接 `TripPlanPdfService`，封装 OpenPDF / iText 底层排版引擎。
  - 全量 338 个单元测试全部绿灯通过（20.924s）。
  - 独立审查子智能体（`aeb13f44-b4ac-42b9-99d3-230e943251a5`）出具 PASS 裁决报告。

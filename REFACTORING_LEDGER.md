# 重构状态账本 (REFACTORING_LEDGER)

> 本账本严格遵循《保持 UI 与功能一致的大规模重构流程（定稿 v2.1）》。  
> 核心原则：以 A 的代码与运行现场为唯一真理；不搞大设计先行；先 A 后 B 提取核验项；严格四分类对账；不擅自修复原有疑似缺陷。

---

## 阶段 0：基准与当前信息

| 记录项 | 填写内容 |
|---|---|
| **A 的对照基准版本** | `42d69a9` (已包含环境自适应加载与意图规则基线修复) |
| **B 的当前分支** | `refactor/cola-architecture` |
| **当前重构切片** | **Slice 6: User 用户认证与鉴权域 (认证控制器迁移至适配层、抽象用户应用服务、纯化密码校验端口、下沉Scrypt加密验证器至基础设施层)** |
| **当前推进状态** | **阶段 2·第六步：独立质检子智能体二次复核通过 (Final Passed - Ready to Commit)** |
| **全量自动化测试结果** | **390 passed / 0 failed / 0 error / 10 skipped (21.3s, BUILD SUCCESS)** |
| **最后更新时间** | 2026-10-04 20:35 |

---

## 一、 当前切片核验项清单 (User 用户认证与鉴权域)

| 编号 | 条件与操作 | A 的表现与依据 (`42d69a9`) | B 的表现 (`refactor/cola-architecture`) | 核验方式与版本 | 结果 |
|---|---|---|---|---|---|
| V-USER-01 | `POST /ai/auth/register`<br/>新用户注册与凭证签发 | 校验 body 判空、username 与 password 非空且非 blank（返回 400 "用户名和密码不能为空"）；唯一性冲突抛 `IllegalArgumentException` 返回 400；成功返回 200 "注册成功" 及包含 userId/username/token/role 的 Map；异常捕获返回 500 "注册失败，请稍后重试"。 | `AuthController` 迁入适配层，委派 `UserApplicationService`，参数判空、400 校验异常文案及 500 异常回退完全一致。 | `AuthControllerTest.testRegister*` + `UserApplicationServiceImplTest.testRegister*` | `[已验证一致]` |
| V-USER-02 | `POST /ai/auth/login`<br/>用户登录鉴权与哈希升级 | 校验 body 判空、username 与 password 非空（返回 400 "用户名和密码不能为空"；注：原代码对登录仅判 null，注册判 null 或 blank，严格保留）；账密错误/用户不存在抛出异常时返回 **401**；成功返回 200 "登录成功"；内部异常返回 500。 | 保持完全一致的 400/401/500 状态码映射，保留对 `IllegalArgumentException` 映射 401 的切斯特顿围栏。 | `AuthControllerTest.testLogin*` + `UserApplicationServiceImplTest.testLogin*` | `[已验证一致]` |
| V-USER-03 | `POST /ai/auth/logout`<br/>登出与 JWT 令牌注销 | 提取 `Authorization` 请求头；缺失或非 `Bearer ` 前缀返回 401 "请提供有效的登录凭据"；Token 解析失败返回 401 "登录凭据无效或已过期"；黑名单写入失败返回 503 "退出登录暂不可用，请稍后重试"；成功返回 200 "退出成功"。 | 保持完全一致的 401、503、200 状态码映射与微观提示文案。 | `AuthControllerTest.testLogout*` + `UserApplicationServiceImplTest.testLogout*` | `[已验证一致]` |
| V-USER-04 | `GET /ai/auth/me`<br/>当前登录用户信息查询 | 校验 `UserContext.isAnonymous()`，未登录返回 401 "未登录"；已登录调用 `userService.getUserById(userId)` 返回 200 "查询成功"；异常捕获返回 500 "查询失败，请稍后重试"。 | `UserProfileController` 迁入适配层，委派 `UserApplicationService`，保持完全相同的 401/200/500 逻辑。 | `UserProfileControllerTest.testGetCurrentUser*` + `UserApplicationServiceImplTest.testGetCurrentUser*` | `[已验证一致]` |
| V-USER-05 | 适配层重构 (Adapter) | 原 `AuthController` 与 `UserProfileController` 位于 `domain.user.api`，混合领域层代码。 | 统一迁移至 `com.ai.guide.adapter.user.web`，彻底物理删除旧 `domain.user.api` 目录。 | 架构包扫描校验 | `[明确获准的变化]` |
| V-USER-06 | 应用层服务提炼 (Application) | 领域层 `UserService` 直接被 Controller 与外部过滤器穿透调用。 | 提取 `UserApplicationService` 门面接口及实现类 `UserApplicationServiceImpl`，统筹注册、登录、登出与用户信息用例，隔离控制层与领域核心。 | 架构分层校验 | `[明确获准的变化]` |
| V-USER-07 | 加密技术设施下沉 (Infrastructure) | `LegacyNodeScryptVerifier`（BouncyCastle Scrypt 加密技术组件）原放置在 `domain.user.security`。 | 统一下沉至 `com.ai.guide.infrastructure.user.security`，测试用例同步镜像迁移至基础设施测试包，领域层不再混杂具体加解密底层工具。 | 依赖图检查通过 | `[明确获准的变化]` |
| V-USER-08 | 全量回归与测试安全基线 | 确保全工程密码升级、角色权限、JWT 过滤与新老切片全链路测试 100% 绿灯。 | 全工程 390 项测试全部绿灯通过（0 errors, 0 failures, 10 skipped，耗时 21.3s），新增 8 项认证控制与应用服务自动化用例。 | `mvn test` 自动化验证 | `[已验证一致]` |

---

## 二、 历史切片核验项清单

### 1. RAG 知识检索增强域 (Slice 5)
- V-RAG-01 ~ V-RAG-13：19 个 REST 端点（检索、指标、文档生命周期、碎片分页、双清空端点）、管理员 403 文案围栏差异、Qdrant 客户端与高德客户端下沉全部通过验收（Commit `3b2179c`）。

### 2. Planner 规划引擎域 (Slice 4)
- V-PLAN-01 ~ V-PLAN-14：14 个 REST 端点、X-Plan-Session-Token 请求头自动注入、空 Body 兜底、DIP 纯净化 `RouteGatewayPort`/`HydrationResult`、高德与会话仓储下沉全部通过验收（Commit `9b6fefd`）。

### 3. Trip 行程生命周期域 (Slice 3)
- V-TRIP-01 ~ V-TRIP-12：9 大 REST 动词、401/404/409 异常映射、局部重规划数据外露、PDF 导出、CAS 并发控制、仓储与 PDF 基础设施下沉全部通过验收（Commit `2afbe66`）。

### 4. Preferences 偏好画像域 (Slice 2)
- V-PREF-01 ~ V-PREF-09：5 大 REST 动词、401 鉴权拦截、409 OCC 冲突结构、400 校验、200/201 状态码、仓储端口依赖倒置全部通过验收（Commit `bfbb2ef`）。

### 5. Chat 对话流试点 (Slice 1)
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


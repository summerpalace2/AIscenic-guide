# 重构状态账本 (REFACTORING_LEDGER)

> 本账本严格遵循《保持 UI 与功能一致的大规模重构流程（定稿 v2.1）》。  
> 核心原则：以 A 的代码与运行现场为唯一真理；不搞大设计先行；先 A 后 B 提取核验项；严格四分类对账；不擅自修复原有疑似缺陷。

---

## 阶段 0：基准与当前信息

| 记录项 | 填写内容 |
|---|---|
| **A 的对照基准版本** | `42d69a9` (已包含环境自适应加载与意图规则基线修复) |
| **B 的当前分支** | `refactor/cola-architecture` |
| **当前重构切片** | **Slice 2: Preferences 偏好画像域 (适配层下沉、应用服务编排与持久化端口解耦)** |
| **当前推进状态** | **阶段 2·第五步：独立质检子智能体验收通过 (APPROVED)，准予集成提交** |
| **独立审查结论** | **0 回归差异、0 尚未核实、25 已验证一致、7 明确获准的变化** |
| **最后更新时间** | 2026-10-04 17:16 |

---

## 一、 当前切片核验项清单 (Preferences 偏好画像域)

| 编号 | 条件与操作 | A 的表现与依据 (`42d69a9`) | B 的表现 (`refactor/cola-architecture`) | 核验方式与版本 | 结果 |
|---|---|---|---|---|---|
| V-PREF-01 | 5 大 REST 动词在 `/ai/preferences`<br/>(GET/POST/PATCH/PUT/DELETE) | 完整支持 5 种 HTTP 请求方式与 `@RequestParam(required = false) Long expectedRevision`。 | `PreferencesController` 完整映射在 `/ai/preferences`，URL 路径与入参完全对齐。 | `PreferencesControllerTest` + MockMvc 验证 | `[已验证一致]` |
| V-PREF-02 | 用户认证检查与 401 拦截 | 拦截 `UserContext.isAnonymous()` 及空白 userId；未认证分别提示："请先登录后查询正式偏好"、"请先登录后修改正式偏好"、"请先登录后清空正式偏好"。 | `PreferencesController` 保留完全相同的身份拦截与字面级提示文案。 | 契约对账与单测覆盖 | `[已验证一致]` |
| V-PREF-03 | OCC 乐观并发控制冲突 (409) | 版本冲突时抛出 `PreferenceConflictException`，返回 409，data 包含 `expectedRevision` 与 `currentRevision`。 | `PreferencesController` 及 `PreferencesService` CAS 失败重新查库取最新 revision 机制保持一致。 | 冲突测试用例绿灯 | `[已验证一致]` |
| V-PREF-04 | 参数非法 400 与异常 500 埋点 | 非法参数返回 400 与对应 message；非预期异常返回 500 并记录统一格式 `[PREF] ... failed userId={}...` 日志。 | 异常处理与日志格式完全一致。 | 异常边界测试覆盖 | `[已验证一致]` |
| V-PREF-05 | 增量合并与全量替换状态码 | 新增偏好返回 201，修改返回 200；PUT 提示“偏好已替换”，POST/PATCH 提示“偏好已更新”。 | `PreferencesController` 准确根据 `result.created()` 决定状态码，提示语 100% 相同。 | 状态码与返回值断言 | `[已验证一致]` |
| V-PREF-06 | 偏好清空 (DELETE) | 仅清空正式偏好档案并记录 CLEAR 审计日志，返回 200 与“偏好已清空”；不影响 Redis 临时槽位。 | `PreferencesController` 委派应用服务清空，协议保真。 | 单测走查覆盖 | `[已验证一致]` |
| V-PREF-07 | 关系存储下沉与端口依赖倒置 | 原 `PreferencesRepository`、`PreferenceAuditRepository` 位于领域层直接依赖 `JdbcTemplate`。 | 在领域层定义 `PreferencesRepositoryPort` 和 `PreferenceAuditRepositoryPort`，具体实现下沉到 `infrastructure.preferences.repository`。 | COLA 分层校验与全量绿灯 | `[明确获准的变化]` |
| V-PREF-08 | 偏好补丁模型规范化 | 原 `UserPreferencesPatch` 放置在已废弃的 `domain.preferences.api` 包中。 | 迁入 `domain.preferences.model` 保持领域聚合内聚，消除领域服务逆向依赖 API 的架构倒置。 | 依赖关系与编译检查 | `[明确获准的变化]` |
| V-PREF-09 | 应用服务层引入 | 原控制器直接调用领域服务 `PreferencesService`。 | 新增 `PreferencesApplicationService` 作为用例调度中枢，解耦 Web 请求与业务逻辑。 | `PreferencesApplicationServiceTest` (4/4 绿灯) | `[明确获准的变化]` |

---

## 二、 历史切片核验项清单 (Chat 对话流)

| 编号 | 条件与操作 | A 的表现与依据 (`42d69a9`) | B 的表现 (`refactor/cola-architecture`) | 核验方式与版本 | 结果 |
|---|---|---|---|---|---|
| V-CHAT-01 | GET `/ai/chat/stream`<br/>入参：message, sessionId, mode... (10个参数) | 声明 10 个参数，返回 SSE 流式数据。事件顺序为：`meta` -> `sentiment` -> 逐块 `text` -> `done`。 | `ChatController.java` 完整保留 10 个参数，封装 `ChatStreamCommand` 委派；事件序列严格保真。 | `ChatControllerTest` + `ChatApplicationServiceTest` | `[已验证一致]` |
| V-CHAT-02 | GET `/ai/chat/stream`<br/>普通对话偏好槽位提取 | 提取并持久化用户偏好槽位；规划上下文时显式跳过，防止历史槽位污染。 | `ChatApplicationService` 完整保留前置门禁判断与异常降级防护。 | 单测覆盖 | `[已验证一致]` |
| V-CHAT-03 | GET `/ai/chat/stream`<br/>快速通道与 RAG 检索分流 | 负向情绪或短闲聊直接跳过 Qdrant 检索由 LLM 共情；其余走检索与 POI 追加。 | `ChatApplicationService` 完整移植正则与分支逻辑，安全防御保持完全一致。 | 单测覆盖 | `[已验证一致]` |
| V-CHAT-04 | GET `/ai/chat/stream`<br/>双层超时与容灾降级 | 外层 Pipeline 保护；内层 stream 超时输出 fallback error 事件 + done 事件。 | `ChatApplicationService` 严格对齐超时阈值与文案。 | 管道断言 | `[已验证一致]` |
| V-CHAT-05 | GET `/ai/chat/stream`<br/>对话结束持久化与埋点 | 流完成后，将完整 `AssistantMessage` 存入 Redis 对话历史，并记录分析日志。 | 在 `doOnComplete` 中完整保留异步持久化与耗时埋点逻辑。 | 单元测试 mock 验证 | `[已验证一致]` |
| V-CHAT-06 | GET `/ai/chat/structured`<br/>结构化对话 | 返回 `Result<ScenicResponse>`，包含景点详细结构体，记录历史。 | 迁入 `ChatApplicationService.chatStructured`，接口出入参格式 100% 保真。 | 契约走查与单测验证 | `[已验证一致]` |
| V-CHAT-07 | GET `/ai/history/**`<br/>会话历史查询与删除 | 4 个端点协议与数据完全保留。 | `HistoryController` 迁移至 `adapter.chat.web`，URL 映射与入参保持一致。 | 全量集成测试验证通过 | `[已验证一致]` |
| V-CHAT-08 | 基础设施下沉与领域净化 | 原 `RedisChatMemory` 位于领域层直接依赖 `RedisTemplate`。 | 迁移下沉至 `com.ai.guide.infrastructure.chat.memory`。 | 编译检查与全量测试 | `[明确获准的变化]` |

---

## 三、 疑似原有问题清单 (Suspected Legacy Issues)
> 规则：保留原行为，记录供用户调试判断，不擅自“修复”。

| 编号 | 涉及接口/模块 | 现象与存疑原因 | A/B 是否保留 | 复现与验证方式 | 用户判断与状态 |
|---|---|---|---|---|---|
| ISSUE-01 | GET `/ai/chat/structured`<br/>(结构化对话槽位提取) | 原 `A_ChatController.java` [L569] 在调用 `slotTrackingService.extractAndSave(userId, message)` 时未像流式接口那样进行 `userId != null` 防御性校验，匿名用户会落库 `user:null:slots`。 | A/B 完全保留现状 | 匿名请求 `/ai/chat/structured` 观察 Redis key `user:null:slots` | 待用户决策是否在后续画像域统一治理 |
| ISSUE-02 | GET `/ai/preferences`<br/>(异常转译状态码) | 原 `PreferencesController.java` [L55-56] 在捕获 `IllegalArgumentException` 时返回 401（未登录文案），而写操作捕获同类型异常返回 400。 | A/B 完全保留现状 | 无凭证直接调用 GET `/ai/preferences` | 属于既有契约边界，忠实保留 |

---

## 四、 最小共享约定与架构演进记录 (Contract Drift)
- `2026-10-04 (Slice 1 - Chat 域)`:
  - 迁入 `adapter.chat.web` 与 `application.chat`，下沉 `RedisChatMemory` 至 `infrastructure.chat.memory`。
- `2026-10-04 (Slice 2 - Preferences 域)`:
  - `com.ai.guide.adapter.preferences.web`: 承接 `PreferencesController`，负责 HTTP 协议映射、401/409/400 适配。
  - `com.ai.guide.application.preferences`: 新增 `PreferencesApplicationService`，负责偏好画像核心用例编排。
  - `com.ai.guide.domain.preferences.gateway`: 定义 `PreferencesRepositoryPort` 与 `PreferenceAuditRepositoryPort` 纯接口，彻底剥离领域层持久化技术。
  - `com.ai.guide.domain.preferences.model`: 承接 `UserPreferencesPatch`，消除领域层向外倒置依赖。
  - `com.ai.guide.infrastructure.preferences.repository`: 承接 `PreferencesRepository` 与 `PreferenceAuditRepository`，封装 JDBC 与 JSON 序列化。
  - 全量 327 个单元测试（覆盖 Preferences、Chat、Planner、Trip、Auth、RAG 等全部模块）全部通过（18.618s）。
  - 独立审查子智能体（`8bf5400f-9f5b-497d-bbf0-2d32c423ed88`）出具 PASS 裁决报告。

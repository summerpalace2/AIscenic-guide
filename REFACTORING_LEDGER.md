# 重构状态账本 (REFACTORING_LEDGER)

> 本账本严格遵循《保持 UI 与功能一致的大规模重构流程（定稿 v2.1）》。  
> 核心原则：以 A 的代码与运行现场为唯一真理；不搞大设计先行；先 A 后 B 提取核验项；严格四分类对账；不擅自修复原有疑似缺陷。

---

## 阶段 0：基准与当前信息

| 记录项 | 填写内容 |
|---|---|
| **A 的对照基准版本** | `42d69a9` (已包含环境自适应加载与意图规则基线修复) |
| **B 的当前分支** | `refactor/cola-architecture` |
| **当前重构切片** | **Chat 对话流试点 (ChatController 编排分离与轻量 COLA 分层)** |
| **当前推进状态** | **阶段 2·第五步：独立质检子智能体验收通过 (APPROVED)，已完成主干集成** |
| **独立审查结论** | **0 回归差异、0 尚未核实、12 已验证一致、3 明确获准的变化** |
| **最后更新时间** | 2026-10-04 17:06 |

---

## 一、 当前切片核验项清单 (Chat 对话流)

| 编号 | 条件与操作 | A 的表现与依据 (`42d69a9`) | B 的表现 (`refactor/cola-architecture`) | 核验方式与版本 | 结果 |
|---|---|---|---|---|---|
| V-CHAT-01 | GET `/ai/chat/stream`<br/>入参：message, sessionId, mode, plannerSessionId, tripId... (10个参数) | 声明 10 个参数，默认值分别为 "default", "normal", ""。返回 SSE 流式数据。事件顺序为：`meta` -> `sentiment` (非中性) -> 逐块 `text` -> `done`。首包包含 RAG 检索状态与 citations。 | `ChatController.java` 完整保留 10 个参数与默认值，封装 `ChatStreamCommand` 委派；事件序列严格保真。 | `ChatControllerTest` + `ChatApplicationServiceTest` (323全量绿灯) | `[已验证一致]` |
| V-CHAT-02 | GET `/ai/chat/stream`<br/>普通对话 (非规划上下文) | 提取并持久化用户偏好槽位 (`slotTrackingService.extractAndSave`)；规划上下文时显式跳过，防止历史槽位污染。 | `ChatApplicationService` [L178-L184] 完整保留前置门禁判断与异常降级防护。 | 代码对账与单测覆盖 | `[已验证一致]` |
| V-CHAT-03 | GET `/ai/chat/stream`<br/>快速通道与 RAG 检索分流 | 负向情绪或短闲聊（正则匹配 31 种问候寒暄短语）直接跳过 Qdrant 检索，由 LLM 直接共情；其余走深度/普通检索与 POI 上下文追加。 | `ChatApplicationService` 完整移植正则与分支逻辑，安全防御保持完全一致。 | 代码对账与单测覆盖 | `[已验证一致]` |
| V-CHAT-04 | GET `/ai/chat/stream`<br/>双层超时与容灾降级 | 外层 Pipeline 保护 (Math.max(1000, streamTimeout + 15000))；内层 stream 超时输出 fallback error 事件 + done 事件。 | `ChatApplicationService` [L127-L156, L278-L287] 严格对齐超时阈值与文案。 | 响应式流管道断言 | `[已验证一致]` |
| V-CHAT-05 | GET `/ai/chat/stream`<br/>对话结束持久化与埋点 | 流完成后，将完整 `AssistantMessage` 存入 Redis 对话历史，并记录分析日志 `analyticsService.logService`。 | 在 `doOnComplete` 中完整保留异步持久化与耗时埋点逻辑。 | 单元测试 mock 验证 | `[已验证一致]` |
| V-CHAT-06 | GET `/ai/chat/structured`<br/>结构化对话 | 返回 `Result<ScenicResponse>`，包含景点详细结构体，记录历史。 | 迁入 `ChatApplicationService.chatStructured`，接口出入参格式 100% 保真。 | 契约走查与单测验证 | `[已验证一致]` |
| V-CHAT-07 | GET `/ai/history/**`<br/>会话历史查询与删除 | `/ai/history`, `/ai/history/sessions`, `/ai/history/{sessionId}`, `DELETE /ai/history/{sessionId}` 协议与数据完全保留。 | `HistoryController` 迁移至 `adapter.chat.web`，URL 映射与入参保持一致。 | 全量集成测试验证通过 | `[已验证一致]` |
| V-CHAT-08 | 基础设施下沉与领域净化 | 原 `RedisChatMemory` 位于 `domain.chat.service`，直接依赖 `RedisTemplate` 中间件，违反 DDD 领域纯粹性。 | 迁移下沉至 `com.ai.guide.infrastructure.chat.memory`，清理 `AnalyticsService` 无关引用。 | 编译检查与全量测试 | `[明确获准的变化]` |

---

## 二、 疑似原有问题清单 (Suspected Legacy Issues)
> 规则：保留原行为，记录供用户调试判断，不擅自“修复”。

| 编号 | 涉及接口/模块 | 现象与存疑原因 | A/B 是否保留 | 复现与验证方式 | 用户判断与状态 |
|---|---|---|---|---|---|
| ISSUE-01 | GET `/ai/chat/structured`<br/>(结构化对话槽位提取) | 原 `A_ChatController.java` [L569] 在调用 `slotTrackingService.extractAndSave(userId, message)` 时未像流式接口那样进行 `userId != null` 防御性校验，匿名用户会落库 `user:null:slots`。 | A/B 完全保留现状 | 匿名请求 `/ai/chat/structured` 观察 Redis key `user:null:slots` | 待用户决策是否在后续画像域统一治理 |

---

## 三、 最小共享约定与架构演进记录 (Contract Drift)
- `2026-10-04`: 落地首个 COLA 试点切片（Chat 域）：
  - `com.ai.guide.adapter.chat.web`: 承接 `ChatController` 与 `HistoryController`，去除所有具体业务编排，专注 HTTP/SSE 协议适配。
  - `com.ai.guide.application.chat`: 新增 `ChatApplicationService`、`ChatStreamCommand` 与 `ChatStructuredCommand`，作为业务编排中心。
  - `com.ai.guide.infrastructure.chat.memory`: 承接 `RedisChatMemory`，将中间件依赖从领域层彻底剥离。
  - 全量 323 个测试集（包含 Trip、Auth、Preferences、Planner、RAG 等全部模块）无损通过（21.052s），构建成功。
  - 独立质检子智能体（`16f2a4f2-5ddb-4762-833c-e2ba7853f448`）先 A 后 B 审查通过，签发准予交付。

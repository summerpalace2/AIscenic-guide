# CP5.1 Chat 模型调用边界任务卡

状态：**CP5 整体实施中的首个组成部分（不单独验收）**  
日期：2026-10-10  
工作根：`D:/scenic-guide`

## 本切片目标

把 `ChatApplicationService` 对 Spring AI `ChatClient` 的直接模型调用收敛到一个可替换的模型能力边界。第一步只处理模型调用入口；不迁移消息上下文构建、检索/画像编排、Redis 历史、压缩策略、流式 SSE 事件和聊天业务决策。所有行为与退出码保持原状。

## 当前调用图

```text
ChatController
  ├─ POST /api/chat/stream → ChatApplicationService.streamChat
  │    └─ ChatModelGatewayPort.stream(messages) → SpringAiChatModelAdapter → SSE text/meta/sentiment/done/error
  └─ structured endpoint → ChatApplicationService.chatStructured
       └─ ChatModelGatewayPort.complete(messages, ScenicResponse.class) → SpringAiChatModelAdapter

ChatApplicationService background compression
  └─ compressMessages → ChatModelGatewayPort.completeText(system + transcript) → SpringAiChatModelAdapter
       └─ compressionExecutor / compressionInFlight → RedisChatMemory summary snapshot
```

对应源码入口：`ChatController.java#chatStream`、`ChatController.java#chatStreamPost`、`ChatController.java#chatStructured`；调用实现：`ChatApplicationService.java#streamChatInternal`、`#chatStructured`、`#compressMessages`。

当前服务还直接依赖 `RedisChatMemory`、Spring AI `Message`、RAG/POI、偏好/记忆/情绪/意图服务。CP5.1 将 `ChatClient` 调用移入 infrastructure adapter；领域网关使用 provider-neutral `ChatModelMessage` 与 Reactive Streams `Publisher`。应用服务仍在上下文构造与 Redis 历史协作中使用 Spring AI `Message`，因此模型调用边界已隔离供应商 SDK，但整个 ChatApplicationService 尚未脱离 Spring AI 类型。

## 允许范围

- 新增窄模型能力端口与 Spring AI adapter，仅表达本切片所需的 streaming、typed completion 和 text completion 能力。
- 将 `ChatApplicationService` 的三处模型调用改为依赖 `ChatModelGatewayPort`；生产 Spring 装配提供 `SpringAiChatModelAdapter`，测试可注入 fake。
- 修改/增加 ChatApplicationService 与 adapter 的局部单元测试；不改 Controller wire DTO、SSE event 名称/顺序、超时或错误文案。
- 上下文消息在调用网关前映射为 `ChatModelMessage`；adapter 负责将其转换成 Spring AI 消息类型，领域契约不得暴露 `ChatClient` 或 Spring AI `Message`。

## 禁止范围

- 不改聊天提示词、RAG 排序、硬约束/敏感信息处理、用户/会话键、Redis key/schema、摘要推进游标、Token 预算或并发压缩语义。
- 不更换模型供应商、重试政策、温度、超时、SSE 反压实现、鉴权或 BFF 请求协议。
- 不修改 Planner、Memory、RAG 其他领域职责来“顺手解耦”。

## 冻结行为与反例

1. Streaming 保持现有 event：metadata → 可选 sentiment → text* → done；错误与 timeout 仍映射为 error event，取消仍记录部分回复并写历史。
2. 空模型流仍返回现有错误事件；流式订阅取消时模型调用必须取消订阅，不能继续向 Redis 写入完整成功回复。
3. Structured chat 仍返回同一 `ScenicResponse` 类型和 Result 结构。
4. 摘要仍使用摘要提示词、限制输入策略与用户归属；模型异常/空值返回 `null`，不能推进 compressed cursor；有效摘要仍按原 `uncovered` 游标保存。
5. 两个账号/会话在模型调用和摘要任务间不得共享请求内容或输出。

## 测试基线与本轮结果

- `ChatApplicationServiceTest`：流式 SSE 与聊天摘要测试（含反射调用 `getContextWithCompressedHistory` 的用例）。
- `ChatContextRecoverySafetyTest`：上下文预算、摘要及关键安全约束回归。
- `ChatControllerTest`：Controller 只负责请求映射/命令转交，wire event 数据保持原样。
- 修改前基线：`ChatApplicationServiceTest` 与 `ChatContextRecoverySafetyTest` 共 16 项通过，0 失败、0 错误；对应源码/测试 SHA-256 记录在 `CP5_1_IMPLEMENTATION_REPORT.md`。
- 修改后定向回归：上述聊天及 Controller 测试 21 项通过，另 `ColaArchitectureGuardTest` 14 项通过，共 35 项通过，0 失败、0 错误；adapter 用例覆盖消息类型翻译、typed completion、provider error 传播和流取消传播。
- 应用服务测试通过 fake `ChatModelGatewayPort` 验证既有 SSE、摘要预算及游标行为；未调用完整 Spring 上下文，未触碰项目 SQLite。

开始改生产代码前记录了测试运行前结果及 SHA-256，避免混入旧 Surefire XML；使用定向 Maven 测试，未启动会触及项目持久 SQLite 的完整 Spring 上下文。上述结果仅用于阶段性开发回归，不构成独立验收。按用户要求，架构审查、反例验收与硬编码审计在 CP5 其余领域全部实施完成后，针对统一冻结候选一次性执行。

## 当前进展

- 已新增 domain `ChatModelGatewayPort`、provider-neutral `ChatModelMessage` 与 infrastructure `SpringAiChatModelAdapter`；流式 SSE、结构化 `ScenicResponse` 和后台摘要均经网关调用。
- `ChatApplicationService` 不再导入或直接构造 `ChatClient`；消息构建、摘要调度、SSE 业务事件、RAG 和历史处理保持原位。
- 网关契约不暴露 `ChatClient`、Spring AI `Message` 或 Reactor `Flux`，使用 `ChatModelMessage` 与 Reactive Streams `Publisher`；应用服务上下文构建和 Redis 历史仍依赖 Spring AI 消息类型。
- 本切片前 `ChatApplicationService` 曾直接构造 `ChatClient`，并通过字段注入持有若干检索/记忆依赖；后者仍是后续切片边界，不作为本切片扩大范围的理由。
- 经验使用记录见 [`20261010-ddf39959ceee`](C:/Users/summer/Documents/ggy的自动化流程/wiki/tasks/20261010-ddf39959ceee.json)；本切片检索未发现可适用的模型端口经验，因此未采用既有经验。CP5 经验收据以后续整体任务记录为准。

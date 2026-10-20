# CP5.1 Chat 模型调用边界实施记录

日期：2026-10-10  
状态：**CP5 整体实施中的首个组成部分（不单独验收）**  
工作根：`D:/scenic-guide`  
实施候选基于 HEAD：`46b1653e81050e3cdd9f23506b327bc6676bcbd8`

## 变更内容

- 新增 domain `ChatModelGatewayPort` 与 provider-neutral `ChatModelMessage`，表达流式文本、指定类型完成和纯文本完成三种当前用例能力。
- 新增 infrastructure adapter `SpringAiChatModelAdapter`，集中持有 `ChatClient`，并负责领域消息与 Spring AI 消息的边界转换。
- `ChatApplicationService` 的 SSE 文本流、`ScenicResponse` 结构化应答、后台摘要改为调用网关。上下文消息生成、SSE 事件拼装、超时与异常映射、摘要队列/游标、历史写入及 RAG 编排保持在原位置。
- 应用服务测试改用 fake `ChatModelGatewayPort`；adapter 测试覆盖消息类型翻译、结构化响应类型、provider 异常传播以及流取消向上传播。

## 验证

修改前基线：

```text
命令：.\mvnw.cmd -f ai\pom.xml test -Dtest=ChatApplicationServiceTest,ChatContextRecoverySafetyTest
结果：16 项通过，0 失败，0 错误，BUILD SUCCESS
```

修改后：

```text
命令：.\mvnw.cmd -f ai\pom.xml test -Dtest=ChatApplicationServiceTest,ChatContextRecoverySafetyTest,ChatControllerTest,SpringAiChatModelAdapterTest,ColaArchitectureGuardTest
结果：35 项通过，0 失败，0 错误，BUILD SUCCESS（聊天及 Controller 21 项 + 架构守卫 14 项）
```

本次未运行完整 Spring 应用上下文和全量 Maven 套件；没有声称真实模型供应商、Redis 或持久数据库通过。`git diff --check` 对本切片文件通过。

## 明确边界与后续验收

网关契约不暴露 Spring AI `ChatClient` / `Message` 或 Reactor `Flux`，而是使用 `ChatModelMessage` 与 Reactive Streams `Publisher`。`ChatApplicationService` 仍在上下文生成和 Redis 历史处理中依赖 Spring AI `Message`，所以模型调用依赖已隔离，整个聊天应用服务仍未完成 SDK 解耦。架构守卫曾捕获 infrastructure 反向依赖 application port；已将 port 下沉到 `domain.chat.gateway`，并以最终 14 项守卫全通过确认层依赖方向。

尚未进行独立架构审查、红队反例验收或全项目硬编码审计。按用户要求，这些检查留到 CP5 所有领域实施完成后，针对统一冻结候选一次性执行；本报告只记录首个组成部分的阶段性回归，不表示 CP5 已完成。

## 候选文件 SHA-256

以下哈希用于识别本次实现候选文件；仓库另有既存工作区修改，HEAD 不代表完整候选：

| 文件 | SHA-256 |
| --- | --- |
| `ai/src/main/java/com/ai/guide/application/chat/ChatApplicationService.java` | `F7AF0B7330D7F4C11ECCBF5EDD9A98A9597330466546AAEA70417C54469A12F2` |
| `ai/src/main/java/com/ai/guide/domain/chat/gateway/ChatModelGatewayPort.java` | `7869EA3AFB3FCF67F65D00F11166ACF5D3E84DCAB3BA9FF1986FBD60FD3A8FAF` |
| `ai/src/main/java/com/ai/guide/domain/chat/model/ChatModelMessage.java` | `BE7FB4090870280E385F47535D26E91A52E979F4CE7809F990AC857E6C1ADE13` |
| `ai/src/main/java/com/ai/guide/infrastructure/chat/model/SpringAiChatModelAdapter.java` | `95EE77EB259E05F863B4A5EA8DA590D7F43F7DF004D76A534813CED66826CECB` |
| `ai/src/test/java/com/ai/guide/application/chat/ChatApplicationServiceTest.java` | `89BD82EF3AC0F478BAB24398F4F1E91AFF81422E6BA288B3CC77B804BD45702F` |
| `ai/src/test/java/com/ai/guide/application/chat/ChatContextRecoverySafetyTest.java` | `DA75E76955D9F094EFA7CD292D000E6A33880663023E0DFFAAFE777E00BF7BBC` |
| `ai/src/test/java/com/ai/guide/infrastructure/chat/model/SpringAiChatModelAdapterTest.java` | `05262E1211C6DDD69903EA9C494D5A5E0C6906421C79F6C9EB915888285D6AA9` |

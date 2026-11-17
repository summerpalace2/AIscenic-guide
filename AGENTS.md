# AGENTS.md — 渝游智策 Java Core Backend（Agent 改造工作区）

## 1. Overview
「渝游智策」唯一共享业务核心与 AI 决策后端（Spring Boot 3.4.1 / Java 17 / Spring AI / Qdrant / Redis / SQLite & PostgreSQL），入口 `com.ai.guide.ScenicGuideApplication`（端口 `8080`，健康检查 `/actuator/health`）。本工作区（分支 `feat/agent-architecture`）用于重构为「单 Orchestrator + Tool-Calling + 确定性引擎沙箱 + HIL 确认」的 Agent 架构；原 `D:\scenic-guide` 目录保留作基线对标。

## 3. Commands（在 `D:\scenic-guide-agent\ai` 下执行）
- 离线全量测试（必须带隔离库参数，防污染本地库）：
  `.\mvnw.cmd test "-DKNOWLEDGE_DB_URL=jdbc:sqlite:target/isolated-test-knowledge.db"`
- COLA 4.0 分层单向守卫（改包结构或新增 Port/Adapter 必跑）：
  `.\mvnw.cmd test -Dtest=ColaArchitectureGuardTest`
- 硬编码审计（涉及词典/规则/餐饮景点数据时运行）：
  `python scripts/audit-cp5-backend-hardcoding.py`
  `python scripts/audit-planner-hardcoding.py`

## 7. Boundaries
### Always
- **新增或修改的所有 Java 类、接口、方法与关键逻辑必须使用清晰规范的中文注释。**
- 严格遵循 COLA 4.0 单向依赖：`adapter → application → domain ← infrastructure`；Spring AI (`ChatClient` / `OpenAiChatOptions` / `ToolResponseMessage`) 具体实现仅允许在 `infrastructure`。
- 守住业务不变量（详见 `docs/baseline/BUSINESS_INVARIANTS_SPEC.md`）：Preview 无副作用、Apply 须 `baseRevision == currentVersion` CAS 乐观锁 + 幂等、Cancel 否定词防御、Fixed 站点不可挤压与闭馆/跨午夜不可绕过。
- Agent 编排保持 System 前缀与 Tool 列表全程固定（不中途增删，保 KV Cache），工具报错/冲突 Observation 必须保留给模型自纠。
- 对本文件遵循“因错迭代 + 定期修剪”：仅在同一错误真实犯两次时才追加条目，已被测试/CI 强制约束的内容及时删掉。

### Ask first
- 修改 `/ai/**` 对外 HTTP/SSE 契约、`spring-ai.version` 升级、数据库 Schema 迁移，或新增会持久化修改行程/偏好的写操作 Tool。

### Never
- 禁止 `domain` 包引入 `adapter` / `application` / `infrastructure` 或 `org.springframework.ai.*`。
- 禁止绕过 `RouteAwarePlanner` / `PlanVerifier` / `ProposalRepositoryPort` 让 LLM 直接改写正式行程或口算时间窗。
- 禁止跳过或放宽 `ColaArchitectureGuardTest`；禁止扫描 `ai/target/`、`ai/knowledge.db`、`.env`。
- 未连接真实 DeepSeek / Qdrant / DashScope / 高德 Key 实测时，禁止宣称已通过端到端真实联调（必须显式标注“仅离线单测通过”）。

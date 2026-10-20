# 渝游智策三仓库重构基线与验证资产清单 (Baseline V1)

版本：1.0  
记录日期：2026-10-07  
状态：**已实测确权，作为后续 COLA 架构重构 S1–S8 阶段的统一回退基线**

---

## 1. 三仓库 Git 版本与工作树基线

| 仓库标识 | 本地绝对路径 | 分支名称 | 提交 SHA | 提交说明 | 工作树状态 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Java 后端** | `D:/scenic-guide` (模块 `ai`) | `refactor/plan-adjustment-monolith-decoupling` | `46b1653e81050e3cdd9f23506b327bc6676bcbd8` | 修复：加固规划聊天与后端边界并制定分阶段重构验收方案 | clean (无未提交修改) |
| **Node BFF** | `C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home` | `main` | `6eb2a07a98fc55eb51c051b57934badddc828bd6` | 修复：统一规划续接、记忆投影与行程版本校验 | clean (仅未跟踪本地材料) |
| **前端 Web** | `C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend` | `main` | `6dd081da50078d39cd76337c018cd5cb4042dd18` | 修复：完善聊天调整交互、偏好合并与工作区隔离 | clean (无未提交修改) |

---

## 2. 实际运行入口与技术栈基线

1. **Java 后端服务**：
   - 运行入口：`com.ai.guide.ScenicGuideApplication`
   - 监听端口：`http://localhost:8080`
   - 健康检查端点：`http://localhost:8080/actuator/health`（实测返回 HTTP 200）
    - 技术栈：Java 17 编译目标（JDK 23 环境） / Spring Boot 3.4.1（由 spring-boot-dependencies 管理） / Spring AI 1.0.0-M3 / SQLite (JPA & JDBC) / Caffeine + Redis
2. **Node.js BFF 代理服务**：
   - 运行入口：`server/index.mjs`
   - 监听端口：`http://localhost:3000`
   - 健康检查端点：`http://localhost:3000/api/health`（实测返回 HTTP 200）
   - 技术栈：Node.js v24.14.0 原生 HTTP 服务（零三方运行依赖，内置测试运行器 `node --test`）
3. **前端 Web 界面**：
   - 运行入口：`web/`（启动命令 `vite --config vite.config.ts web`）
   - 监听端口：`http://localhost:5173`（实测返回 HTTP 200）
   - 技术栈：Vite 6.4.3 / 原生现代化 ESM + CSS 变量 / Netlify 部署适配

---

## 3. 已实测验证的全量自动化测试基线

本清单所有测试均在本地**离线、无真实外网 Key、使用临时隔离 SQLite 数据库**环境下实际执行验证通过：

### 3.1 Java 后端全量测试套件
- **执行命令**：`.\mvnw.cmd test "-DKNOWLEDGE_DB_URL=jdbc:sqlite:target/isolated-test-knowledge.db"`
- **实测结果**：
  - **已提交基线测试汇总**：937 项（927 通过，10 跳过，0 失败，0 错误）
  - **步骤 3 重构与边界修复后最新执行汇总**：949 项（939 通过，10 跳过，0 失败，0 错误）
  - **失败数 (Failures)**：0 项
  - **错误数 (Errors)**：0 项
  - **跳过数 (Skipped)**：10 项（主要为缺少外部 `production-v2-source` 数据资产与真实 Qdrant 远程集群的集成用例）
  - **构建状态**：**BUILD SUCCESS**
  - **核心测试类覆盖**：
    - `PlanAdjustmentServiceTest`：66 项全通（含时空开闭馆、晚间跨午夜检测、餐饮多级归一化）；
    - `StopPlanRoundTripTest`：8 项全通（含 100% 无损往返、null/false/空字符串保留、核心数值类型保留、恶意 extras 清洗、边界校验与非碰撞 ID）；
    - `MemoryRepositoryPortContractTest`：4 项全通（含 CRUD 契约、TransactionTemplate 异常回滚、画像失败候选回滚）；
    - `ColaArchitectureGuardTest`：6 项全通（ArchUnit COLA 架构分层单向守护）；
    - `CombinedLogicRegressionTest`：7 项全通；
    - `TravelMemoryServiceTest`、`ChatApplicationServiceTest` 等核心领域测试全通。

### 3.2 BFF 代理契约测试套件
- **执行命令**：`node --test (Get-ChildItem server -Filter '*.test.mjs').FullName`
- **实测结果**：
  - **测试总数**：14 项（涵盖 4 个测试套件：`memory-pagination.test.mjs` (4项)、`memory-projection-refresh.test.mjs` (3项)、`planner-bridge-contract.test.mjs` (2项)、`trip-delete-version.test.mjs` (5项)）
  - **实际通过数**：14 项（100% Passed）
  - **失败/错误数**：0 项
  - **语法检查**：`node --check server/index.mjs` 零语法错误
  - **覆盖重点**：CSRF 拦截、分页参数边界防死循环、版本号冲突防脏删、规划桥接透传与取消、图片与画像按需刷新。

### 3.3 前端 Web 自动化测试与打包构建
- **执行命令**：`node --test (Get-ChildItem web/src -Recurse -Filter '*.test.mjs' -File)` 及 `npm run build`
- **实测结果**：
  - **测试总数**：41 项
  - **实际通过数**：41 项（100% Passed）
  - **失败/错误数**：0 项
  - **Vite 生产构建**：60 modules transformed，打包成功零警告。

---

## 4. 明确登记的未验证能力边界（Non-Verified Capabilities Inventory）

根据文档规范，以下能力在当前测试环境中未连接真实外部生产基础设施，**明确登记为“尚未端到端验证”，后续重构中不得虚假声称其通过端到端验证**：

1. **真实 Qdrant 向量检索服务**：
   - 现状：当前在测试和本地环境下保持显式降级为本地 SQLite `kb_document` 关键词匹配。
   - 边界：未验证大规模高维向量在生产 Qdrant 容器下的召回率与并发写入表现。
2. **生产 PostgreSQL 数据库双向迁移**：
   - 现状：当前所有回归基于 SQLite 隔离数据库。
   - 边界：PostgreSQL 生产方言与序列迁移脚本未在真实 PostgreSQL 实例上执行回滚验证。
3. **真实高德 LBS API 极端并发限流**：
   - 现状：测试环境使用内置 Mock 或静态缓存。
   - 边界：真实高德账号在超出 QPS 配额（403/QuotaExceeded）时的网络熔断与恢复。
4. **多实例部署下的分布式 Epoch 与跨实例接续**：
   - 现状：当前为单实例部署架构，会话锁与 Epoch 基于本地内存与单节点 Redis。
   - 边界：跨多个 Pod/实例的提案共享与 CAS 竞争尚未搭建双节点测试集群（属于 S8 条件阶段）。
5. **真实百度语音 ASR/TTS 在线接口**：
   - 现状：测试环境使用测试占位 Key 与本地 Mock 响应。
   - 边界：未产生真实百度语音计费流量的端到端声学识别。

# CP4 累积验收裁决

日期：2026-10-10  
裁决：**CP4 按执行计划 §3.5 的明确退出条件验收通过；不是对未覆盖故障与部署环境的全面保证。**

## 验收依据

CP4 §3.5 的退出条件为：完成保存→打开→局部调整→保存→重载；空保存不增加正式行程版本；删除冲突采用页面显示版本；旧工作区或旧账号响应不能污染当前页面；涉及 UI 的路径必须有真实交互证据。

| 条件 | 证据与裁决 |
|---|---|
| 保存→打开→局部调整→保存→重载 | R11 使用隔离临时 SQLite、BFF JSON 和真实 Java/BFF HTTP 服务完成真实浏览器流程；保存、重开及 BFF 重启后数据保持一致。R12 修复硬刷新时认证恢复晚于页面挂载导致的列表无限加载，并在真实浏览器验证硬刷新及同页登出/登录恢复。通过。 |
| 空保存不增加版本 | R11 浏览器实测未修改保存版本不变；CP4.1 Java Save 回归覆盖 no-op 保存与版本语义。通过。 |
| 删除冲突按显示版本处理 | 当前 BFF/前端回归覆盖显示版本提交、冲突后采用服务端版本及缺失版本先刷新；本轮复跑 Node 套件通过。通过。 |
| 迟到响应不污染账号/工作区 | R4 stale-Open 与工作区 epoch/revision 门禁回归覆盖；本轮复跑 Node 套件通过。通过。 |
| UI 路径有真实交互证据 | R11、R12 的隔离浏览器报告提供真实页面交互证据；R11 实际 Java↔BFF HTTP 链路覆盖保存、打开、修改、保存和重开。通过。 |

## 本轮独立复跑

- Java：`./mvnw.cmd test -Dtest=ColaArchitectureGuardTest,PlannerOpenApplicationServiceTest,PlannerControllerTest,PlannerSessionRepositoryDeletionTest`，**43 passed，0 failures/errors/skipped，BUILD SUCCESS**。仓储删除测试使用临时 SQLite；未运行连接项目持久数据库的 `PlannerSessionRepositoryTest`。
- BFF、scripts、legacy frontend feature：`node --test server/*.test.mjs scripts/*.test.mjs frontend/web/src/features/**/*.test.mjs`，**141 passed，0 failed/cancelled/skipped**。
- Vue 前端：`npx vite build --config vite.config.js`，**成功，116 modules transformed**。
- R12 记录的 `vue-tsc --noEmit` 仍有 `ChatPanel.vue` 既有候选字段类型错误；本轮未运行或声称其通过。该项不在 CP4 §3.5 的验收条件内，保留为独立质量问题。

## 明确保留的边界

1. **Open 响应丢失与补偿 DELETE 失败：CP4.5 已关闭代码层 P2。** 同一正式行程 Open 操作现在复用稳定客户端操作键与 Java 幂等键；BFF 以确定 ID 落地本地 draft/workspace 后 ACK Java pending session。未收到 Java 响应时重试同键复用会话；补偿失败或确认前进程退出时，后台 TTL 删除未确认且未修改的 session。隔离故障注入、临时 SQLite 与契约回归见 [`CP4.5 Open 幂等与清理报告`](CP4_5_OPEN_IDEMPOTENCY_CLEANUP_REPORT.md)。真实 socket 丢包、PostgreSQL、真实 Redis、多实例和进程强杀恢复仍未验证，不据此宣称生产网络层已实测。
2. **PostgreSQL、真实 Redis、多实例、进程崩溃恢复**未验证。现有 CP4 明确以隔离 SQLite、单 BFF JSON 存储和单实例语义验收；这些环境未列为 §3.5 必测项，需按实际部署目标单独立项。
3. **类型检查**：R12 已记录 `ChatPanel.vue` 的 8 处类型诊断；不把 Vite build 成功误报为 TypeScript 全量检查通过。

## 最终状态

CP4 的已定义退出条件均有当前报告或先前真实交互证据支撑，Open P2 follow-up 已按 CP4.5 增补幂等恢复与 TTL 清理。故**CP4 限定范围验收通过，CP4.5 代码层补充风险关闭**；不扩展为“系统所有故障模式均已解决”。CP5 已启动，首卡是 Chat 模型调用边界，见 [`CP5.1 任务卡`](CP5_1_CHAT_MODEL_BOUNDARY.md)。

关联报告：[R11 真实 Java↔BFF 集成](CP4_4_R11_LIVE_INTEGRATION_ACCEPTANCE_REPORT.md)、[R12 刷新恢复](CP4_4_R12_TRIP_LIST_REFRESH_RECOVERY_REPORT.md)、[CP4 执行计划](COLA_REFACTOR_EXECUTION_PLAN.md)。

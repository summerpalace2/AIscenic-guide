# CP4.5 Open 回执丢失与孤儿 Session 清理

日期：2026-10-10  
裁决：**原 P2 风险已在代码和隔离故障注入层关闭；真实 socket 丢包、生产数据库及多实例恢复仍未验证。**

## 变更

1. 浏览器 Open 请求为账号与正式行程生成稳定操作键，暂存于 `sessionStorage`；请求失败时保留，成功或明确的 409 冲突后清理。页面运行期间另有内存备份，sessionStorage 不可用时仍可重试。
2. BFF 校验并向 Java 透传 `Idempotency-Key`，并按账号、行程和操作键生成稳定的 BFF draft ID。Java 将行程 ID 与操作键映射为持久幂等键；同一键绑定不同正式版本/快照时返回 409，不静默返回旧/新错配快照。
3. Java Open 新建 `OPEN_PENDING` session。BFF 完成 draft 与 workspace 的单次原子保存后调用 ACK，Java 才将 session 激活。ACK 失败时 BFF 不注册/返回会话，回 503 `PLANNER_OPEN_ACK_PENDING` 并提示同一操作重试。
4. Open 的 Java 回执在 BFF 收到前丢失时，重试同一操作键返回原 Java session；确定性 BFF ID 也复用同一 draft identity。若 BFF 持久化失败且补偿 DELETE 失败，或进程在 ACK 前退出，Java 定时任务会在默认 24 小时后删除仍未确认、未修改的 `OPEN_PENDING` session 及 revision。TTL 与扫描间隔可由 `planner.open.pending-session-ttl-ms`、`planner.open.pending-cleanup-interval-ms` 配置。
5. Java session 与初始 revision 写入采用事务；幂等唯一键上的冲突仍通过数据库约束收敛。测试桩也支持稳定重放、ACK 和补偿 DELETE。

## 关键证据

- Java：`./mvnw.cmd test "-Dtest=PlannerOpenApplicationServiceTest,PlannerControllerTest,PlannerSessionRepositoryDeletionTest,ColaArchitectureGuardTest"`，**49 项通过，0 失败/错误/跳过，BUILD SUCCESS**。仓储测试使用 `@TempDir` 临时 SQLite，覆盖重复操作键只建一个 session/revision、ACK owner scope/idempotence、过期 pending 清理以及不删除已确认 session。
- BFF + 前端 Open 故障路径：`node --test server/formal-trip-bff-contract.test.mjs frontend/web/src/features/trip-save/open-saved-trip-stale-response.test.mjs`，**39 项通过**。其中注入“Java 已提交但响应丢失”、ACK 失败后同键重试，断言同一 Java 操作键、同一 BFF ID、BFF 不提前暴露未确认 session。
- 完整 Node：`node --test server/*.test.mjs scripts/*.test.mjs frontend/web/src/features/**/*.test.mjs`，**144 项通过，0 失败/取消/跳过**。
- `node --check server/index.mjs`、`node --check server/java-core-client.mjs` 通过；`npx vite build --config vite.config.js` 成功（116 modules）。

## 限制与未验证项

- 全量 `mvn test` 未取得退出结果。本轮启动后观察到 SpringBoot 测试上下文加载应用配置的本地 SQLite，并运行迁移/种子初始化；之后停止全量套件。`ai/data/knowledge.db` 在测试启动后显示 19:30 的更新时间；由于没有运行前副本/hash，不能确定本轮对该 SQLite 的精确数据变更，也没有尝试覆盖或回滚它。不能声称 Java 全量绿灯。定向集成测试中的仓储部分使用临时 SQLite。
- 尚未用真实 Java↔BFF TCP socket 注入连接中断，也未验证 PostgreSQL、真实 Redis、多 BFF/Java 实例或进程被强杀后的协调；模拟测试验证控制流和持久状态契约，不替代这些部署层实验。
- `vue-tsc` 的 `ChatPanel.vue` 既有类型错误仍按 R12 记录；本次 Vite 构建成功不表示完整类型检查通过。

## 回切

本切片新增 Java Open ACK/DELETE 契约、`OPEN_PENDING` 状态与清理任务，以及 BFF/前端操作键逻辑。回切代码前需确认所有 BFF 实例已停止发送新 ACK/Open-Idempotency 字段；数据库中遗留 pending 行可由清理任务回收。不要通过恢复旧数据库文件或清空 session 表回切。

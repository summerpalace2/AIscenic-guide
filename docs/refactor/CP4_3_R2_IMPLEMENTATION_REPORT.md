# CP4.3 R2 Restore 红队定点返工报告

实施日期：2026-10-09  
状态：候选已冻结，等待主智能体安排 B/C 复审；本报告不代表 CP4.3 验收通过。

## 本轮范围与改动

本轮只处理 `CP4_3_REDTEAM_REVIEW.md` 指出的三项缺口，并保留前轮 Restore 迁移行为与范围：

1. `PlannerRestoreApplicationService` 改为构造注入 Spring 管理的 `TripPlanLimits` bean，不再在 Restore 请求内 `new TripPlanLimits(objectMapper)`。这样 Restore 与正式行程保存读取同一 `trip.plan.*` 配置来源。这是本切片有意的行为修正：部署配置的上限现在同样约束恢复的备份。
2. 修正错误 capability 覆盖：登录用户对 USER session 以 owner 身份授权；测试不再假设 USER token 是强制门禁。另增错 capability 认领匿名 session 的测试，确认请求调用 `claimAnonymous` 并被拒绝，且没有读取正式行程或写 session。
3. 增加 repository CAS 更新阶段冲突测试：模拟 `MutationResult.conflict(latest)`，断言 409 响应含最新 `sessionId`、行程 revision 与标记内容，并验证只调用一次 CAS update、不做重试。

生产代码修改仅为 Restore application 的限制依赖注入；其他新增为 Restore 用例测试与旧入口 fixture 构造参数调整。未改 BFF、前端、schema、Controller 协议或其他业务规则。

## 测试结果

在全新隔离副本 `D:\scenic-cp4_3-r2b-0ff7c5d6ef2048ac9df8c61f00dfb029\ai` 运行，副本从当前工作树复制，排除 `.git`、`target`、`node_modules`、`build`、`dist`、`.idea`。`clean test` 没有操作共享 `D:\scenic-guide\ai\target`。

命令：

```powershell
.\mvnw.cmd -B '-Dtest=PlannerRestoreApplicationServiceTest,PlannerOpenApplicationServiceTest,PlannerSaveApplicationServiceTest,PlannerDraftRestoreTest,PlannerSaveConsistencyTest,TripSaveCapacityRegressionTest,PlannerControllerTest,PlannerServiceTest,ColaArchitectureGuardTest' clean test
```

结果：**74 tests, 0 failures, 0 errors, 0 skipped；BUILD SUCCESS**。其中 Restore application 10 项、旧 Restore facade 2 项通过。最终原始日志为 `CP4_3_R2_TEST.log`；候选记录了其 SHA-256。

启动时 Qdrant `localhost:6334` 连接失败并按既有逻辑降级；该测试没有验证真实 Qdrant 服务。初次测试中唯一失败来自测试对冲突响应顶层 `syncRevision` 的错误预期，代码现有错误响应在 `trip` 中携带最新状态；断言已按实际兼容契约改为校验最新 sessionId/trip 内容，并在另一份全新隔离副本完成上述最终运行。未将初次失败计入最终通过数。

## 保留的边界与未验证项

- formal Trip 读取与 planner session CAS 更新之间仍有跨聚合时间窗：formal Trip 可在读取后、session 更新前推进版本。此 Restore 只更新 planner session，不覆盖正式行程；后续正式保存仍会以 source version CAS 拒绝旧来源。本轮没有扩展为跨聚合事务或锁，也没有声称此并发路径经屏障测试。
- 测试使用 Mockito 端口，不能替代真实 PostgreSQL/JDBC 并发证明、真实 HTTP、BFF 或浏览器生命周期验证。
- 错 capability 测试断言 application 对 claim port 的调用与拒绝；USER owner 授权的数据库查询语义由现有 `PlannerSessionRepository.find` 实现提供，不把 USER token 误当授权条件。

## 候选身份与复审

最新候选 manifest：`CP4_3_R2_CANDIDATE.json`；本轮报告：`CP4_3_R2_IMPLEMENTATION_REPORT.md`；原始测试日志：`CP4_3_R2_TEST.log`；隔离副本路径：`CP4_3_R2_ISOLATED_COPY.txt`。前一轮候选原始文件保留为 `CP4_3_R1_*`，不被覆盖。

冻结后停止编辑 CP4.3 源码，等待 B/C 针对 R2 identity 复审。本报告不宣布 CP4.3 验收通过。

## 经验记录

任务记录：`C:\Users\summer\Documents\ggy的自动化流程\wiki\tasks\20261009-133113fbf0eb.json`。按主题检索没有命中适用 Wiki 结论；本轮未采用历史经验规则，结论来自当前实现、仓储授权语义与隔离测试。

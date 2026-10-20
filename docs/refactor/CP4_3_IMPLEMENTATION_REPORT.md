# CP4.3 Planner Restore 草稿恢复应用用例迁移

实施日期：2026-10-09  
状态：实现候选已冻结，等待 B/C 复核；本报告不代表验收通过。  
范围：仅迁移 Java Planner V1 Restore 草稿恢复 orchestration 到 application；未改 BFF、前端、数据库 schema 或其他 CP 切片。

## 1. 变更

原 `PlannerService.restoreDraft` 负责用户身份、session/capability 授权、期望 planner revision、关联 formal Trip 读取与版本比对、备份验证、服务端身份字段覆盖、repository CAS 更新及响应组装。现迁至 `PlannerRestoreApplicationService`。`PlannerService.restoreDraft(String, RestoreDraftRequest)` 保留兼容入口，转成显式 `PlannerRestoreActor` 并通过 `PlannerRestoreUseCasePort` 委派。Controller 将当前 `UserContext` 显式传成 `PlanningActor`，保留 body/header capability 的既有优先级，并映射用例 status/body。应用实现不依赖 `PlannerService`，ArchUnit 规则禁止新增此反向依赖。

## 2. 保留契约与权威

- 匿名 actor 仍在任何 session 查找/写入前返回 401，原消息与 body 字段保持一致。
- session 查找继续要求 session ID 与 capability；登录用户仍先按 owner 查找，未命中时沿用原 anonymous capability claim fallback。session 不归属当前用户或 capability 不匹配时返回既有 404。
- `expectedVersion` 必须等于 server-held session 的 `currentVersion`；过期/缺失时返回原 409，使用现有冲突响应和指标日志，不写入 session revision。
- formal ID 只取授权 server session 的 `trip.sourceTripId`，并通过当前 actor owner-scoped 读取 formal Trip。未关联 formal Trip、formal Trip 缺失/无权访问或版本不一致时分别保持原有 400/404/409 结果，不调用 session update。
- 版本约束继续比较备份的 `sourceTripVersion` 与当前正式 Trip 的 `currentVersion`。既有 Java 合约不比较浏览器备份中的 `formalTripId/sourceTripId` 是否匹配；成功时忽略这些浏览器身份值并由服务端 `sourceTripId/formalTripId/sourceTripVersion` 覆盖。测试明确验证伪造的备份 ID 不会污染恢复后的身份。没有新增拒绝规则。
- `TripPlanLimits` 仍在任何 repository update 前验证完整备份，异常时返回原 400；保持当前 `new TripPlanLimits(objectMapper)` 的既有校验配置来源，不夹带配置语义变更。
- 成功仍基于客户端确认的备份内容做顶层副本，但 formal identity 与新 `version=currentVersion+1` 由服务端指定；写入仍用 `repository.update(session, restored, currentVersion, "RESTORE_DRAFT", ...)`，保持 CAS、revision/syncRevision 和 mutation 标签。CAS 冲突继续返回原 409。
- 成功 body 仍由 `planResponse` 同语义字段加原消息构成；restore endpoint status/body 保持兼容。Controller 继续在 request capability 为空时回退 `X-Plan-Session-Token`。

## 3. 文件与测试覆盖

CP4.3 新增：

- `PlannerRestoreApplicationService.java`：应用层 Restore 协调、显式 actor 授权、版本门禁、快照权威覆盖、CAS 写入与原响应组装。
- `PlannerRestoreUseCasePort.java`、`PlannerRestoreActor.java`、`PlannerRestoreResult.java`：领域委托 port 和无 Spring/MVC 依赖的身份/结果模型。
- `PlannerRestoreApplicationServiceTest.java`：验证成功后 session ID/同步 revision/新 planner revision、伪造 formal identity 被覆盖；匿名、其他 owner、错误 capability、stale planner/formal version、缺 formal link/Trip 与无效备份拒绝，拒绝场景不调用 session update 或 Trip update。

针对性调整：

- `PlannerService.java`：legacy 入口通过 port 委派，删除原业务流程。
- `PlannerApplicationService.java`、`PlanningApplicationService.java`、`PlannerController.java`：增加显式 actor application route 与协议映射。
- `PlannerDraftRestoreTest.java`：继续覆盖 legacy 兼容入口、server snapshot authority 与旧 revision 拒绝。
- `PlannerControllerTest.java`：验证显式 actor、header capability fallback 和 409 body/status 映射。
- `ColaArchitectureGuardTest.java`：强制 Restore application 实现 port 且不依赖 PlannerService。

共享工作区内 CP4.2 及更早阶段文件包含已有 dirty hunks；tracked patch 按候选清单列出的文件范围生成，因此重叠文件 patch 也包含既有内容。未跟踪的 CP4.2 与更早文件未被修改或覆盖，其身份与本轮新增文件分别列于清单。

## 4. 测试

最终运行在新的临时副本 `scenic-cp4_3-verified-7c268e7ed44e46c8a41a11332771e2e3/ai`。副本从当前树复制，排除 `.git`、`target`、`node_modules`、`build`、`dist`；`clean test` 仅操作该副本，没有清理或使用共享 `D:/scenic-guide/ai/target`。

命令：

```powershell
.\mvnw.cmd -B '-Dtest=PlannerRestoreApplicationServiceTest,PlannerOpenApplicationServiceTest,PlannerSaveApplicationServiceTest,PlannerDraftRestoreTest,PlannerSaveConsistencyTest,TripSaveCapacityRegressionTest,PlannerControllerTest,PlannerServiceTest,ColaArchitectureGuardTest' clean test
```

最终原始日志：[CP4_3_TEST.log](D:/scenic-guide/docs/refactor/CP4_3_TEST.log)。结果：**71 tests, 0 failures, 0 errors, 0 skipped；BUILD SUCCESS**。Restore application 7 项、legacy PlannerDraftRestore 2 项、Controller 18 项及 Architecture Guard 14 项通过；Spring `PlannerServiceTest` 上下文成功启动。

为保留完整测试过程，另存两份诊断日志：首次 clean compile 暴露 Controller test 缺少 `assertFalse` 静态导入，见 [CP4_3_TEST_COMPILE_DIAGNOSTIC.log](D:/scenic-guide/docs/refactor/CP4_3_TEST_COMPILE_DIAGNOSTIC.log)；修复导入后的首轮运行暴露新测试 Mockito update stub 匹配过严、导致 fixture 返回 null，见 [CP4_3_TEST_FIXTURE_DIAGNOSTIC.log](D:/scenic-guide/docs/refactor/CP4_3_TEST_FIXTURE_DIAGNOSTIC.log)。已修正测试 fixture，在另一个全新隔离副本从 clean 重新执行，最终日志全绿。启动时外部 Qdrant 不可用的日志触发既有 RAG 降级；不代表本轮测试访问或验收了真实 Qdrant。

## 5. 未验证与边界

- 没有运行真实 BFF、浏览器 UI、真实 HTTP 客户端往返、PostgreSQL、Redis 或多进程场景。Node 与 frontend 文件没有修改。
- Restore application 测试使用 Mockito 端口验证副作用边界；既有 PlannerDraftRestoreTest 使用 mocked session repository，不构成 PostgreSQL/真实 session-store 事务证明。
- 既有登录用户 anonymous-session claim fallback 保持不变；本轮测试的失败零写入断言针对 formal Trip update 和 planner session revision update，未重新定义 claim 的持久化边界。
- CP4 整体退出要求的 BFF/前端保存→打开→局部调整→保存→重载，以及旧 account/workspace 响应 UI 证据仍待后续切片。

## 6. 冻结

冻结候选：[CP4_3_CANDIDATE.json](D:/scenic-guide/docs/refactor/CP4_3_CANDIDATE.json)；tracked patch：[CP4_3_TRACKED_PATCH.patch](D:/scenic-guide/docs/refactor/CP4_3_TRACKED_PATCH.patch)；起点：[CP4_3_STARTING_CANDIDATE.json](D:/scenic-guide/docs/refactor/CP4_3_STARTING_CANDIDATE.json)。实现者已停止编辑源码，候选交主智能体安排 B/C 复核；不声明 CP4.3 验收通过。

## 7. 经验使用

检索命中并阅读候选主题 `planner-memory-version-conflict`，将 M2 标为 considered；其版本权威提醒与本轮保留现有 `expectedVersion`/CAS 校验相符，但没有新增实现规则。本次结论依据当前 Restore 调用链与最终隔离测试。任务记录：[20261009-70d1835ed9ac.json](C:/Users/summer/Documents/ggy的自动化流程/wiki/tasks/20261009-70d1835ed9ac.json)。

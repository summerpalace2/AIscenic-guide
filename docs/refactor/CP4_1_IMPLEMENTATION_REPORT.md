# CP4.1 Planner Save 应用用例迁移

实施日期：2026-10-09  
状态：候选已冻结，等待 B/C 复核；本报告不代表验收通过。  
范围：仅迁移 Java V1 Planner Save 编排；没有修改 BFF、前端、Open/Restore 或数据库 schema。

## 1. 变更概况

原先 `PlannerService.save` 同时负责读取并授权 planner session、组装正式快照、生成幂等键、调用正式行程保存、将正式 ID 链回草稿，并在并发编辑时重试链接。现由 `PlannerSaveApplicationService` 编排这些步骤。该用例只接收明确的 `PlannerSaveActor`、session ID 和 session capability；没有客户端 trip 入参，因此 V1 仍只保存服务端 session 快照。

`PlannerService.save` 保留为兼容入口，通过 `PlannerSaveUseCasePort` 转发并将同一 `PlannerSaveResult` 转为原有 `ServiceResult`。HTTP Controller 使用显式 actor 的应用入口，将原有 service status 与 body 原样写入 `ResponseEntity`。依赖关系无环：应用用例依赖 session repository port 与 `TripService`，不依赖 `PlannerService`；`PlannerService` 只依赖 domain gateway port。Spring 上下文启动测试证明当前组件可完成注入，ArchUnit 规则守住用例实现与旧 facade 之间不反向调用。

## 2. 保留的保存行为

- 匿名用户仍收到 HTTP 401 与消息“请先登录后保存行程。”，在读取 session 或写正式行程前拒绝。
- 登录用户先按 user ID、session ID 与 capability 查找自有 session；未找到时仍尝试用 capability 将匿名 session 认领给该用户；两者都失败时返回原 404 消息。
- 正式保存输入来自 `StoredSession.trip()`；服务端附加原有 `plannerSessionId`、`plannerVersion` 和 `appliedPreferences`。幂等键保持 `planner-save-{sessionId}-{currentVersion}`。
- 调用现有 `TripService.savePlannerSnapshot`，因此稳定 formal trip ID、`PLANNER_SAVE` / `PLANNER_LINK` 幂等记录、业务快照 NOOP、`sourceTripVersion` CAS/变基以及正式 Trip 版本行为仍由原实现负责。
- 保存结果后仍尝试把 `formalTripId`、`sourceTripId`、`sourceTripVersion` 链回 session；失败时最多重试三次，每次重读授权 session，只更新身份元数据并避免覆盖并发编辑的业务内容。
- 数据库连接瞬断仍按原 session+revision 幂等键完整重试一次。
- 成功 body 保留 `ok`、`trip`、`tripId`、`currentVersion`、`appliedPreferences`、`draftLinked`，只有链接未完成时附加原 `message`。下游失败 body 仍保留 `ok`、`message`、`appliedPreferences` 和可用的 `tripId`、`expectedVersion`、`currentVersion` 字段；状态码仍采用 `TripService.OperationResult.status()`。

## 3. 代码与测试文件

CP4.1 修改/新增的文件清单和 SHA-256 记录在候选清单 [CP4_1_CANDIDATE.json](D:/scenic-guide/docs/refactor/CP4_1_CANDIDATE.json) 中。主要职责分布如下：

- [PlannerSaveApplicationService.java](D:/scenic-guide/ai/src/main/java/com/ai/guide/application/planner/PlannerSaveApplicationService.java)：应用层保存流程、授权 session 解析、幂等键、正式快照写入和 session 链接重试。
- [PlannerSaveUseCasePort.java](D:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/gateway/PlannerSaveUseCasePort.java)、[PlannerSaveActor.java](D:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/model/PlannerSaveActor.java)、[PlannerSaveResult.java](D:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/model/PlannerSaveResult.java)：无 Spring/MVC 依赖的委托端口、身份和结果模型。
- [PlannerService.java](D:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/PlannerService.java)、[PlannerApplicationService.java](D:/scenic-guide/ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java)、[PlanningApplicationService.java](D:/scenic-guide/ai/src/main/java/com/ai/guide/application/planner/PlanningApplicationService.java)、[PlannerController.java](D:/scenic-guide/ai/src/main/java/com/ai/guide/adapter/planner/web/PlannerController.java)：保留旧入口并将 HTTP 路由改为显式 actor 用例。
- [PlannerSaveApplicationServiceTest.java](D:/scenic-guide/ai/src/test/java/com/ai/guide/application/planner/PlannerSaveApplicationServiceTest.java)：验证服务端快照、幂等 key、结果字段、匿名拒绝、匿名 session 认领及旧入口委托。
- `PlannerControllerTest`：验证显式 actor 路由以及原 HTTP 状态/body 映射。
- `PlannerSaveConsistencyTest` 与 `TripSaveCapacityRegressionTest`：保留 SQLite 正式 ID、重复保存、并发编辑和版本不重复增长场景，并通过新的应用用例执行保存。
- `ColaArchitectureGuardTest`：增加 application use case 实现 port 且不调用旧 `PlannerService` 的静态架构规则。

旧共享工作区已有未提交文件全部保留。CP4.1 没有触碰 BFF、frontend 或 schema。由于实现涉及已有 dirty 的 `PlannerService`、Controller 和测试文件，候选 patch 清单明确标识其基于 CP0–CP3 已接受工作树；请在该工作树及对应基线身份上复核。

## 4. 验证结果

Java 测试仅在隔离临时副本执行。副本排除了 `.git`、既有 `target`、`node_modules`、`build` 与 `dist`；`clean` 仅作用于该副本。

执行命令：

```powershell
.\mvnw.cmd -B '-Dtest=PlannerSaveApplicationServiceTest,PlannerSaveConsistencyTest,TripSaveCapacityRegressionTest,PlannerControllerTest,TripServiceTest,PlannerDraftRestoreTest,ColaArchitectureGuardTest' clean test
```

命令在隔离副本的 `ai` 目录执行，原始最终输出见 [CP4_1_TEST.log](D:/scenic-guide/docs/refactor/CP4_1_TEST.log)。结果：**55 tests, 0 failures, 0 errors, 0 skipped；BUILD SUCCESS**。Maven main/test 源码均从空 `target` 编译。首次 clean 编译因 ArchUnit 版本不支持 `ClassesShould.notDependOnClassesThat()` 而失败；规则已改为单独的 `noClasses().should().dependOnClassesThat()` 并重新 clean 测试。首次编译诊断没有作为最终测试日志保留；最终 clean suite 的原始日志已单独保存。

`TripServiceTest` 的 Spring Boot 上下文成功启动并通过 8 项测试；其启动阶段记录了对默认 Qdrant endpoint 的异步 `UNAVAILABLE`/缺集合日志，RAG 明确降级。该日志不导致测试失败，但本轮没有验证真实 Qdrant、PostgreSQL 或生产 BFF。

## 5. 尚未验证

- 完整 BFF 保存 endpoint、登录态及跨端 wire contract；该层本轮保持未修改。
- 浏览器中的保存、打开、编辑、再保存与重载真实交互；本轮只验证 Java adapter 及 SQLite 测试。
- PostgreSQL 真实事务/锁行为、运行中的生产外部服务以及进程级故障恢复。
- CP4 整体退出条件中的 Open / Restore 与 stale account/workspace response；它们属于后续 CP4 切片。

## 6. 候选冻结身份

冻结后未再编辑源码。候选清单记录 HEAD、starting candidate 身份、CP4.1 范围文件 hashes、tracked patch SHA-256、测试日志 hash 与测试命令。由于底层工作区包含 CP0–CP3 的既有 dirty edits，`CP4_1_TRACKED_PATCH.patch` 是从该 HEAD 对本切片涉及的 tracked files 生成的 binary diff，可能同时含这些文件的前序 CP0–CP3 hunks；清单分别列出了 CP4.1 文件 hashes 和全量 tracked patch hash，避免将它误认为只含本切片增量的干净补丁。

本候选现交由 B/C 复核；在复核意见返回前不扩展切片，也不宣布 CP4.1 验收。

## 7. 经验使用记录

本轮检索了应用层用例、依赖倒置与保存/CAS 相关主题，没有找到适用建议，未采用历史经验。任务记录：[20261009-37224220888b.json](C:/Users/summer/Documents/ggy的自动化流程/wiki/tasks/20261009-37224220888b.json)。当前结论依据 CP4 基线、工作树源码和隔离测试日志。

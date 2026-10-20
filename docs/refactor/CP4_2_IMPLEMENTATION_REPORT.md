# CP4.2 Planner V1 OpenFormalTrip 应用用例迁移

实施日期：2026-10-09  
状态：候选已冻结，等待 B/C 复核；本报告不代表验收通过。  
范围：仅迁移 Java Planner V1 正式行程 Open 用例；未修改 BFF、前端、Restore 流程或数据库 schema。

## 1. 变更结果

`PlannerService.openFormalTrip(String)` 原先同时处理登录校验、正式行程 owner-scoped 查询、快照重建、约束/偏好恢复、新规划会话创建与 HTTP 结果 body。现将这段用例协调迁入 `PlannerOpenApplicationService`。旧 `PlannerService.openFormalTrip(String)` 保留为兼容门面，采集当前 `UserContext` 并通过 domain `PlannerOpenUseCasePort` 委派；新 HTTP Controller 直接构造 `PlanningActor` 并调用 application 用例，由 Controller 写出原状态码与 body。

依赖方向避免形成 Spring 循环：`PlannerOpenApplicationService` 只依赖 `TripService`、`PlannerSessionRepositoryPort` 与 `ObjectMapper`，不引用 `PlannerService`；`PlannerService` 仅引用 domain gateway port；上层 `PlannerApplicationService` 才同时持有 legacy facade 与 open port。ArchUnit 规则验证新 application 实现该 port 且不依赖 `PlannerService`。

## 2. 保留的 Open 行为

- 匿名 actor 或缺失 owner identity 在读 Trip / 创建 session 前返回 401，body 仍为 `{ok:false,message:"请先登录后打开正式行程。"}`。
- 空 Trip ID 在查询前返回原 400；以显式 actor 的 userId 调用 `TripService.get(ownerId, tripId)`，缺失或不属于该 owner 的 Trip 返回原 404；以上错误路径都不创建规划 session。
- 可恢复 plan 为空时仍返回原 400，且不创建 session。
- 从一次 owner-scoped `TripService.get` 得到的同一个 `Trip` 中读取 plan、正式 ID、标题和 currentVersion。恢复 plan 仍做深拷贝，写入 `title`、`formalTripId`、`sourceTripId`、`sourceTripVersion`、旧 planner revision 的 `sourcePlannerRevision`、`plannerSessionRestoreSource=RESTORED_FROM_TRIP`，并将新 planner session revision 设为 1。
- 仍从快照重建 constraints/appliedPreferences；快照没有可用偏好时保留 `APPLIED_PREFERENCES_UNAVAILABLE` 降级标记，约束反序列化失败时保留 `CONSTRAINTS_UNAVAILABLE` 标记。prompt 继续使用 `prompt`，为空时回退 `input`。
- session 使用 ownerType=`USER`、当前 ownerId、恢复后的服务端 Trip 快照、现有 `trip-open-{tripId}-{formalTripVersion}-{UUID}` key 与等价 SHA-256 request fingerprint 创建。仓储返回的 capability 仍按旧契约不加入 HTTP body。
- 成功 body 保留原有 `planResponse` 字段和值语义，并补充 `formalTripId`、`formalTripVersion`、`restoredFromTrip=true`、`adjustmentCapability=V1_PROPOSAL`、`legacyMode=false`。HTTP status 仍为 200。

## 3. 本轮文件

本轮逻辑变更位于候选清单 `CP4_2_CANDIDATE.json` 的 CP4.2 文件列表中：

- `PlannerOpenApplicationService.java`、`PlannerOpenUseCasePort.java`、`PlannerOpenActor.java`、`PlannerOpenResult.java`：用例 orchestration、委托端口及协议无关的 actor/result。
- `PlannerService.java`、`PlannerApplicationService.java`、`PlanningApplicationService.java`、`PlannerController.java`：保留 legacy 入口，增加显式 actor 用例路由和 HTTP status/body 映射。
- `PlannerOpenApplicationServiceTest.java`、`PlannerControllerTest.java`、`TripSaveCapacityRegressionTest.java`、`ColaArchitectureGuardTest.java`：身份/快照/版本/无副作用契约、Controller 映射、现有 SQLite 开启流程与依赖边界保护。

共享工作区包含已接受 CP0–CP4.1 的未提交更改。本候选只冻结上列范围文件的 tracked patch，但其中与 CP4.1/更早切片重叠的 tracked 文件 diff 会包含已有 dirty hunks；patch 的明确生成命令、SHA 与全部本轮文件 SHA 记录在候选清单中。其他用户改动均保留。

## 4. 验证

Maven 只在新建的 `%TEMP%` 隔离副本运行；副本通过 robocopy 从当前工作树复制，并排除了 `.git`、所有 `target`、`node_modules`、`build`、`dist`。`clean` 只清理该副本的 target，没有触碰共享 `D:/scenic-guide/ai/target`。

执行命令（在隔离副本 `ai` 目录）：

```powershell
.\mvnw.cmd -B '-Dtest=PlannerOpenApplicationServiceTest,PlannerSaveApplicationServiceTest,PlannerSaveConsistencyTest,TripSaveCapacityRegressionTest,PlannerControllerTest,PlannerServiceTest,PlannerDraftRestoreTest,ColaArchitectureGuardTest' clean test
```

最终原始日志：[CP4_2_TEST.log](D:/scenic-guide/docs/refactor/CP4_2_TEST.log)。结果：**61 tests, 0 failures, 0 errors, 0 skipped；BUILD SUCCESS**。其中 Open application 5 项、Controller 17 项、SQLite TripSaveCapacity 10 项、架构守卫 12 项均通过。`PlannerServiceTest` 的 Spring 上下文成功启动并验证兼容入口；启动检查仍记录 Qdrant endpoint 不可用并维持既有 RAG 降级，这不是本次 Open 用例的验证目标。

## 5. 尚未验证

- 未运行 BFF 或浏览器流程。本轮没有证实 BFF Open session cache、跨端登录态或真实 UI 的 save→open→edit→save 生命周期。
- 没有访问真实 PostgreSQL/Redis、跨进程 session 存储或生产外部服务；测试使用 Mockito 和现有 SQLite 场景。
- 测试验证 controller 直接响应和应用用例，不包含运行服务器后的真实 HTTP 客户端往返。
- CP4 后续 Restore 与前端 account/workspace stale-response 审查仍在 CP4 基线中列为未验证，本轮未触碰。

## 6. 冻结状态

候选身份见 [CP4_2_CANDIDATE.json](D:/scenic-guide/docs/refactor/CP4_2_CANDIDATE.json)，tracked patch 为 [CP4_2_TRACKED_PATCH.patch](D:/scenic-guide/docs/refactor/CP4_2_TRACKED_PATCH.patch)，测试日志为 [CP4_2_TEST.log](D:/scenic-guide/docs/refactor/CP4_2_TEST.log)。本轮未 Git add/commit。实现已停止；候选提交 B/C 复核，收到复核前不宣告 CP4.2 验收通过。

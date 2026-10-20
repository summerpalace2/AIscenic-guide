# CP4.3 独立架构复审

复审日期：2026-10-09  
候选：CP4.3 final candidate，SHA-256 `42b3d72954847ef2b6d273ff54b563a4eb68c00cfc48dc6f435d63e9fe829a7c`。  
范围：Planner Restore 应用用例迁移。只读追踪 Controller → `PlannerApplicationService` → `PlannerRestoreApplicationService` → repository/`TripService` 与旧 `PlannerService` facade；未运行 Maven，未修改任何文件，也未触碰正在由主智能体使用的共享 target。

## 冻结身份核验

- `docs/refactor/CP4_3_CANDIDATE.json` SHA-256：`42b3d72954847ef2b6d273ff54b563a4eb68c00cfc48dc6f435d63e9fe829a7c`，匹配。
- `docs/refactor/CP4_3_TRACKED_PATCH.patch` SHA-256：`04da05079482732a9442494ea9af910d95ebab4a4810640a0c3ff475e2e156d2`，匹配 manifest。
- `docs/refactor/CP4_3_TEST.log` SHA-256：`bb311f8b3b8cd5594664cb5293b1232f0d258a7a5a87c5d34166a86dd65c731f`，匹配 manifest。
- `docs/refactor/CP4_3_IMPLEMENTATION_REPORT.md` SHA-256：`8aec794d1ea0d4e4118087c5bfadfb241e3227a59d519fdd23a3192276eb5084`，匹配。
- manifest 列出的 12 个源文件 hash 全部匹配。
- patch 只覆盖列出的 tracked paths；报告中所述 tracked patch 含较早阶段在重叠文件中的 hunks。新增/未跟踪文件通过 manifest 的单独 hash 核验。

## 裁决

**应用层确实接管 Restore 用例编排；依赖方向无环；权限、版本、快照来源和 session CAS 顺序与报告基本相符。未发现本切片引入的架构阻断项。** 浏览器备份不决定 session 身份或 formal Trip 身份；formal ID 取自已授权服务端 session，Trip 由当前 actor owner-scoped 查询，Trip 版本与备份版本比对后再写 session。正式 Trip 只读，不调用 Trip 更新。

有两个需限制表述的行为边界：

1. 对有合法 guest capability 的登录用户，`findAuthorized` 会先将 anonymous session claim 给用户；后续若 planner revision 过期、Trip 版本冲突或备份非法，session 所有权迁移仍然已经发生。这是既有 claim 流程，并非 Restore 内容写入；不能把这些失败场景笼统描述为“零写入”。
2. formal Trip 版本校验是一次 owner-scoped 读取，然后独立 CAS 更新 PlannerSession；没有跨 Trip/PlannerSession 的原子事务。因此 Trip 若恰好在读取后并发更新，恢复仍可能按刚读到的旧版本完成。后续正式保存仍有 `sourceTripVersion` CAS/变基保护，但当前 Restore 本身不能对两个聚合提供线性化快照。

## 调用链与逐项证据

| 检查项 | 结论 | 证据 |
|---|---|---|
| Controller → app 门面 | 通过 | `POST /sessions/{sessionId}/restore-draft` 保留原路由和请求 DTO；request body token 为空时用 `X-Plan-Session-Token` 回退，body token 非空时优先；从 UserContext 创建 actor 后调用应用服务，并以用例状态/body 建 HTTP response（`ai/src/main/java/com/ai/guide/adapter/planner/web/PlannerController.java:98-109`）。 |
| 用例编排迁移 | 通过 | 新 `PlannerRestoreApplicationService.restoreDraft` 依次做 actor/auth、session revision、server session 来源 formal ID、owner-scoped formal Trip 读取、客户端 source version 比较、备份限额校验、服务端身份字段覆盖、CAS session update、结果组装（`ai/src/main/java/com/ai/guide/application/planner/PlannerRestoreApplicationService.java:24-81`）。旧 `PlannerService.restoreDraft(String, request)` 现在只检查 port 并委托，不再重复实现上述流程（`ai/src/main/java/com/ai/guide/domain/planner/service/PlannerService.java:405-413`）。 |
| 依赖方向/Spring 装配 | 通过 | application 实现 domain port；它依赖 `PlannerSessionRepositoryPort`、`TripService`、校验器与 metrics logger，不依赖 `PlannerService`（`PlannerRestoreApplicationService.java:24-43`）。`PlannerApplicationService` 同时保存 legacy facade 与新 Restore port，并把显式 actor 交给 port（`ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java:51-64,134-143`）；`PlannerService` 的 Spring `@Autowired` 构造器注入 Restore port，旧构造器保留（`ai/src/main/java/com/ai/guide/domain/planner/service/PlannerService.java:88-118`）。ArchUnit 规则要求应用实现该 port 且不依赖 legacy facade（`ai/src/test/java/com/ai/guide/architecture/ColaArchitectureGuardTest.java:201-212`）。Spring `PlannerServiceTest` 在候选所附日志中启动并通过，ArchUnit 14 项通过。 |
| actor / owner / capability | 通过，claim 有副作用说明 | anonymous actor 在查找/修改前返回 401（`PlannerRestoreApplicationService.java:48-51`）。登录 actor 按 session ID、USER owner ID、token 查找；未命中时沿用合法 capability claim guest session（`:84-93`）。Controller 只接受由 UserContext 得到的身份，不接受浏览器传来的 owner（Controller `:106-108`）。Repository 的 claim 对 userId 做非空检查，并以 session、ANONYMOUS owner、token digest 条件单次更新 owner 和 capability（`ai/src/main/java/com/ai/guide/infrastructure/planner/repository/PlannerSessionRepository.java:140-148`）。因此失败分支可能已有这个独立 owner claim，但没有应用备份或提升 revision。 |
| Planner revision 门禁 | 通过 | body 缺失、trip 缺失、expectedVersion 缺失或不等于当前 server session revision 时立即返回 conflict（`PlannerRestoreApplicationService.java:52-55`），早于 formal Trip 读取和 session update。专项测试断言 stale revision 为 409、保留 server trip、没有 session write 或 Trip 查询（`ai/src/test/java/com/ai/guide/application/planner/PlannerRestoreApplicationServiceTest.java:121-129`）。 |
| formal ID 与版本 authority | 通过 | formal ID 只从已授权 session 的 `trip.sourceTripId` 取；通过当前 actor 的 userId 调 `tripService.get`；客户端备份的 `sourceTripVersion` 必须等于读取到的正式 Trip currentVersion，否则 409（`PlannerRestoreApplicationService.java:56-63`）。成功恢复时丢弃浏览器身份字段，改写为服务端 formal ID/currentVersion（`:71-75`）。测试传入伪造 `sourceTripId/formalTripId`，确认输出 ID 来自 server session，Trip 只 owner-read 一次（`PlannerRestoreApplicationServiceTest.java:54-94`）。 |
| CAS、提交顺序和写失败 | 通过本地 CAS；跨聚合并发边界未原子化 | `TripPlanLimits.validate` 在 session update 前运行（`PlannerRestoreApplicationService.java:64-70`）。通过后调用 `sessionRepository.update(session, restored, session.currentVersion(), "RESTORE_DRAFT", ...)`；发生 CAS conflict 即返回冲突，否则以更新结果组装响应（`:75-81`）。真实 repository 的 update 在事务内用 `WHERE session_id=? AND current_version=?` 更新 revision 与 syncRevision，再写 revision history；零行更新时读取最新 session 并返回 conflict（`ai/src/main/java/com/ai/guide/infrastructure/planner/repository/PlannerSessionRepository.java:179-198`）。用例失败测试断言不调用 session update/Trip update；但这些多数是 mock 契约，不是 PostgreSQL 事务故障注入。formal Trip 版本检查与 planner CAS 不共享事务，所以不能声称跨 aggregate 原子一致。 |
| legacy facade / wire compatibility | 通过，保留旧调用方式但依赖注入 port | 旧方法 `PlannerService.restoreDraft(String, request)` 通过 `PlannerRestoreUseCasePort` 调新用例并映射 `PlannerService.ServiceResult`（`PlannerService.java:405-413`）。Controller route、token fallback、status/body 直接映射保持；Controller 测试包括显式 actor、header fallback 和 409 body/status（`ai/src/test/java/com/ai/guide/adapter/planner/web/PlannerControllerTest.java` 中 Restore 相关用例；候选原始测试日志记录 Controller 18 项通过）。旧手工构造器把新 port 置 null，直接调用 facade 会抛出显式未配置异常；生产 Spring constructor 注入 port，测试日志中的 `PlannerServiceTest` Spring context 通过。 |
| 失败副作用与请求约束 | 基本通过，有 claim 限定 | anonymous、无权 session、错 capability、stale planner/formal version、缺 formal link/Trip、非法 plan 的测试均断言不调用 planner revision update 或 Trip update（`PlannerRestoreApplicationServiceTest.java:64-67,97-164`）。登录用户通过 guest capability 的 claim 不被这些断言涵盖，而且会先行发生（实现 `:91-93`）；实现报告在限制段已披露，不应把它算作完全无持久副作用。 |
| 影响范围 | 符合声明范围 | manifest 文件只涉及 Java planner adapter/application/domain 与测试；没有 BFF、frontend 或 schema 路径。实现报告也明确这些端到端路径未测；因此本裁决不覆盖 BFF/browser/真实 HTTP/PostgreSQL/Redis/多进程行为。 |

## 已证实问题、建议与限制

**已证实的边界（不阻断本切片）**：

- 登录用户用 guest capability 恢复时，claim 是授权流程的一部分，会先于 revision/backup/formal freshness 校验发生。失败后 planner 内容未写，但 owner 状态可能已改变。
- formal Trip 的读取与 PlannerSession 的 CAS 分属不同仓储操作。两者间 formal Trip 可并发变化，故 Restore 的检查基于读取时版本；代码没有提供跨聚合原子快照。
- `findAuthorized` 中 `actor.anonymous()` 分支在方法入口已拒绝 anonymous actor 后不可达（`:48-50` 与 `:84-90`）。这是死分支/可维护性建议，不构成权限绕过。
- 结果模型 `PlannerRestoreResult` 使用 `int serviceStatus`（`ai/src/main/java/com/ai/guide/domain/planner/model/PlannerRestoreResult.java:7-11`）：无 MVC 依赖，但 service status 语义仍渗入 domain 模型，属于后续类型边界改善建议。

**验证限制**：

- manifest 的最终隔离测试结果为 71 tests、0 failures/errors/skipped、BUILD SUCCESS；本复审只读取日志，不重跑 Maven。
- 用例测试使用 Mockito 验证多数拒绝路径无 update；repository CAS 逻辑源码确认，但本轮没有独立执行 PostgreSQL/真实 session store 并发故障测试。
- 没有 BFF、浏览器端或真实 HTTP client 验证；共享 target 正由主智能体运行，本复审未访问或操作它。

## 最终结论

CP4.3 候选的 Restore 编排迁移和依赖倒置通过；显式身份、服务端 session/formal ID 权威、expected planner revision、formal version 门禁、限额校验和 planner session CAS 顺序均有源码/测试依据。**在上述跨聚合竞态和 capability claim 副作用边界明确披露的前提下，可判定本切片架构复审通过；不构成整个 CP4 生命周期或跨端流程验收。**

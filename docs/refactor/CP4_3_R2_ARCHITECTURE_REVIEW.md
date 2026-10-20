# CP4.3 R2 独立架构复审

复审日期：2026-10-09  
候选：CP4.3 R2，manifest SHA-256 `72ff4abcb8724ad789c3d86897974d786dbac02b01aeaf4f9c75283c3cfcc959`。  
范围：只读复核 R2 修订，不以 R1 报告替代。未改文件、未运行 Maven，也未触碰主智能体正在使用的共享 target。

## 冻结身份

- `docs/refactor/CP4_3_R2_CANDIDATE.json` SHA-256 `72ff4abcb8724ad789c3d86897974d786dbac02b01aeaf4f9c75283c3cfcc959`：匹配。
- `docs/refactor/CP4_3_R2_TRACKED_PATCH.patch` SHA-256 `04da05079482732a9442494ea9af910d95ebab4a4810640a0c3ff475e2e156d2`：匹配。
- `docs/refactor/CP4_3_R2_TEST.log` SHA-256 `d9ce3c82da7d9fc05e14bb556845a04db079edd8863022079dfaac9825483213`：匹配。
- `docs/refactor/CP4_3_R2_IMPLEMENTATION_REPORT.md` SHA-256 `eee5df37244a8d2a4f81208233bb7e1b8ec911c59f635ffc0fc7393234343f2b`：匹配。
- manifest 列出的 12 个代码/测试文件 hash 全部匹配。

## 裁决

**R2 的三项定点返工在代码层已落地；未发现新架构阻断。** Restore application 确实构造注入 Spring `TripPlanLimits` bean；USER session 授权依赖 owner，而不是 session token；CAS conflict 返回 409 并把最新 session 状态放在响应 `trip` 中；旧 `PlannerService` 入口与 Spring 构造装配仍保留。

证据需要准确限定：custom-limit 用例通过测试手工设置 validator 实例的 `maxDays` 字段并注入服务，证明限额对象影响 Restore，不是从 Spring Environment 的 `trip.plan.max-days` 属性完成端到端绑定验证。生产代码使用 `@Component` 和 `@Value`，且 Restore、TripService 都接收该 bean，因此配置链在源码上成立；最终日志的 Spring 上下文可证明可装配，但没有证明某个自定义属性值由 Spring 配置到达 Restore。

## R1 关注项复核

| 项目 | R2 结论 | 证据与限制 |
|---|---|---|
| TripPlanLimits 注入与配置来源 | **实现修复通过；属性绑定测试证据不完整** | Restore service 构造器现在注入 `TripPlanLimits`，不再在请求内 `new`（`ai/src/main/java/com/ai/guide/application/planner/PlannerRestoreApplicationService.java:24-42,63-68`）。该 validator 是 `@Component`，五项上限用 `@Value("${trip.plan...}")` 读取（`ai/src/main/java/com/ai/guide/domain/trip/service/TripPlanLimits.java:12-29`）；`TripService` 的 Spring `@Autowired` setter 也接收同一类型 bean（`ai/src/main/java/com/ai/guide/domain/trip/service/TripService.java:46-58`）。因此运行时配置路径真实存在。测试 `configuredLowPlanLimitRejectsBackupBeforeSessionUpdate` 手工 `new TripPlanLimits(mapper)` 后用 `ReflectionTestUtils` 设置 `maxDays=1`，再将该实例注入 use case（`ai/src/test/java/com/ai/guide/application/planner/PlannerRestoreApplicationServiceTest.java:184-201`）；它验证 validator 行为传达给 Restore，但没有通过 Spring 属性覆盖验证 `trip.plan.max-days`。应把报告中的“自定义属性已实测到达”表述收窄为“bean 配置代码链已确认，手工注入限额行为已测”。 |
| USER owner 与 capability 语义 | **通过，测试修正方向正确** | `PlannerSessionRepository.find` 按 session ID、ownerType、ownerId 查询；仅对 `ANONYMOUS` owner 比对 capability digest，USER owner 不因 token 不匹配而拒绝（`ai/src/main/java/com/ai/guide/infrastructure/planner/repository/PlannerSessionRepository.java:107-122`）。因此 `userSessionAuthorizationUsesAuthenticatedOwnerRatherThanCapabilityToken` 使用 wrong token 仍找到本人 USER session，正确表达“owner 是授权条件”（`PlannerRestoreApplicationServiceTest.java:125-137`）。对 guest 情况，应用层确实调用 `claimAnonymous`；新测试 mock 该调用返回 null 并断言无 formal Trip read/session revision update（`:108-123`），这是端口级委托测试，不是错误 capability 的真实 JDBC 测试。仓储实现仅在 capability digest 匹配时条件更新，并拒绝空 token/userId（`PlannerSessionRepository.java:140-148`），源码佐证实际拒绝条件。注意合法 guest capability 的 claim 仍会先于后续备份/版本校验发生，失败 restore 可能改变 session owner；实现报告已披露这一边界。 |
| expected planner revision | **通过** | `request`、备份 trip 或 expectedVersion 缺失，以及 expectedVersion 与 server-held currentVersion 不一致，均在 Trip 读取/session update 前返回 conflict（`PlannerRestoreApplicationService.java:48-54`）。新测试确认 stale revision 409、响应保留最新 server trip 且无 Trip 交互和 session update（`PlannerRestoreApplicationServiceTest.java:139-147`）。 |
| formal Trip 版本 authority | **通过，跨聚合竞态仍存在** | formal ID 从授权 server session 的 `sourceTripId` 取；`TripService.get(actor.userId(), formalId)` owner-scoped 读取；备份 `sourceTripVersion` 必须等于读到的 formal currentVersion（`PlannerRestoreApplicationService.java:55-62`）。成功时浏览器身份/版本字段被 server session/formal Trip 覆盖（`:69-75`）。formal Trip 在读取之后可能先于 session CAS 更新；这两个操作没有跨 aggregate 事务，R2 报告把它保留为限制并明确未做屏障测试，表述恰当。 |
| CAS conflict 映射及最新状态响应 | **通过** | CAS 使用 session currentVersion 作为 expected version；`MutationResult.CONFLICT` 转到 conflict 响应，不重试（`PlannerRestoreApplicationService.java:74-79,135-140`）。冲突 body 包含 `ok=false`、消息、sessionId、`trip` 和 preferences；最新 revision/marker 随 `trip` 内容返回，不另给顶层 `syncRevision`（`:123-140`）。R2 测试将 update stub 为 latest revision 4 / marker，并断言 HTTP domain status 为 409、`trip` 是最新状态、update 恰好一次（`PlannerRestoreApplicationServiceTest.java:203-225`）。Controller 将 409/body 原样映射（`ai/src/main/java/com/ai/guide/adapter/planner/web/PlannerController.java:98-109`；对应 Controller 测试位于 `ai/src/test/java/com/ai/guide/adapter/planner/web/PlannerControllerTest.java:116-137`）。 |

## 装配、依赖与旧入口

- `PlannerRestoreApplicationService` 实现 domain gateway port，依赖 session port、`TripService`、`TripPlanLimits` 和 metrics；没有依赖 `PlannerService`（`PlannerRestoreApplicationService.java:24-42`）。
- `PlannerApplicationService` 注入 legacy facade 与 Restore port，并显式把 `PlanningActor` 转为 `PlannerRestoreActor`（`ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java:51-65,139-143`）。`PlannerService` 仍只经同层 port 委托旧入口（`ai/src/main/java/com/ai/guide/domain/planner/service/PlannerService.java:104-118,405-413`）。ArchUnit 保持“用例实现 port 且不依赖 PlannerService”约束（`ai/src/test/java/com/ai/guide/architecture/ColaArchitectureGuardTest.java:201-212`）。
- 候选最终隔离日志报告 74 项通过，其中 `PlannerServiceTest` Spring Boot context 启动且 7 项通过，ArchUnit 12 项通过；我未复跑测试。因为该 Spring 上下文未用自定义 `trip.plan.max-days` 属性覆盖，不能据此声称真实自定义属性注入路径已有集成测试。
- 旧 `PlannerService.restoreDraft(String, request)` 保留并将 `UserContext` 转显式 actor 后委派；旧构造器无 port 时直接调用会抛明确配置异常，Spring 主构造器接收 port。测试覆盖 legacy facade 仍可调用；手工实例需提供 port。

## 结论与建议

本候选在 application 层职责迁移、依赖方向、用户/guest 授权语义、session revision 门禁、formal Trip 版本检查和 CAS 冲突响应方面通过复审；没有发现 R2 新引入的架构阻断项。两个既知边界保留并披露恰当：合法 guest capability claim 是早于 Restore 内容检查的 owner 状态迁移；formal Trip read 与 PlannerSession CAS 之间存在未原子化窗口。

剩余验证建议属于证据补强：加一个 Spring context test 用 `@TestPropertySource(properties="trip.plan.max-days=1")` 或等效动态属性真实配置 bean，走 Restore service 并证明两天备份拒绝。当前手动字段覆盖测试可证明校验器被用到，不能证明 Spring 外部配置绑定链。

# CP4.3 R2 Planner Restore 红方复审

日期：2026-10-09  
范围：仅审查 CP4.3 R2 冻结候选的 Java Restore application、仓储授权/ CAS 调用点及兼容入口。只读；没有修改源码/测试，也没有运行 Maven。  
R2 manifest SHA-256：`72ff4abcb8724ad789c3d86897974d786dbac02b01aeaf4f9c75283c3cfcc959`（匹配）  
tracked patch SHA-256：`04da05079482732a9442494ea9af910d95ebab4a4810640a0c3ff475e2e156d2`（匹配）  
implementation report SHA-256：`eee5df37244a8d2a4f81208233bb7e1b8ec911c59f635ffc0fc7393234343f2b`（匹配）  
raw test log SHA-256：`d9ce3c82da7d9fc05e14bb556845a04db079edd8863022079dfaac9825483213`（匹配；只读 manifest 记录，未复跑）  
manifest 列出的 12 个实现/测试文件 SHA-256 全部匹配。

## 裁决

**CP4.3 R2 Restore application：PASS（限于 Java 用例迁移和本候选静态审查范围）；没有发现阻断缺陷。** R2 对 R1 的三个重点问题做了有效闭环：Restore 现在注入 Spring 管理的 `TripPlanLimits`；CAS conflict 有明确 409/最新状态/单次提交尝试断言；匿名 guest 错 capability 与已登录 USER owner 的授权语义分别表达，且历史真实 JDBC 仓储测试验证错误 token 不会 claim 未认领 guest session。成功恢复仍只写 planner session，服务器覆盖 formal identity/version；stale planner/formal version、缺正式关联、Trip 不存在及低配置限额都在 update 前拒绝。

这不是对 CP4 总体的验收：R2 自己注明 formal Trip 查询与 planner session CAS 之间没有跨聚合事务或屏障测试；也没有真实 HTTP/BFF/browser 或本次运行的实时 PostgreSQL 证明。该窗口可能令恢复返回成功但保存时才暴露 formal version 冲突，不过不会由 Restore 覆盖正式 Trip。

## 候选身份核验

- `CP4_3_R2_CANDIDATE.json` hash 与派发值匹配。
- manifest 中 tracked patch、实现报告和原始测试日志 hash 与当前文件匹配。
- manifest `fileSha256` 的所有 12 个文件均匹配。
- manifest 记录隔离 `clean test` 为 74 tests、0 failures、0 errors、0 skipped。本次没有重新执行，不能把该统计表述为独立复跑。

## 重点反例与证据

### 1. 配置化 TripPlanLimits：R1 缺陷已修复

- `PlannerRestoreApplicationService` 现在构造注入 `TripPlanLimits planLimits`，备份校验通过 `planLimits.validate(restored)` 执行（`PlannerRestoreApplicationService.java:27-43, 64-70`）；不再在每次 Restore 内 `new TripPlanLimits`。
- `TripPlanLimits` 是 Spring `@Component`，限额字段有 `@Value("${trip.plan.*:default}")` 注入（`TripPlanLimits.java:12-24`）。Spring 运行时将同一个配置 bean 装配到 Restore use case。
- `configuredLowPlanLimitRejectsBackupBeforeSessionUpdate` 创建 maxDays=1 的限额对象，提交两天备份，检查返回 400、消息显示 1 天上限、没有 session update 或 Trip update（`PlannerRestoreApplicationServiceTest.java:178-200`）。这个反例直接证明 Restore 使用调用方注入的限额对象，并且限制失败在 planner 写入前。
- 测试通过 ReflectionTestUtils 配置 bean 字段，没有构造 Spring context 验证外部 properties 绑定；但生产类的 `@Component`/`@Value` 与构造注入链明确，未见绕过点。剩余是配置绑定集成覆盖缺口，不是已发现错误。

### 2. CAS MutationResult.conflict：R1 缺陷已修复到应用层；底层无 revision 副作用由仓储实现支撑

- `updateCasConflictReturnsLatestStateWithoutRetryingUpdate` 模拟竞态中最新 server session 为 version 4，并带 `latest-server-state` 标记；断言返回 409，回执包含最新 sessionId/trip version/marker，且 `sessions.update` 精确调用一次（`PlannerRestoreApplicationServiceTest.java:203-225`）。application 不重试，不会按旧版本重放浏览器备份。
- JDBC 仓储 `PlannerSessionRepository.update` 以 `WHERE session_id = ? AND current_version = ?` 执行 CAS。影响行数为 0 时只读取最新 session、返回 `MutationResult.conflict(latest)` 并提前退出；只有影响行数非零才插入 `planner_plan_revision`（`PlannerSessionRepository.java:181-199`）。静态代码顺序显示 CAS 失败不增加 revision；候选用例验证 application 不重复调用，但没有对 conflict 分支使用真实数据库查询 revision_count。
- `conflict()` 返回最新 trip/version，不附加顶层 `syncRevision`；R2 测试按实际 wire helper 在 trip 中检查最新标记，没有误断言顶层 `syncRevision`。这一响应行为与当前错误响应构造一致。

### 3. Guest wrong token 与 USER owner：授权语义被正确拆开

- `anotherOwnerCannotRestoreAndWrongCapabilityCannotClaimAnonymousSession` 将错 token 场景建模为 USER lookup miss 后，`claimAnonymous(sessionId, wrongToken, ownerId)` 返回 null；断言 404、验证 claim 调用，并确认没有 Trip 读取和 session update（`PlannerRestoreApplicationServiceTest.java:105-120`）。这是 Mockito 编排测试，并不单独证明数据库 token 检查。
- 同类新增 `userSessionAuthorizationUsesAuthenticatedOwnerRatherThanCapabilityToken` 明确断言：相同 owner 的 USER session 即便带错 token，owner 查询仍成功；用 stale expectedVersion 收口为 409，确认不 claim、不读 formal Trip、不写 session（测试 122-136 行）。这与真实仓储行为一致：`PlannerSessionRepository.find` 对 USER 行按 owner 查询，对 ANONYMOUS 行才验证 token hash（`PlannerSessionRepository.java:112-126`）。
- guest 错 token 的真实仓储边界有既有 JDBC 测试 `DeepAuditRegressionTest.capabilityClaimIsAtomicAndRevokesAnonymousAccess`：对尚未认领 guest session 用 wrong token 调用 `claimAnonymous`，断言 null；同测试还用两个线程竞争有效 token 并断言只有一个 owner 获胜。另有 `PlannerSessionRepositoryTest` 检查 `findAnonymousByAccessToken("wrong-capability") == null`。这些测试不在 R2 manifest 的定向 Maven 类列表中，也未在本次执行；它们是当前源码中的真实仓储覆盖，不应冒充 R2 应用流程端到端证明。
- 应用层在 `actor.anonymous()` 时立即返回 401，任何 session/Trip 端口都不调用（`PlannerRestoreApplicationService.java:48-49`；`anonymousActorIsRejectedBeforeAnySessionLookupOrWrite`，测试 100-108 行）。Restore 面向登录用户；有效 guest token 需通过登录用户 claim flow。

### 4. 成功恢复、伪造身份、stale 版本和拒绝时写入：PASS

- 成功路径基于服务端 session 的 `sourceTripId` owner-scoped 读取 formal Trip。读取不到关联 Trip 返回 400，不存在或不归当前 owner 返回 404；备份 `sourceTripVersion` 与当前读取版本不匹配返回 409（`PlannerRestoreApplicationService.java:52-63`）。各拒绝用例断言 session update 未发生：`stalePlannerRevisionIsRejectedBeforeFormalTripRead`、`staleFormalVersionIsRejectedWithoutSessionWrite`、`missingFormalLinkOrUnownedTripIsRejectedWithoutSessionWrite`。
- 备份中伪造的 `sourceTripId`、`formalTripId` 与 `version` 被覆盖为服务端 session/formal ID、当前 formal version 和 session revision + 1（源码 `PlannerRestoreApplicationService.java:71-77`）。`restoresValidatedBackupIntoAuthoritativeSessionAndFormalIdentity` 检查这三个值及编辑内容仍保留（测试 73-98 行）。
- 缺少 days 的结构无效备份返回 400 且不调用 update（`invalidBackupIsRejectedWithoutSessionWrite`，测试 166-176 行）。配置上限两天到 maxDays=1 的测试同样在 update 前拒绝（上文）。没有发现这些 serial rejection 分支会写 planner revision 或正式 Trip。
- user owner 的错误 token 仍有权读取其 USER session；这是有意的 owner authorization，不能把 capability 当作登录用户的第二把必须密钥。若产品协议想要所有 owner 请求都强制 session capability，需要另立约定；当前代码、仓储和测试对这一点一致。

### 5. Formal Trip 更新竞态：遗留且被明确披露，列为非阻断集成边界

- Restore 顺序仍是 `TripService.get(ownerId, formalId)` → 比对 backup sourceVersion → planner session CAS update（`PlannerRestoreApplicationService.java:56-77`）。formal Trip 可在读取后、planner update 前推进版本，此请求仍会将读取时版本写入 planner session 并返回成功。
- Restore 不调用 `TripService.update`；所以该竞态不会回写/覆盖正式 Trip。后续 `PlannerSaveApplicationService` 的 source version 检查会拒绝旧来源保存。R2 manifest 与实现报告均明确没有跨聚合事务或屏障测试。
- 该限制符合已有分离存储/版本语义，当前没有证据证明导致正式数据损坏；但缺少可控并发测试，恢复成功后立即保存的 UX 风险仍未实证。列为非阻断缺口，不把它描述成已验证的原子一致性。

## 场景矩阵

| 场景 | R2 证据 | 结论 |
| --- | --- | --- |
| Anonymous actor | application test 无端口交互 | PASS |
| Other owner | application test owner miss + claim miss，无 Trip/update | PASS（mock application 层） |
| Guest wrong token | application test 验证 claim 调用与拒绝；现有 JDBC 测试验证错误 token claim 返回 null | PASS（仓储证据非本轮重跑） |
| USER owner 携带错误 token | 新测试按 owner 授权，stale 版本 409，不 claim、不写 | PASS；测试表达正确 |
| Stale planner revision | formal Trip read 前 409、无 update | PASS |
| Stale formal Trip version | formal read 后 409、无 session update | PASS（非并发） |
| Formal Trip 改版竞争 | get 与 session CAS 之间没有协调屏障 | NOT VERIFIED；已披露，非阻断 |
| Missing association / missing or unowned Trip | 400 / 404，无 session update | PASS（mock boundary） |
| Forged backup source/formal ID and version | 服务端身份覆盖及成功返回内容断言 | PASS |
| Configured low plan limit | 注入 maxDays=1 对象，2 天请求 400 且零 update | PASS（use case 层）；Spring property wiring未做单独 context 断言 |
| Invalid structure | 缺 days 返回 400、无 session update | PASS |
| CAS update conflict | latest server trip 在 409 返回，update 调用一次；SQL conflict 分支在 revision insert 前返回 | PASS（应用断言+仓储静态证据；无真实 DB conflict 用例） |
| Header fallback / wire status | `PlannerControllerTest.restoreDraftPassesExplicitActorAndUsesHeaderCapabilityFallback` 直接验证空 body token 使用 header、409 body/status | PASS（direct controller，不是 HTTP client） |
| Legacy Java facade | `PlannerDraftRestoreTest` 经旧签名验证成功及 stale rejection | PASS（mock repository/trip） |

## 未验证范围

1. 本次审查不运行 Maven；隔离测试统计仅来自已哈希日志。
2. 缺少 R2 Restore 端到端真实 JDBC CAS 冲突断言（revision_count 与 session snapshot 前后比对）；应用层无重试有精确 mock 调用计数，仓储 SQL 的冲突分支由只读检查核对。
3. formal Trip read 与 planner session CAS 不是原子操作，没有屏障并发测试；该风险已由实施报告明示。
4. 没有真实 HTTP、BFF、浏览器/本地备份生命周期、PostgreSQL 服务或多进程验证。

## 最终意见

R2 已实质修复前轮三个重点：配置限额通过构造注入生效；CAS conflict 返回当前服务器状态且不重试；guest capability 与 USER owner 规则分开建模，错误 guest token 的真实 repository 拒绝另有 JDBC 测试支持。成功路径保留 server identity 与 version 权威，当前可见拒绝路径在 session update 前返回。未见需要阻止 CP4.3 Java Restore 切片的缺陷；主智能体可将本候选作为应用用例迁移验收依据，但必须把跨 formal Trip/session 的竞态和真实 HTTP/BFF/browser 场景保留为 CP4 总体验收边界。

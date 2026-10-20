# CP4.2 Planner Open 红方复审

日期：2026-10-09  
范围：只读审查 CP4.2 冻结候选 OpenFormalTrip 应用用例；未运行 Maven，未修改代码或测试。  
候选 manifest SHA-256：`cc12654db2d49efea316efcf485acec3924796a6dc561be4504ba51a798c8553`（匹配）  
Tracked patch SHA-256：`fdde058fd1fa24a5290a095d238ad9fa941c48a15e35a81d5834715d84f4e053`（匹配）  
Final test log SHA-256：`48807d892cc1112bec5041baedcef1341298a96de063ce6353f41606e1a96985`（匹配）

## 裁决

**CP4.2 Open 应用用例迁移：PASS，未发现阻断缺陷。**

在审查范围内，Open 的授权边界清楚：anonymous、空 owner 在 Trip 读取和 session 创建之前拒绝；formal Trip 使用 actor owner-scoped lookup；缺失/非归属 Trip 和空快照不会创建规划 session。成功路径从单次 `TripService.get(ownerId, tripId)` 返回的同一个 Trip 对象中读取计划、title 和 formal currentVersion，深拷贝后建立一致的新 session：规划 revision 重置为 1，`sourcePlannerRevision` 保留原 plan version，`sourceTripVersion`/formalTripVersion 保留同一 formal Trip 的当前版本。Controller 将明确 actor 传给用例，并按结果 status/body 返回；旧 `PlannerService.openFormalTrip` 保留为委托兼容入口。

重复 Open 不做请求级幂等：restore key 带 UUID，所以每个调用都会创建一个新的 planner session。这与已有 Open 行为一致，也符合“重新打开生成新草稿”的现有测试；并发或客户端重试可能留下多个独立 session，但不会改写 formal Trip 或让 session 混用两个 formal 版本。本轮没有明示要求 Open 去重，因此这是非阻断行为/产品契约观察，不判为实现回归。

## 身份与测试证据

候选 manifest、patch、日志哈希及 manifest 列出的所有 12 个实现/测试文件 SHA-256 均与当前文件匹配。manifest/日志记录在隔离副本执行 **61 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**；本次只读取日志，未重新运行。

## 逐项审查

### 1. 授权、无副作用拒绝：PASS

- `PlannerOpenApplicationService.openFormalTrip` 先拒绝 null actor、anonymous actor 与 blank owner ID（`PlannerOpenApplicationService.java:44-46`），再检查空 Trip ID（47-48 行），之后才做 `tripService.get(actor.userId(), formalTripId)`（50 行）。
- `formal == null` 返回原 404（51-52 行）；恢复快照复制后为空返回 400（54-56 行），这两类拒绝均发生在 `sessionRepository.create` 之前（70 行）。
- `PlannerOpenApplicationServiceTest` 覆盖 anonymous 与 authenticated blank identity，均 `verifyNoInteractions(trips, sessions)`；missing/unowned trip 覆盖 Trip read 一次且 sessions 无交互；empty snapshot 覆盖 session repository 无交互（测试方法 `anonymousActorIsRejectedBeforeTripReadOrSessionCreation`、`authenticatedActorWithoutOwnerIdentityIsRejectedWithoutSideEffects`、`missingOrUnownedTripDoesNotCreateSession`、`emptyFormalSnapshotDoesNotCreateSession`）。
- 负向路径没有单独用真实 TripRepository/SQL 做跨 owner 数据隔离测试，但应用层调用 owner-scoped `TripService.get(ownerId, id)`，而现有 TripService ownership tests 负责底层归属门禁；此处无迹象能通过 session capability 绕过正式 Trip 的 owner check。

### 2. 单一权威快照、版本与来源元数据：PASS

- 成功流程只查询一次 formal Trip，随后对 `formal.plan()` 深拷贝，并在同一返回对象上取 `formal.title()`、`formal.id()`、`formal.currentVersion()`（`PlannerOpenApplicationService.java:50-64`）。没有从客户端接收 Trip/body 快照，也没有二次查询导致 version 与 plan 跨时点拼接。
- 元数据设置为 `formalTripId == sourceTripId == formal.id`、`sourceTripVersion == formal.currentVersion`、`sourcePlannerRevision == prior plan.version`，最后 `version=1`（测试 58-64 行）。服务测试用 formal version 7、source plan version 4 断言三者分别保留，且新 planner revision 变成 1（`PlannerOpenApplicationServiceTest.java:41-69`）。
- `PlannerServiceTest.opensFormalTripAsEditablePlannerSessionAndSavesBackToSameTrip` 集成用例验证重开创建不同 session、formal Trip version 保持不变，并可继续 preview（`PlannerServiceTest.java:195-235`）。
- response 的 `formalTripVersion` 来自同一 formal Trip；planResponse 的 trip 使用 repository 创建后返回的 session trip。正常 repository create 会保存相同恢复快照；应用 service mock 测试也构造了从传入 `restored` map 派生的 StoredSession。没有发现返回 session 元数据与 formal 版本错配路径。

### 3. 重复 Open 与竞态：无阻断缺陷；有明确非幂等语义

- restore key 是 `trip-open-{tripId}-{formalVersion}-{UUID}`（`PlannerOpenApplicationService.java:67`），所以同一请求的重复调用不会被 create 幂等记录合并，会生成两个独立草稿。旧 `PlannerService` 实现同样使用 trip/version/UUID；这是迁移保留行为。
- `PlannerServiceTest` 明确断言重开 session ID 与原 session ID 不同（第 215-223 行）。本轮没有重复 POST/并发双击专项测试，所以没有证据表明多个 Open 响应只会产生一个 session；按照当前实现，多个都会产生 session。
- formal Trip 更新并发发生在 `get` 与 `create` 之间时，当前 session 仍完整源自该次 get 返回的旧版本 snapshot，并把同版本写入 `sourceTripVersion`/`formalTripVersion`；不会把新 formal version 与旧 plan 混在一起。后续 Save 可通过已有 stale-sourceVersion CAS/rebase 检查冲突。
- 是否需要同一客户端请求重放返回同一个 Open session 是 API 契约决定。当前执行计划要求 Open 产生服务端一致快照并保持字段契约，没有要求 Open 去重；重复调用生成新 session 因此为非阻断观察。若 BFF/浏览器需要抵御网络重试造成的孤立草稿，应另行设计显式 open request idempotency，而不能误称现状具备。

### 4. Controller contract 与兼容入口：PASS（直接 Controller 测试）

- HTTP 入口为 `POST /trips/{tripId}/open`，从 UserContext 构造 PlanningActor，将 actor 和 path ID 传给 application service，再按 `serviceStatus()` 原样写 status/body（`PlannerController.java:90-95`）。
- `PlannerControllerTest.openFormalTripDelegatesToApplicationService` 验证 actor owner、formal trip ID、200 status 和 body 原样返回（测试 96-110 行）。
- `PlannerService.openFormalTrip(String)` 保留旧签名，转换当前 UserContext actor 后委托 `PlannerOpenUseCasePort` 并映射回原 `ServiceResult`（`PlannerService.java:575-584`）。其兼容行为还由 `PlannerServiceTest` 的旧调用端到端 Spring 上下文用例覆盖。
- Controller 测试为直接调用 Controller，并非 MockMvc/真实 HTTP 客户端；因此路由/filter/BFF 组合契约仍未验证，但 CP4.2 没修改 BFF，且这不构成该应用用例切片的阻断。

## 非阻断观察 / 仍未验证

1. 同一 formalTripId/version 重复请求会生成多个 session；没有重复/并发 Open 的专用测试。此为现存行为而非本候选引入的回归，但 API 未提供调用级幂等。
2. 缺少“正式行程在 Trip get 与 session create 之间更新”的确定性屏障测试；代码仍用同一次读取产生自洽的旧版本 session，后续保存依赖既有 CAS/变基处理。
3. Controller 证据是 direct invocation，不覆盖 Spring MVC 路由、认证 filter、BFF 或真实浏览器。
4. Restore 生命周期、BFF cache、UI stale account/workspace 响应、真实 PostgreSQL/Redis、多进程行为均不在 CP4.2 范围；候选报告已列明。
5. `restoreConstraints` 与偏好恢复失败时共用单一 `plannerSessionRestoreDegradation` 字段；若两个子系统同时损坏，后设置的 `APPLIED_PREFERENCES_UNAVAILABLE` 会覆盖前一个 `CONSTRAINTS_UNAVAILABLE`。此行为在 HEAD 中旧 Open 实现已存在，本迁移未引入；若要求累计所有降级原因，应单独改为列表/组合标记。

## 最终意见

CP4.2 在 Open 用例迁移范围内正确保持了 owner 授权、服务端单快照一致性、来源版本与 planner revision 语义、错误路径零 session 写入、Controller status/body 以及旧 facade 兼容。未发现阻断缺陷，可交主智能体整体验收。重复 Open 的 UUID 非幂等、真实 HTTP 和 BFF/UI 链路仍应作为 CP4 总体验收的边界记录。

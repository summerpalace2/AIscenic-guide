# CP4.1 Planner Save 红方复审

日期：2026-10-09  
范围：只读审查 CP4.1 Java Planner Save 用例迁移与对应测试；未运行 Maven、未修改代码/测试。  
候选 manifest SHA-256：`87a48be29dc06438e670dfae9a4bba11aa63489fbc880ec458ac315a0bb09d00`（匹配）  
Tracked patch SHA-256：`27ea7183a4d65edd81344920edf92a4a1c7c018b69d8e3068e0f6cd739efb4fd`（匹配）  
Final test log SHA-256：`5559d1103c4c1e110657c41ba422e9f7a60afa5eca6d3d37820084254a310017`（匹配）

## 裁决

**CP4.1 Java Save 用例迁移：PASS（范围内未发现阻断缺陷）；少数真实仓储/授权组合场景仍属非阻断覆盖缺口。**

保存用例现在由 application service 持有编排，正式保存输入始终从授权后的服务端 planner session 读取；不接受客户端 trip。身份、capability、正式 Trip 写入和 session metadata 链接顺序清楚。现有幂等键按 session revision 稳定生成，重复点击和链接失败后重试会命中原正式 Trip；业务无变化由 TripService 的业务快照比较保持 NOOP，不额外增版。并发编辑链接失败时只重读最新 session 并补身份元数据，不拿旧快照覆盖较新的草稿业务内容。

测试记录为隔离副本 **55 tests, 0 failures, 0 errors, 0 skipped**；本审查只读取记录，没有复跑。没有发现将客户端内容写入正式行程、跳过 capability 检查或兼容入口改变 status/body 的实现路径。

## 冻结身份

候选 manifest、tracked patch、test log 哈希均与委托值一致。manifest 列出的 14 个实现/测试/报告文件 SHA-256 也全部与当前文件匹配。工作树有 CP0–CP3 既有 dirty changes；本报告只评价 manifest 所指 CP4.1 slice，不将整个 tracked patch 误称为仅含 CP4.1 的差异。

## 关键调用路径和行为

### 授权及服务端快照：PASS

- Controller `PlannerController.save` 从 `UserContext` 构造显式 actor，调用 `PlanningApplicationService.save(actor, sessionId, token)`，以结果的 service status 和 body 原样构造 HTTP 响应（`PlannerController.java:140-145`）。
- Planning facade 将 `PlanningActor` 转成 `PlannerSaveActor` 后委托 `PlannerSaveUseCasePort`（`PlannerApplicationService.java:106-109`）；没有再调用旧 `PlannerService` 实现。
- `PlannerSaveApplicationService.save` 对 null/anonymous actor 先返 401；owner session lookup 未命中后，只使用请求 capability 尝试 claim；两者都未授权则返 404。正式 Trip 写入前没有副作用（`PlannerSaveApplicationService.java:28-31,74-77`）。底层 `PlannerSessionRepository.claimAnonymous` 要求有效非空 capability 和 userId，并用 session ID + capability digest 的条件 UPDATE 建立所有权边界（`PlannerSessionRepository.java:141-148`）。
- 写入 payload 由 `StoredSession.trip()` 的浅拷贝构造，只补 `plannerSessionId`、session revision 与服务端 `appliedPreferences`；用例没有接收或合并客户端 plan 字段（`PlannerSaveApplicationService.java:33-39`）。服务测试用客户端无入口的实际 method signature 和 expected server snapshot equality 检查此契约（`PlannerSaveApplicationServiceTest.java:44-76`）。

### 重复保存、稳定正式 ID、NOOP 版本：PASS

- 幂等键固定为 `planner-save-{sessionId}-{currentVersion}`（`PlannerSaveApplicationService.java:38`）。TripService 在该 key 下重放，并用 `PLANNER_LINK` 持久记录恢复 formal ID（`TripService.java:305-359`）。
- `TripSaveCapacityRegressionTest.repeatedPlannerSaveBeforeAndAfterIdLinkingStaysAtVersionOne` 通过新 PlannerSaveApplicationService → PlannerService compatibility facade → 真实 SQLite TripService 保存三次，断言 Trip 只有一条、version=1；再在 session revision 更新后保存两次，断言仍是同一 Trip、version只到2（测试 72-98 行）。
- 新 key 的 metadata-only 保存由 `metadataOnlySaveWithNewKeyIsNoopButCustomFieldsAndPreferencesStillCount` 检查 `NOOP`；自定义字段和偏好发生变化时则 `UPDATED` 且增版（测试 134-150 行）。相同 key、业务内容不同则 conflict 且 Trip version 不变（测试 100-107 行）。
- `PlannerSaveApplicationServiceTest` 还验证 revision=7 进入生成的幂等键，成功 body 保留原字段及 TripService 状态码（测试 44-77 行）。

### 并发草稿编辑与身份链接：PASS（编排覆盖；session CAS 是 mock）

- 首次 Trip 保存完成时，测试模拟 session 已从 version 1 更新为“edited during save” version 2；第一次 link 对旧版本拒绝，应用用例重新读授权 session，以最新 plan 补充 formal ID 元数据并再次 link（`PlannerSaveConsistencyTest.java:45-72`）。断言新内容保留、正式 Trip ID 已链上，第二次 save 将同一 formal Trip 更新到最新标题，未重复创建 Trip（测试 73-75 行）。
- 此路径覆盖了“不用旧快照覆盖并发编辑”和后续保存更新同一 formal ID。但测试里的 `PlannerSessionRepositoryPort` 是带 revision 比较逻辑的 Mockito stub，不是生产 JDBC session repository 并发竞争；正式 Trip 写入使用隔离 SQLite。故这是应用编排与 Trip 持久层的组合测试，不是完整双仓储并发集成测试。
- link 多次失败时上限为三次；若仍未链接则返回保存成功但 `draftLinked=false` 并带可见说明，不会虚报 link 成功（`PlannerSaveApplicationService.java:49-69`）。该极限分支未被本轮用例直接覆盖，属非阻断测试缺口。

### 瞬时数据库失败和事务：PASS（有限 SQLite 证据）

- 应用层对 `DataAccessResourceFailureException` 使用相同 session+revision key 重试一次（`PlannerSaveApplicationService.java:41-49`）。TripService 的 save key 和 formal ID link 在其 transaction/幂等存储中处理（`TripService.java:316-359`）。
- `PlannerSaveConsistencyTest` 验证 Trip 写入与 link identity 在事务异常时一起回滚（测试 95-104 行）；另以 SQLite 并发 reservation 测试验证两路相同请求最后只有一个 formal Trip，并对 SQLite contention 重试（测试 106-122 行）。后一个测试直接调用 TripService，不覆盖应用服务捕获的 `DataAccessResourceFailureException` 恰好发生在提交前/提交后的全部驱动层故障位置；没有 PostgreSQL 或生产锁证据。

### HTTP status/body 与兼容入口：PASS（字段保持；wire test 为单测适配器）

- `PlannerControllerTest.saveDelegatesExplicitActorAndPreservesStatusAndBody` 断言 Controller 把 user actor、session ID 和 token 传入应用入口，并原样保留 201 与 body（测试 161-179 行）。实际返回异常映射代码在 controller 中是 status/body 原样输出（`PlannerController.java:143-145`）。
- `PlannerSaveApplicationServiceTest.legacyPlannerServiceSaveDelegatesToConfiguredUseCaseWithCurrentActor` 验证旧 `PlannerService.save(sessionId, token)` 仍通过 `PlannerSaveUseCasePort` 使用当前 UserContext 构造 actor，并保持原 `ServiceResult` 状态和 body（测试约 108-129 行；实现 `PlannerService.java:542-549`）。
- 这些是 controller/use-case 单测，不是 MockMvc HTTP 请求；对路由、认证 filter 与 BFF 的端到端契约需后续验证，当前 CP4.1 没改这些边界。

## 非阻断覆盖缺口

1. 没有明确测试“登录用户使用错误/他人 capability 访问其他 session”最终 404、正式 Trip write=0；现有代码查 owner session并以 capability 条件 claim，路径安全，但负向组合应由 repository/use-case 集成测试固定。
2. link 失败达到三次后的 `draftLinked=false` 和兼容消息未直接断言。
3. 同一保存请求在真实生产 session repository 上与调整并发的 JDBC 双仓储竞态未覆盖；当前测试使用 Mockito 模拟 CAS 结果，并用 SQLite TripService 验证 formal Trip 侧。
4. BFF、浏览器、POST/CSRF、Open/Restore 及前端旧响应污染均不在 CP4.1 范围，manifest 和实施报告已如实列为未验证项；这不阻断当前 Java Save slice，但不能据此宣布 CP4 整体完成。

## 最终意见

按 CP4.1 Java Save 迁移范围，身份验证、服务端快照、重复保存/NOOP、正式 ID 稳定性、并发编辑不丢内容、结果映射和旧入口兼容都有明确实现及对应证据，未发现阻断问题。可接受 CP4.1 进入主智能体整体验收；在 CP4 总体验收前仍需完成 Open/Restore、BFF/frontend 和真实保存→打开→局部调整→再保存→重载路径。

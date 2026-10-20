# CP4.1 独立架构复审

复审日期：2026-10-09  
范围：冻结的 CP4.1 Planner Save 候选。只读检查源码、候选 manifest、实现报告与原始测试日志；未运行 Maven，也未修改业务代码。

## 冻结身份

候选身份与主审提供值一致：

- `docs/refactor/CP4_1_CANDIDATE.json`：`87a48be29dc06438e670dfae9a4bba11aa63489fbc880ec458ac315a0bb09d00`
- `docs/refactor/CP4_1_TRACKED_PATCH.patch`：`27ea7183a4d65edd81344920edf92a4a1c7c018b69d8e3068e0f6cd739efb4fd`
- `docs/refactor/CP4_1_TEST.log`：`5559d1103c4c1e110657c41ba422e9f7a60afa5eca6d3d37820084254a310017`
- manifest 所列 14 个源文件/实现报告的 SHA-256 均与当前文件匹配。

此 tracked patch 是从 HEAD 到候选涉及 tracked 路径的差异，可能包含这些重叠文件中既有 CP0–CP3 改动；本复审不把它误称为纯 CP4.1 增量。

## 裁决

**未发现 CP4.1 范围内的架构阻断项，可进入后续验收；这不是 CP4 整体完成或生产端到端验收。** 应用层拥有 Planner Save 的编排，保存快照来自授权后的服务端 session；端口依赖没有形成编译层循环；正式行程 ID、NOOP、幂等与版本 CAS 仍由既有 `TripService`/repository 路径负责。Controller 的保存路由仍为原 POST 路径并继续传出原 service status 与 body。

## 逐项审查

| 项目 | 裁决 | 源码证据与边界 |
|---|---|---|
| 应用/领域依赖方向与环 | 通过 | `PlannerSaveApplicationService` 在应用包实现领域 gateway `PlannerSaveUseCasePort`，只依赖 session repository port 与 `TripService`，没有反向调用 `PlannerService`（`ai/src/main/java/com/ai/guide/application/planner/PlannerSaveApplicationService.java:17-27`）。领域 `PlannerService` 只面向同层 gateway 接口，不依赖应用实现（`ai/src/main/java/com/ai/guide/domain/planner/service/PlannerService.java:79-80,97-109`）。ArchUnit 增加了实现 port 且禁止用例依赖旧 facade 的规则（`ai/src/test/java/com/ai/guide/architecture/ColaArchitectureGuardTest.java:174-185`）。应用 facade 同时注入新用例与旧 `PlannerService`，但保存调用的实现不依赖该 facade，因此未见依赖环（`ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java:34-52,100-109`）。 |
| 兼容委托 | 通过，附带测试构造限制 | 旧 `PlannerService.save(sessionId, token)` 方法签名保留，将当前 UserContext 变为 actor 后转交 port，并返回旧 `ServiceResult`（`PlannerService.java:542-549`）。Spring 用 `@Autowired` 构造器注入 port（同文件 `:97-109`）。旧参数构造器仍可被手工构造，但将 port 置空；此类实例调用 `save()` 会显式抛 `IllegalStateException`（`PlannerService.java:81-95,542-545`）。这是预期迁移边界，应让单元测试/独立调用方注入新 port 或调用应用用例；当前生产 Controller 不走此兼容方法。 |
| 身份授权与 capability | 通过 | 应用用例先拒绝空 actor 或匿名 actor，早于 session 读取和正式行程写入（`PlannerSaveApplicationService.java:31-34`）。登录用户首先按 session ID、`USER` owner、user ID 和 token 查找；未命中时才尝试使用 capability 将匿名 session 认领到当前 user（`:95-99`）。repository 的认领逻辑是带 owner、session 与 capability digest 条件的更新（`ai/src/main/java/com/ai/guide/infrastructure/planner/repository/PlannerSessionRepository.java:124-148`）。Controller 从 `UserContext` 创建显式 actor，不接受请求体中的 actor（`ai/src/main/java/com/ai/guide/adapter/planner/web/PlannerController.java:140-145`）。 |
| 快照权威 | 通过 | `save` 只以被授权 `StoredSession.trip()` 复制快照，并补充服务端 session/revision/preferences 元数据；方法不接收客户端 trip（`PlannerSaveApplicationService.java:31-42`）。应用用例测试断言实际传给 `TripService` 的数据等于服务端快照加这些元数据（`ai/src/test/java/com/ai/guide/application/planner/PlannerSaveApplicationServiceTest.java:44-76,130-136`）。 |
| 幂等、formal ID、NOOP、CAS | 通过，故障重试边界仍需集成验证 | 保存幂等键保持 `planner-save-{sessionId}-{currentVersion}`（`PlannerSaveApplicationService.java:38-45`）。既有 `TripService.savePlannerSnapshot` 负责 `PLANNER_SAVE`/`PLANNER_LINK` 预留、重放及正式 ID 约束（`ai/src/main/java/com/ai/guide/domain/trip/service/TripService.java:297-359`）；同一业务快照返回 NOOP，变化时按 `sourceTripVersion` 做 CAS，并在可安全时尝试三方变基（`TripService.java:362-424`）。保存成功后应用层以正式 Trip 返回的 ID/version 链回草稿；CAS 链接失败时至多三次重读并重试，只修改身份元数据，若已有不同 formal ID 则停止（`PlannerSaveApplicationService.java:64-82`）。对瞬时 `DataAccessResourceFailureException` 使用同一个 revision key 重试一次（`:43-51`），设计上可由幂等重放吸收第一次已提交但响应/连接失败的情况；当前 55 项测试日志未显示该特定异常重试场景，所以不把它记为已实测故障注入覆盖。 |
| Controller / wire contract | 通过（Java adapter 范围） | `POST /sessions/{sessionId}/save` 和可选 `X-Plan-Session-Token` 保持不变；Controller 将新结果的 `serviceStatus` 映射为 HTTP status、body 原样输出（`PlannerController.java:140-145`）。测试覆盖显式 actor、201 状态和响应 body 字段（`ai/src/test/java/com/ai/guide/adapter/planner/web/PlannerControllerTest.java:161-179`）。应用测试覆盖成功字段 `ok/trip/tripId/currentVersion/appliedPreferences/draftLinked`，未链接时附 message（`PlannerSaveApplicationService.java:84-92`）。本轮未验证 BFF、浏览器及真实 HTTP 序列化/鉴权中间件。 |
| 返回类型边界 | 非阻断设计债务 | `PlannerSaveResult` 无 Spring/MVC 类型依赖，但以 `int serviceStatus` 作为结果的一部分（`ai/src/main/java/com/ai/guide/domain/planner/model/PlannerSaveResult.java:7-15`）。它实现了当前兼容映射，也意味着领域包模型携带 service/transport 状态概念。后续可将结果改为应用层 outcome，并由 Controller 映射 HTTP；不影响本候选依赖环或当前 wire 行为裁决。 |
| 测试范围 | 通过所声明切片，不等同全量验收 | 冻结日志记录 55 项、0 failures/errors/skipped、BUILD SUCCESS（`docs/refactor/CP4_1_TEST.log:65-172`），涵盖 save 用例、SQLite 保存一致性/容量回归、Controller、TripService、draft restore 与架构 guard。实现报告记载 clean test 在隔离副本运行，未清理共享 target。启动日志显示 Qdrant UNAVAILABLE 后明确降级（测试仍通过，日志 `:118-151`）。未覆盖 PostgreSQL、BFF/browser 端到端，也未执行完整 Maven suite；本复审也未重跑测试。 |

## 重要边界

1. 当前没有证据显示 CP4.1 改动保存数据时接收或信任客户端提供的 trip 快照；该入口只用授权 session 快照。
2. `PlannerSaveResult.serviceStatus` 是唯一明显的层次语义瑕疵，属于非阻断项；它避免直接依赖 MVC 类，但不算纯业务 outcome。
3. 瞬断重试的幂等依据明确、保存主链有 SQLite/重建实例覆盖，但 `DataAccessResourceFailureException` 的“提交成功后抛异常”特定路径未见本批日志中单独故障注入，不应宣称已验证。
4. 实现报告提及完整 BFF、浏览器流程、生产 PostgreSQL 与外部服务未验证；CP4 的 Open/Restore 等切片仍在范围外。因此结论仅为 CP4.1 Java Planner Save 架构复审通过，不推导整个后端无缺陷。

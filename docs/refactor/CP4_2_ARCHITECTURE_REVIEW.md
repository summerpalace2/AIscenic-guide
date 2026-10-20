# CP4.2 独立架构复审

复审日期：2026-10-09  
范围：冻结的 CP4.2 Planner V1 正式行程 Open 用例迁移。只读检查候选清单、源码、相关测试与原始测试日志；未运行 Maven，未改业务代码。

## 冻结身份

- `docs/refactor/CP4_2_CANDIDATE.json` SHA-256：`cc12654db2d49efea316efcf485acec3924796a6dc561be4504ba51a798c8553`，匹配。
- `docs/refactor/CP4_2_TRACKED_PATCH.patch` SHA-256：`fdde058fd1fa24a5290a095d238ad9fa941c48a15e35a81d5834715d84f4e053`，匹配。
- `docs/refactor/CP4_2_TEST.log` SHA-256：`48807d892cc1112bec5041baedcef1341298a96de063ce6353f41606e1a96985`，匹配。
- manifest 的 12 个源文件 hash 及实现报告 hash 全部匹配。
- tracked patch 按 manifest 定义从 HEAD 限定路径生成，重叠文件可能含先前 CP0–CP4.1 改动；新增文件另列并有独立 hash。

## 裁决

**没有发现 CP4.2 应用/领域依赖环或授权绕过；主要 Open 编排已进入应用用例，当前 wire 兼容性有 Java 测试支撑。** 同一次 owner-scoped `TripService.get(ownerId, tripId)` 返回对象被用于恢复快照、正式 trip ID 和 version，避免查询之间版本漂移。没有发现修改 BFF、前端、Restore 路由或数据库 schema。

有一项 **P2 非阻断、但真实可触发的恢复诊断缺陷**：约束反序列化失败标记会被“偏好不可用”标记覆盖。它是迁移前已有逻辑，CP4.2 将其移动到新用例时仍原样保留，因而不能归咎为本次架构迁移新引入；但实现报告笼统声称两种降级标记都保留，当前代码在两个降级同时发生时不成立。

## 审查矩阵

| 项目 | 结论 | 证据与触发边界 |
|---|---|---|
| 应用/领域依赖方向 | 通过 | `PlannerOpenApplicationService` 位于 application 层，实现 domain `PlannerOpenUseCasePort`，自身依赖 session repository port、domain `TripService` 与 `ObjectMapper`，没有依赖 `PlannerService`（`ai/src/main/java/com/ai/guide/application/planner/PlannerOpenApplicationService.java:25-41`）。`PlannerService` 只持有 domain gateway port 并通过兼容方法委派（`ai/src/main/java/com/ai/guide/domain/planner/service/PlannerService.java:81-114,575-582`）。`PlannerApplicationService` 才同时依赖旧 facade 和 Open port（`ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java:38-58,117-125`）。ArchUnit 增加“实现 port 且不得依赖旧 facade”规则（`ai/src/test/java/com/ai/guide/architecture/ColaArchitectureGuardTest.java:188-198`）；测试日志显示 12 项架构规则通过。 |
| 显式 actor 与 owner 授权 | 通过 | Controller 从服务端 `UserContext` 建 `PlanningActor`，请求中不接收 owner ID，使用显式 actor 调用 application（`ai/src/main/java/com/ai/guide/adapter/planner/web/PlannerController.java:90-95`）。用例拒绝空/匿名 actor 或空白 userId，再按该 userId 调用 owner-scoped `TripService.get`；查无权/不存在均返回 404，不创建 session（`PlannerOpenApplicationService.java:43-55`）。用例分别测试匿名、空 owner、无权/不存在 Trip 三个早返回分支且断言无 session creation（`ai/src/test/java/com/ai/guide/application/planner/PlannerOpenApplicationServiceTest.java:72-104`）。 |
| 单次 Trip 读取的一致性 | 通过 | 唯一读取位于 `PlannerOpenApplicationService.java:50`。随后的 snapshot、title、formal ID、`sourceTripId`、`sourceTripVersion`、恢复响应的 `formalTripVersion` 都从同一个 `formal` 对象读取（`:53-61,75-81`）；没有二次查询竞态。测试验证 Trip Version 7 同时写入 `formalTripVersion` 与 `sourceTripVersion`，以及正式 ID/标题（`PlannerOpenApplicationServiceTest.java:34-70`）。 |
| session 创建与 revision | 通过 | 新快照保留旧 planner revision 到 `sourcePlannerRevision`，把新 session trip `version` 重置为 1（`PlannerOpenApplicationService.java:55-62`）。已有 repository `create` 将 session `current_version`/`sync_revision` 及初始 plan revision 初始化为 1（`ai/src/main/java/com/ai/guide/infrastructure/planner/repository/PlannerSessionRepository.java:82-94`）。用例以 `USER` 与 actor userId 创建，并返回创建 session ID（`PlannerOpenApplicationService.java:70-89`）。现有 SQLite 回归也走 Open 并验证更新后的 Trip 标题进入恢复快照（`ai/src/test/java/com/ai/guide/domain/trip/service/TripSaveCapacityRegressionTest.java:168-180`）。 |
| Session 创建幂等边界 | 说明性限制 | `restoreKey` 带随机 UUID（`PlannerOpenApplicationService.java:70`），因此每个 POST Open 都代表新 session；相同 Trip/version 的网络重试不会命中同一 idempotency key，可能创建两个 session。该行为与迁移前实现一致，适合“每次打开创建新草稿”的语义；如果客户端需要对响应丢失重试去重，现有 API 没有请求幂等 token，本切片未提供该保证。 |
| wire status/body | 通过，限 Java adapter | 路由仍是 `POST /trips/{tripId}/open`；Controller 将 `PlannerOpenResult.serviceStatus()` 与 body 映射到原 HTTP 响应（`PlannerController.java:90-95`）。Controller 测试断言 actor、状态及返回 body（`ai/src/test/java/com/ai/guide/adapter/planner/web/PlannerControllerTest.java:96-110`）。用例保留 401/400/404 body 结构，成功仍为 200 并有 `planResponse` 及 Open 元数据（`PlannerOpenApplicationService.java:45-81`）。测试为直接 Controller 方法调用，不是启动服务器后的 HTTP 客户端/BFF 往返。 |
| 旧 facade 行为 | 通过，受构造方式限制 | `PlannerService.openFormalTrip(String)` 保留并用 UserContext 建 actor 转发到 Open port，status/body 适配回旧 `ServiceResult`（`PlannerService.java:575-582`）。Spring 注入构造器接收 Open port（`:101-114`），实现报告指定的 `PlannerServiceTest` 7 项通过，覆盖 Spring facade 路径。旧手工构造器向 Open port 传 null，直接调用 `openFormalTrip` 会抛出明确 `IllegalStateException`（`:85-99,575-578`）；独立手工实例必须显式传入 port 或调用 application facade。 |
| 降级标记合并 | **P2 非阻断真实缺陷，迁移前已存在** | `restoreConstraints` 在 `constraints` 是 map 但无法反序列化时写 `plannerSessionRestoreDegradation=CONSTRAINTS_UNAVAILABLE`（`PlannerOpenApplicationService.java:116-122`）。随后若偏好缺失/解析为空，主流程直接 `put` `APPLIED_PREFERENCES_UNAVAILABLE`（`:63-67`），覆盖前一个原因。触发条件：正式快照约束无法解析且应用偏好同时为空。最终 session 快照只暴露后一标记；管理端/后续调用无法判断 constraints 也丢失。迁移前旧实现 hunk 同样按此顺序写单一字段（`docs/refactor/CP4_2_TRACKED_PATCH.patch:621-625`），是保留的既有行为而非 CP4.2 重构造成。实现报告需收窄“两个降级标记均保留”的表述，或另开修复把原因合并成集合/列表并补复合场景测试。 |
| 兼容响应元数据 | 非阻断观察 | `planResponse` 会按 `planner.v1.enabled` 输出 planner/policy 版本和 degraded 状态（`PlannerOpenApplicationService.java:84-104`），但成功结果仍固定写 `adjustmentCapability=V1_PROPOSAL`、`legacyMode=false`（`:75-81`），即使配置关闭 V1 也如此。这个固定值与迁移前方法相同（tracked patch 的旧实现 `:633-639`），属于现存语义；如配置关闭时 Open 可被调用，响应 capability 与版本字段可能不一致。本候选未改变它，不列为本次重构阻断项。 |
| 范围及测试 | 符合声明范围，不代表全端验收 | manifest 仅列 Java planner adapter/application/domain 与测试，未列 BFF、frontend、Restore 代码或 schema。冻结日志显示 61 tests、0 failures/errors/skipped、BUILD SUCCESS，并包含 `PlannerServiceTest` Spring context 与架构守卫。Qdrant 启动检查不可用但明确降级（实现报告注明）。未实测 BFF、浏览器、PostgreSQL/Redis、真实 HTTP 客户端或网络重试；本复审没有重跑测试。 |

## 建议后续处理

1. 单独修复恢复降级标记的覆盖，至少为“约束解析失败 + 偏好缺失”新增测试，并让两个原因可同时观察。
2. 如产品要求 Open 请求丢响应后安全重试，需在 API 接收稳定的调用方幂等键；否则文档明确每次 Open 创建独立 session。
3. 保持 CP4.2 的架构裁决范围：当前没有理由因上述既有诊断缺陷否定依赖方向或同一 Trip 快照读取，但不应将它宣称为已完全没有行为问题。

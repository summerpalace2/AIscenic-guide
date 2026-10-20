# CP4.3 Planner Restore 红方复审

日期：2026-10-09  
审查范围：冻结候选中的 Java Planner Restore 应用用例与兼容入口。只读检查，没有运行 Maven、修改源码或测试，也没有使用共享 `target`。  
候选 manifest SHA-256：`42b3d72954847ef2b6d273ff54b563a4eb68c00cfc48dc6f435d63e9fe829a7c`（匹配）  
tracked patch SHA-256：`04da05079482732a9442494ea9af910d95ebab4a4810640a0c3ff475e2e156d2`（匹配）  
最终测试日志 SHA-256：`bb311f8b3b8cd5594664cb5293b1232f0d258a7a5a87c5d34166a86dd65c731f`（匹配；仅读取 manifest 声明，未重新运行）  
编译诊断日志 SHA-256：`755b3d32815189107e0394ffccbe51cc0c620277de415f40a324639ea759eeda`（匹配）  
fixture 诊断日志 SHA-256：`b083b5ede488550d1ce9cf07dca056a2e160e063abddaeb54a3aabbbd00e304e`（匹配）  
manifest 列出的 12 个实现/测试文件 SHA-256 均与当前文件一致。

## 裁决

**PARTIAL：未发现确认的跨用户恢复或正式行程覆盖漏洞；核心恢复路径看起来保留了 owner 检查、服务端身份覆盖和 planner revision CAS。** 但不能把候选描述的完整矩阵都视为已验收：实际测试未验证 Restore 的 CAS 冲突分支、无效超限备份分支或 formal Trip 更新与恢复并发；“错误 capability”测试用例对 USER session 的 stub 与真实仓储语义不一致。另有明确的配置差异：Restore 每次 `new TripPlanLimits(objectMapper)`，不会使用 Spring 注入的可配置限制。建议将这些缺口列为 CP4.3 的剩余项；若部署依赖 `trip.plan.*` 自定义限制，应先修正限制来源并补回归再判完整通过。

本裁决针对 Java Restore 迁移切片，不代表 BFF/前端恢复链路或 CP4 全阶段通过。

## 身份核验

- `docs/refactor/CP4_3_CANDIDATE.json` 与指定 SHA 匹配。
- manifest 指定的 tracked patch 与三个原始测试/诊断日志 SHA 均匹配。
- manifest `fileSha256` 中全部 12 个源文件和测试文件均匹配当前工作区。
- manifest 记录隔离副本 `clean test` 为 71 tests、0 failures、0 errors、0 skipped；这是实施日志中的证据，不是本次复跑结果。

## 逐项检查

### 1. 匿名身份、他人身份及 capability：实现拒绝路径正确；一项测试 stub 不能代表真实 USER-session 行为

- `PlannerRestoreApplicationService.restoreDraft` 在任何仓储调用之前拒绝 null/anonymous actor，返回 401（`PlannerRestoreApplicationService.java:48-49`）。`anonymousActorIsRejectedBeforeAnySessionLookupOrWrite` 断言 401 且对 session/Trip 端口 `verifyNoInteractions`（`PlannerRestoreApplicationServiceTest.java:97-103`）。Restore 面向已登录用户；此处不存在匿名 capability 成功恢复路径。
- 登录用户通过 `find(sessionId, "USER", actor.userId(), token)` owner-scoped 查找；未命中后仅尝试 `claimAnonymous(sessionId, token, actor.userId())`（`PlannerRestoreApplicationService.java:84-93`）。另一个用户既读不到 USER session，也不能 claim 已归属的 USER session。`anotherOwnerAndWrongCapabilityAreDeniedWithoutWrites` 断言另一 owner 返回 404，未访问 Trip、未调用 session update（测试 105-118 行）。这证明 application 编排拒绝，但测试是 Mockito 端口 stub，不是仓储/数据库隔离证据。
- 该测试把“wrong capability”分支 stub 为 `sessions.find("session-1", "USER", "owner-1", "wrong-cap") == null`。实际 `PlannerSessionRepository.find` 对 `ownerType="USER"` 只按 `session_id + owner_type + owner_id` 查找，只有 `ownerType="ANONYMOUS"` 才校验 token digest（`PlannerSessionRepository.java:112-126`）。因此同一已认证 owner 对自己的 USER session 携带错误或过期 token 仍会授权；这是 owner 认证模型，不是越权，但该测试名/断言不能证明 USER session 的 token 必须匹配。真实应拒绝的 wrong-cap 场景是登录用户尝试 claim 一个仍属 ANONYMOUS 的 session；本轮 Restore 用例测试未通过真实仓储覆盖该分支。底层 claim 的 wrong-token/竞争另有历史仓储测试，但不替代本候选应用调用链测试。
- capability 适用于匿名 session claim，不应覆盖当前用户所有权。若 HTTP 契约本意是所有 Restore 都必须校验 capability，则现实现不是该契约；需明确业务权威。按当前仓储既有语义，USER owner 通过身份授权是合理的。

### 2. 旧 planner revision 与 formal Trip 版本：基本门禁成立；formal 版本并发窗口未被测试

- `expectedVersion` 缺失或不等于 server-held `currentVersion` 时，在读取 formal Trip 和调用 update 之前返回 409（`PlannerRestoreApplicationService.java:52-55`）。`stalePlannerRevisionIsRejectedBeforeFormalTripRead` 检查返回 trip 仍为版本 3、未写 session、没有 Trip 交互（测试 121-129 行）。
- 使用 `session.trip().sourceTripId` 作为 formal ID，并按 actor owner-scoped 调用 `tripService.get`；空 link 返回 400、无权/不存在返回 404，均不调用 session update（源码 56-59 行；`missingFormalLinkOrUnownedTripIsRejectedWithoutSessionWrite`，测试 140-155 行）。
- 备份 `sourceTripVersion` 与读取到的 formal `currentVersion` 不一致时返回 409 且不更新 session（源码 60-63 行；`staleFormalVersionIsRejectedWithoutSessionWrite`，测试 131-138 行）。
- 检查存在 get-to-update 竞态：若 `TripService.get` 返回版本 5 后、`sessionRepository.update` 前 formal Trip 被另一请求推进到 6，本次仍会以版本 5 的身份元数据提交恢复后的 planner session 并返回 200。由于恢复只改 planner session，正式 Trip 不会被覆盖；后续 Save 的 formal-version CAS 会拒绝写回旧来源。此行为延迟暴露冲突，不会造成正式行程丢失。候选没有 latch/barrier 测试，也没有跨正式 Trip 与 planner session 的原子事务。按现有语义这是未验证竞态/用户体验风险，不构成已证明的正式行程数据破坏。

### 3. 备份身份伪造：PASS

- 成功路径不信任客户端传入的 `sourceTripId`、`formalTripId`、`version`，覆盖为 server session 的 formal ID、当前 formal version 和 `currentVersion + 1`（`PlannerRestoreApplicationService.java:71-77`）。
- `restoresValidatedBackupIntoAuthoritativeSessionAndFormalIdentity` 使用伪造的两个 ID 和 `version=99`，观察到返回 trip 是 `formal-1`、来源版本 5、planner version 4，且用户编辑的 stop 内容保留（测试 69-95 行）。这直接覆盖身份与版本权威。

### 4. 无效/超限备份：基本校验在写入前；超限与配置化门禁没有 Restore 覆盖

- `TripPlanLimits.validate(restored)` 在 `sessionRepository.update` 前调用，`IllegalArgumentException` 转成 400（源码 64-70 行）。`invalidBackupIsRejectedWithoutSessionWrite` 只删除 `days`，验证结构缺字段失败并且无 session update（测试 157-164 行）。
- 本候选没有 Restore 专项的超天数、每日站点数、总站点数、文本长度或 JSON 字节上限测试。现存 `TripSaveCapacityRegressionTest` 覆盖正式 Trip 保存的上限和可配置性（例如其 `oversizeWritesAreRejectedWithoutPersistingAndLimitsAreConfigurable` 与 `dailyAndTotalStopLimitsBothApply`），但不能证明 Restore 路径实际接入同一配置。
- 实现直接执行 `new TripPlanLimits(objectMapper)`（源码第 67 行），绕过 Spring `TripPlanLimits` bean 的 `@Value` 配置。当前构造将使用类字段默认值（30 天、每日至多 40 站、总计 500 站、单段 20,000 字符、JSON 2 MiB），而不是部署设置的 `trip.plan.*`。例如正式保存配置允许 35 天时，Restore 仍会拒绝第 31 天；反向降低配置时 Restore 仍可能接受高于部署限制、但低于硬编码默认的备份。此为具体配置一致性缺陷，且正式保存与恢复会采取不同容量门禁。实现报告称沿用旧构造方式，说明它是迁移前已有问题，但并不消除候选中的行为差异风险。若项目环境没有覆盖这些属性且接受上述默认值，该项可降为已知限制；需由主智能体按实际配置裁决。
- 没有发现验证失败后仍调用 `sessionRepository.update` 的代码路径；缺字段失败分支确实先返回。超限分支的同等“零写入”目前仅可由控制流推断，没有 Restore 测试证据。

### 5. CAS 冲突：仓储实现有条件写；应用对冲突结果有映射；缺少 Restore 断言

- application 把已读的 `session.currentVersion()` 原样传给 `sessionRepository.update`，结果为 `CONFLICT` 时返回 409（`PlannerRestoreApplicationService.java:76-79`）。
- JDBC 仓储使用 `UPDATE ... WHERE session_id = ? AND current_version = ?`；更新 0 行时读取最新 session 并返回 `MutationResult.conflict`，不会插入 `planner_plan_revision`；成功时才插入 revision（`PlannerSessionRepository.java:181-199`）。从实现看并发 planner 写不会覆盖新版本或重复增加 revision。
- 本候选的 `PlannerRestoreApplicationServiceTest` 没有构造 `MutationResult.conflict(latest)` 的 Restore 用例，也没有检查返回 409、当前 revision 与 revision 表写入次数。已有 `PlannerDraftRestoreTest` 的旧 revision 测试是服务端读取前的 expectedVersion 失败，不是 update 阶段 CAS 竞争。故 CAS 竞争逻辑有代码证据，但缺少该候选的行为回归证据；不能把“拒绝时 DB 不变”扩大声称为已实测覆盖 CAS 冲突。

### 6. Controller header token fallback 与 wire status/body：直接调用覆盖有限契约

- Controller 在请求体 token 为 null/blank 时使用 `X-Plan-Session-Token`，非空 body token 优先，并显式构建 actor；按 application result status/body 原样输出（`PlannerController.java:99-108`）。`restoreDraftPassesExplicitActorAndUsesHeaderCapabilityFallback` 验证 blank body token 使用 header、actor 与 session ID 传递，以及 409 status/body（`PlannerControllerTest.java:116-138`）。
- 这是直接调用 Controller 的单元测试，不是 MockMvc/真实 HTTP；无法验证 JSON 绑定、认证 filter、header 解析、BFF 代理或浏览器行为。本切片未更改 BFF/前端，所以列为范围外，不判阻断。

### 7. 旧入口兼容：PASS（应用委托兼容，不等于完整 HTTP 端到端）

- `PlannerService.restoreDraft(String, RestoreDraftRequest)` 保留旧签名，通过 `PlannerRestoreUseCasePort` 构造 `PlannerRestoreActor` 并映射结果回既有 `ServiceResult`（`PlannerService.java` Restore facade）。
- `PlannerDraftRestoreTest.restoresOnlyToOwnedCurrentSessionAndServerRevision` 经此旧签名验证成功映射及 server revision；`rejectsStalePlannerOrFormalVersionWithoutWriting` 经旧入口验证 stale 分支。但 fixture 是 Mockito repository/trip 服务，不能替代生产事务和 HTTP。
- 测试清单里的 `PlannerServiceTest` 主要检查 Open、Preview、Apply、Save 的 Spring 上下文流程；从测试方法检查没有发现通过旧 Restore HTTP 入口的真实数据库闭环。因此旧 Java facade 有定向证据，完整线上路由兼容仍未验证。

## 覆盖矩阵

| 场景 | 代码判断 | 本候选直接测试证据 | 结论 |
| --- | --- | --- | --- |
| anonymous actor | 任意仓储查找前 401 | `anonymousActorIsRejectedBeforeAnySessionLookupOrWrite`，verifyNoInteractions | PASS |
| 其他 owner | owner-scoped find 后 claim 不成功则 404 | `anotherOwnerAndWrongCapabilityAreDeniedWithoutWrites`，mock 端口 | PASS（应用层） |
| wrong capability | 匿名 session claim 使用 token；USER session 由 owner 身份授权 | 用例将 USER-session `find` stub 为 null，与 JDBC 真实语义不同 | PARTIAL，测试名称/覆盖有误 |
| stale planner revision | formal Trip 读取前 409 | `stalePlannerRevisionIsRejectedBeforeFormalTripRead` | PASS |
| stale formal version | 与 get 到的 formal version 比较，不一致不更新 session | `staleFormalVersionIsRejectedWithoutSessionWrite` | PASS（非并发） |
| formal Trip 并发更新 | read→session CAS 存在时间窗；后续 Save 可拒绝旧来源 | 无屏障测试 | PARTIAL，竞态未验证 |
| session 未关联 Trip / Trip missing | 400 / 404，session update 前返回 | `missingFormalLinkOrUnownedTripIsRejectedWithoutSessionWrite` | PASS（mock 端口） |
| 伪造 backup 身份 | 用 server session formal ID 覆盖浏览器 ID | `restoresValidatedBackupIntoAuthoritativeSessionAndFormalIdentity` | PASS |
| 无效结构备份 | limits 在 update 之前校验 | `invalidBackupIsRejectedWithoutSessionWrite` 只覆盖缺 days | PASS（缺字段）；超限 PARTIAL |
| 超限/部署自定义限额 | 检查默认限制，但使用 new 实例不读取 Spring 属性 | 无 Restore 超限测试；正式保存限制测试不等价 | PARTIAL，配置差异需裁决 |
| update 阶段 CAS 冲突 | JDBC 条件更新、0 行返回 conflict，不插 revision | Restore application 无冲突结果测试 | PARTIAL，代码成立但未回归 |
| header token fallback | blank body token 取 header | `restoreDraftPassesExplicitActorAndUsesHeaderCapabilityFallback` | PASS（direct controller） |
| 旧 Java 入口 | facade 委托 Restore application 并映射 ServiceResult | `PlannerDraftRestoreTest` 两项 | PASS（mock fixture） |

## 剩余边界

1. 本次没有运行任何测试；不能将 manifest 中的 71 项测试统计表述为本次独立复跑。
2. 本切片没有验证 BFF / frontend 的恢复请求、备份身份、浏览器账号与工作区切换、真实 HTTP 或真实 PostgreSQL / Redis。
3. 除正式 Trip get 与 session update 的时间窗外，缺少真实仓储/屏障驱动的 Restore 并发回归；没有证明跨两类数据记录的原子性。
4. 用户若需要 Restore 与正式保存使用同一组可运维配置限额，应注入 Spring `TripPlanLimits` 而不是自行 new；补测试时需把自定义低限额和高限额分别通过 Restore 入口验证。

## 最终建议

目前实现没有显示出匿名绕过、跨 owner 写入、客户端身份覆盖或正式 Trip 被恢复操作直接覆盖的问题。可接受的有限结论是“Restore 编排迁移的主要串行语义保持，候选未见阻断安全回归”。在主智能体将“PARTIAL”中的覆盖和配置决策关闭前，不建议将 CP4.3 声称为完整通过；最少补 Restore CAS 冲突零 revision 写入测试、真实错误 capability claim 覆盖、超限备份零写入测试，并明确是否要求 Restore 遵循 `trip.plan.*` 配置。

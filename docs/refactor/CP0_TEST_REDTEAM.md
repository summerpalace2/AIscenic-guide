# CP0 红方测试映射审查

审查日期：2026-10-09  
审查对象：`COLA_REFACTOR_EXECUTION_PLAN.md` 的 S0 / V01–V16 测试映射  
审查方式：只读检查当前 Java、BFF、Frontend 测试源码与测试资产跟踪状态；**未运行测试**，未修改业务源码或既有文档。

## 裁定摘要

当前测试资产对局部规划的规则与服务层行为有较多覆盖，但它们不能合并解释成一条端到端通过链路。几个高风险误读需要避免：

1. `PlanAdjustmentServiceTest` 和 `PlannerServiceTest` 是 `@SpringBootTest`，比纯 Mockito 单测更接近服务集成；但它们绕过 HTTP Controller、BFF 和浏览器，不等于 UI→BFF→Java→数据库端到端。
2. 多项 BFF 测试从 `index.mjs` 中按字符串边界截出路由体，在 `vm` 上下文中注入假 `javaCore`、假持久化层执行；它们验证了真实源码路由片段的逻辑，但没有启动 HTTP 服务，也没有调用真实 Java 服务或数据库。
3. 前端规划与草稿恢复测试 mock `fetch`，断言页面状态与请求载荷；不证明浏览器实操、CSRF/会话桥接或后端实际落库。
4. 有价值的测试文件仍未跟踪：Java `StopPlanRoundTripTest`、`PlannerDraftRestoreTest`、`MemoryRepositoryPortContractTest`；BFF `draft-restore-route.test.mjs`；Frontend `draft-backup-restore.test.mjs`。前端目录整体在 BFF `.gitignore` 下，但 `frontend` 是独立 Git 仓库；其中上述草稿恢复测试在 Frontend 仓库也未跟踪。CP0 不能只靠当前工作区运行清单证明可携带。
5. V08 的现有可控并发测试只覆盖“apply 已进入仓储更新后，cancel 读取并最终被拒绝”这一路径；没有发现对称的“cancel 先完成，apply 后拒绝”屏障测试。V09 的旧预览晚到缓存/提案写入竞态也没有直接测试。

## V01–V16 覆盖矩阵

“直接”表示找到了明确断言目标场景的测试方法，不表示端到端；“部分”表示有相邻覆盖但仍缺计划要求中的核心条件；“无”表示本轮定位的相关测试中没有找到该场景的直接断言。所有分类仅来自静态阅读，未验证测试实际通过。

| V | 静态审查裁定 | 具体证据与覆盖边界 |
|---|---|---|
| **V01 预览不得变更行程/revision** | **直接（服务集成层）** | Java `PlanAdjustmentServiceTest.test01_previewReplacementDoesNotChangeDurablePlanOrRevision` 与 `test24_reduceDayDensityGeneratesPreviewWithoutMutatingTripOrRevision` 直接读取规划会话版本；`PlannerServiceTest.shadowPlanningAndMutationsNeverCreateDurablePlannerOrTripState` 检查数据库行数。未覆盖 Controller/BFF/UI 链路。 |
| **V02 澄清承接与完整纠正** | **直接（领域规则层）** | `ConversationContinuationRegressionTest.completePlaceAnswerFillsOriginalDayAndConstraints`、`placeThenTimeThenDayFillsOnlyEachAnsweredSlot`、`ContinuationCorrectionBoundaryTest.completeCorrectionReplacesOldTargetWhilePreservingPins`、`CompoundTimeAndCancellationTest.correctingInvalidCommonTimeDoesNotLoseCompoundSelection` 明确覆盖承接/纠正。主要是内存 continuation 与规则测试，不覆盖服务重启或真实请求历史的 HTTP 串接。 |
| **V03 甜食口语等价与跨城搜索** | **部分** | Java `PlanAdjustmentServiceTest.colloquialDessertVariantsProduceEquivalentPreviewsOnSameTrip`、`noDessertCandidatesIsExplainedAsSearchFailureRatherThanTimeConflict` 等覆盖措辞与无候选解释；`CrossCityPlannerPolicyTest` / `CityCatalogIsolationTest` 覆盖部分跨城策略。未找到单项用例同时断言两种甜食措辞、跨城市、实际城市参数传到供应商及“无候选”的真实原因；外部高德真实服务没有证据。 |
| **V04 当前方案按钮与旧 tab 快捷操作** | **部分** | Frontend `planning-loop.test.mjs` 检查结构化快捷操作 payload；`proposal-availability.test.mjs` 的“旧对话快捷操作按当前行程重新发起”检查模式切换和输入内容。没有浏览器真实点击/旧 tab 多轮状态实操，也没有证明旧动作携带错误 revision 时后端拒绝且不重复整篇规划。 |
| **V05 幂等重试/同键异请求** | **部分** | Java `PlanAdjustmentServiceTest.test15_sameIdempotencyKeyDoesNotCreateDuplicateRevision` 直接检查重复 apply 后 revision 保持 2；`TripServiceTest.idempotencyReplaysOriginalResultAndRejectsDifferentFingerprint` 覆盖正式行程 create 的同键异指纹拒绝。没有发现局部调整 apply 的“同键但不同 proposal/option 请求必须拒绝”用例，也未覆盖进程重启后的幂等回执。 |
| **V06 旧 revision/他人提案/过期提案无副作用** | **直接（Java 服务层）** | `PlanAdjustmentServiceTest.test03_applyOnStaleRevisionReturnsConflict409`、`test04_proposalBelongingToDifferentSessionIsRejected`、`test05_expiredProposalIsRejected`，以及 `staleProposalCannotBeAppliedWithForgedCurrentRevision` 覆盖主要拒绝条件；部分用例检查版本，拒绝路径的数据库副作用断言并不统一。BFF 端没有真 Java 链路验证。 |
| **V07 真实取消与否定取消** | **直接（领域/服务层）** | `CancellationProposalLifecycleTest.cancelledGeneratedProposalCannotBeApplied`、`negatedCancellationKeepsGeneratedProposalApplicable`，以及 `CompoundTimeAndCancellationTest` 对否定取消的测试覆盖。前两个直接用 Mockito session repository，属于服务逻辑+替身，不是数据库原子性验证。 |
| **V08 cancel/apply 两种可控先后顺序** | **部分，CP0 关键缺口** | `CancellationProposalLifecycleTest.cancellationWaitsForSubmittedApplyAndRejectsStaleRevision` 用 `CountDownLatch` 控制 apply 进入更新后 cancel 到达，验证 apply 成功、cancel 被拒。未发现 cancel 先提交后 apply 到达的对称屏障测试；测试 repository 是 Mockito mock，无法证明真实 SQL CAS/事务争用。 |
| **V09 取消后旧预览晚到写入** | **无直接覆盖** | `ConversationContinuationRegressionTest.resetAndNewRoundPreventOldPublication` 覆盖 continuation callback generation 失效；但没有发现让旧 preview 先暂停、cancel/reset 完成后再恢复旧 preview，并断言 proposal cache 不复活、当前 revision/UI 不被旧响应覆盖的测试。二者不是同一个竞态。 |
| **V10 DB 已提交而回执丢失/缓存故障** | **部分** | `PlanAdjustmentServiceTest.test15...` 覆盖同一进程重复 apply；BFF `draft-restore-route.test.mjs` 有“Java 已提交但 BFF 草稿缓存写失败”的 restore 分支，但 Java 是 harness 假实现。未找到 apply 提交后模拟 HTTP 回执丢失、缓存失效/Redis 故障后重试且核验只写一次的跨层故障注入。 |
| **V11 Redis 故障与新实例恢复提案** | **部分，关键缺口** | `ProposalStoreAndChatContextTest.testBasicPutAndGet`、`testExpiredProposal` 和容量测试仅覆盖本地 `ProposalStore` 行为；`PlannerServiceTest` 有 shadow session 的数据库验证，但没有重建服务实例从 Redis 恢复提案/epoch 的测试。未见 Redis 故障注入、真实 Redis 或新实例验证。现有测试名不能支撑 Redis/重启恢复承诺。 |
| **V12 固定站、营业区间、路线缺失与 force** | **部分** | `PlanAdjustmentServiceTest` 有 `knownMondayClosureCannotBeForceApplied`、must-visit/pinned、营业和路线相关回归；`TemporalIntegrityRegressionTest`、`DiningGeoSafetyTest`、`GeoAndMealGapRegressionTest` 也覆盖局部约束。未找到一个完整矩阵用例同时证明固定站保留、跨完整营业区间拒绝、路线未知不当零、force 只能越过软提醒。需按子不变量逐条映射，不能写成 V12 已全覆盖。 |
| **V13 组合动作逐项确认/跳过/取消剩余** | **部分** | `CompoundTimeAndCancellationTest.differentActionTimesRemainIndependentAcrossApply`、`ContinuationCorrectionBoundaryTest.refusedActionNeverReturnsInQueueAfterApplyingAnotherAction`、`sameKindOperationsKeepBothDaysAfterApply` 覆盖队列部分规则。未找到完整地断言应用一次后剩余序号、已执行动作不重放、跳过与取消剩余全路径的集成用例。 |
| **V14 Map/类型往返与 wire 边界** | **部分** | 未跟踪的 Java `StopPlanRoundTripTest` 中 `testExtrasLosslessPreservation`、`testCoreFieldsOverrideMaliciousExtras`、`testWireNullEmptyFalseAndNumbersLossless` 等直接覆盖 StopPlan/AdjustmentPreview pilot。它不能代表 Trip/session 的 Map→持久化→HTTP 往返；且当前未跟踪，冷检出不可复现。 |
| **V15 保存/打开/恢复/继续变异与备份失败** | **部分（分层覆盖，不是 E2E）** | Java `PlannerServiceTest.opensFormalTripAsEditablePlannerSessionAndSavesBackToSameTrip` 与 `restoredPlannerSaveRejectsStaleFormalTripVersion` 用 Spring 测试上下文和 JDBC 检查正式行程生命周期；未跟踪 `PlannerDraftRestoreTest` 是 Mockito 仓储/TripService 的单服务边界测试。BFF `draft-restore-route.test.mjs` 是抽取路由片段 + 假 Java/存储；Frontend `draft-backup-restore.test.mjs` 用 mock fetch。合起来仍没有同一运行中浏览器→BFF→真实 Java→测试 DB 的端到端用例。 |
| **V16 切账号/工作区后旧响应不污染当前页面** | **无直接覆盖** | 本轮检索 Frontend 测试未找到控制请求延迟、切账号/工作区、再释放旧响应并检查状态不变的用例；Java 资源归属测试 `TripOwnershipTest.anotherOwnerCannotReadOrPhysicallyDeleteTripHistory` 只验证后端正式行程 owner，不覆盖旧 UI 回执。 |

## 容易被误报成端到端的测试

- **BFF 路由测试**：`server/draft-restore-route.test.mjs` 用 `source.indexOf(...)` 截取 `index.mjs` 中路由段，再 `vm.runInContext` 执行；其中 `javaCore.restorePlannerDraft`、`persistentStore.savePlannerDraft` 都是测试 harness 假对象。它是实际路由源码片段的契约/隔离测试，不是 HTTP 服务集成，更不是 Java+DB 集成。
- **BFF Java bridge 测试**：`server/planner-bridge-contract.test.mjs` 从 `java-core-client.mjs` 中抽出 `plannerConversationBody` 函数，在 VM 中执行并断言转换字段；没有发出网络请求到 Java。
- **Frontend planning loop**：`planning-loop.test.mjs` 将 `fetch` 替换为函数，分别返回预制 preview/apply JSON。可验证状态机和请求顺序，不能证明接口契约与 Java 实际响应一致。
- **Frontend draft restore**：`draft-backup-restore.test.mjs` 同样 mock `/draft/restore` 与站点变更返回值。可验证 UI 保存/恢复逻辑，但不是用户浏览器与真实服务端生命周期验收。
- **Java 并发边界测试**：`CancellationProposalLifecycleTest` 将 session repository mock 掉。锁竞争顺序有价值，但不覆盖真实 repository CAS、数据库事务、Redis 或多实例锁。
- **架构守卫**：`ColaArchitectureGuardTest` 的测试文件存在不意味着覆盖 V01–V16；它检查包依赖静态规则，不能替代业务行为或系统链路。

## CP0 交付风险：关键测试尚未纳入版本控制

以下是当前工作区静态状态（非 HEAD 内容）中与矩阵直接相关的测试：

| 仓库 | 路径 | 当前 Git 状态 | 可携带性影响 |
|---|---|---|---|
| Java `D:/scenic-guide` | `ai/src/test/java/com/ai/guide/domain/planner/model/StopPlanRoundTripTest.java` | untracked | V14 的主要类型往返证据未进入版本控制。 |
| Java | `ai/src/test/java/com/ai/guide/domain/planner/service/PlannerDraftRestoreTest.java` | untracked | V15 的服务边界证据无法从 HEAD 冷检出获得。 |
| Java | `ai/src/test/java/com/ai/guide/domain/memory/port/MemoryRepositoryPortContractTest.java` | untracked | 与本轮局部规划矩阵无直接 V 项，但作为 S2 仓储试点契约测试不具备冷检出证据。 |
| BFF `.../loop-p0-functional-inventory-1-home` | `server/draft-restore-route.test.mjs` | untracked | V10/V15 BFF 路由测试未跟踪。 |
| Frontend 独立仓库 `.../frontend` | `web/src/features/trip-save/draft-backup-restore.test.mjs` | untracked | V15 前端备份恢复测试未跟踪。 |
| Frontend 独立仓库 | `web/src/features/trip-chat/proposal-availability.test.mjs` | modified | 当前工作树内容有修改；须把候选 diff 纳入冻结身份。 |

此外 BFF 根目录 `.gitignore` 忽略整个 `frontend/` 子目录；Frontend 实际有独立 Git 仓库。基线/运行清单必须分别从 BFF 与 Frontend 仓库取版本身份，不能只记录 BFF HEAD。当前 Frontend HEAD 为 `6dd081da50078d39cd76337c018cd5cb4042dd18`，BFF HEAD 为 `6eb2a07a98fc55eb51c051b57934badddc828bd6`，Java HEAD 为 `46b1653e81050e3cdd9f23506b327bc6676bcbd8`；这些 SHA 只用于定位，不包含当前 dirty/untracked 内容，也不构成候选冻结标识。

## CP0 补齐建议与退出裁决

1. 将本矩阵作为 `CP0_ACCEPTANCE_MATRIX.md` 的证据输入，逐项补上具体 fixture、观察的最终状态/写入次数、依赖替身、数据库类型和实际执行命令结果。此文件不是执行日志。
2. 修复 V08 对称性缺口、V09 旧预览晚到、V16 切账号/工作区延迟响应三项测试后，再进入依赖这些行为的重构切片；至少先确认目标规范和现有行为契约。
3. V10/V11 明确保持 CP8 的单实例范围：CP1 需有端口契约与重建服务实例后“旧 epoch 必须拒绝”的测试。不要拿 Redis/L1 单测宣称重启恢复通过。
4. 规划纵向链路至少补一个能真实启动 BFF 并指向本机 Java 测试服务/临时 DB 的合同集成测试；浏览器要求另按真实 UI 层级验收。执行计划要求哪些真实 UI 路径必须有交互证据，应按此保留边界。
5. 让 CP0 所依赖的测试对目标仓库可见并可冷检出。特别是 Java/BFF/Frontend 各自 Git 根不同，未跟踪测试应进入负责仓库，或明确提供可复现补丁包。

**红方 CP0 判断：当前存在可用的测试基线，但 V01–V16 尚未逐项闭环；S0 测试映射只能判“部分完成、待补关键覆盖和可携带性”。本审查没有运行测试，因此不对任何测试通过率、构建状态或 CP0 总体验收作判断。**

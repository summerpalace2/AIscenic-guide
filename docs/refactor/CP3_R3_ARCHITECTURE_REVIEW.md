# CP3-R3 冻结候选独立架构复审

日期：2026-10-09  
审查方式：只读核对冻结材料、源码和测试源码；未运行测试/Maven，未修改实现文件。  
候选 manifest：`C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP3-r3-candidate.json`  
manifest SHA-256：`821921b0f17fe71db5abd671baa0bb6086c462cb2aab3f2e1fcc8a9d8bfe344c`  
tracked patch SHA-256：`96f04a05e1dead4e9962aa405fbb12b76ff678de1fcd1ae4ba3879a3627dc3c1`

## 裁决

**R3 可通过架构复审，未发现阻断项。** R3 是对 R2 竞态证明的测试强化，没有生产代码改动：manifest 中 `PlannerApplicationService.java` 与 `PlanAdjustmentService.java` 的 slice SHA 与 R2 相同；tracked patch SHA 也相同。变更落在 `PlannerPreviewUseCaseTest` 与实现报告。候选身份及关键 slice 哈希均匹配。

**Application 已拥有 CP3 Apply/Cancel/Reset 的步骤编排。** 新 Preview 在同一既有生命周期锁内完成 actor/session 授权、revision 校验和 generation ticket 登记；耗时规划在解锁后运行并携带该 ticket。Cancel/Reset、Apply 继续复用相同 session stripe 与 continuation 状态。Domain 承担规划规则、校验和 repository 操作，Application 排列用例步骤与 typed result。

**R1 admission/cancel race 已闭环，R3 对两种先后次序增加了更强的确定性静态证据。** R3 测试不再靠 `Thread.sleep` 判断竞争窗口；它确认后到操作到达共享锁入口，并在有界时间内观察其线程处于 JVM `BLOCKED` 状态，随后才放行锁持有方。此证据来自单 JVM 测试源码及 manifest 所述隔离测试结果；本审查没有执行测试，也不将其扩展为多进程或真实数据库并发证明。

## 冻结身份与范围

- manifest 与 tracked patch 原始文件 SHA-256 均与指定值一致。
- manifest 中四个 R3 slice 哈希匹配：`PlannerPreviewUseCaseTest.java`、`PlannerApplicationService.java`、`CP3_IMPLEMENTATION_REPORT.md`、`PlanAdjustmentService.java`。
- 生产 application/domain 文件哈希与 R2 相同；R3 scope 声明仅强化确定性 lock-order tests。tracked patch 与 R2 完全相同，测试文件与实现报告作为 untracked slice 纳入 manifest。
- manifest 记录隔离 suite 为 143 tests、0 failures/errors/skipped；本审查未运行 Maven。其验证边界为单 JVM 与 SQLite；真实 Redis、PostgreSQL、多进程、HTTP response-drop 及 commit-then-throw 均未验证。
- 工作树包含 CP0–CP2、用户已有改动及早期报告。本次未覆盖、还原或清理这些内容，只新增本审查报告。

## 证据和审查结果

### 1. R3 是否只加强测试，没有改生产语义

- `outputs/20261009-CP3-r3-candidate.json` 将 scope 写为 “CP3 R3 only strengthens deterministic lifecycle lock-order tests”。manifest slice 显示：
  - `PlannerApplicationService.java`：`3d14667d7cd6814b8009efa363c5b4174c3f84d8c0a4f19b583c2985c912e380`；
  - `PlanAdjustmentService.java`：`186110d8bc56173836cc11deca3d7f5760d4ec335f5b9c5a342dd2c06f4f9bb6`；
  - `PlannerPreviewUseCaseTest.java`：`0a078b4367ed2225c1a6a364ab2f888250e9ea73a02a981d4858943c0ef4375e`；
  - `CP3_IMPLEMENTATION_REPORT.md`：`78e677f5b276734f775922e3d2a3193a88dcf2176827ac097114c8b4058042e1`。
- 同 manifest 中两个生产文件的哈希与 R2 一致，tracked patch SHA 也与 R2 相同。可证实的 R3 增量是测试屏障强化及对应验证记录；未发现 production path 变动。

### 2. Application workflow owner 与共享状态 authority

- `ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java:115-154`：Preview 识别 Cancel/Reset；其普通 admission 在 `withSessionApplyLock` 中做 actor 授权、`baseRevision` 对比和 ticket 登记；锁释放后将已授权 session、proposal writer 和 admission ticket 一并交给领域 Preview executor。
- 同文件 `:125-137`：Cancel/Reset 在相同生命周期锁内重新读取已授权 session、核对 revision、reset continuation 并构造 typed result。
- 同文件 `:175-209`：Apply 在相同 session stripe 内执行复读授权 session、replay lookup、domain prepare、CAS commit、提交后 completion；Application 随后组装 receipt 与记录 replay。Application 是调用步骤的排序者，不是对领域规则的复制者。
- `ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java:85-93` 仍只有一份内存 replay map、128 个 `sessionApplyLocks` stripe 及一个 `PendingIntentContinuations` authority。Application 与 legacy facade 调用同一个 Spring service，不存在第二套 lock/epoch/replay map。
- `PlanAdjustmentService.java:775-800` 暴露同一锁 callback 与 ticket admission API；legacy preview 在无 admitted ticket 时也在该 lock 中做 refresh、revision 校验和 ticket 登记（`:282-299`）。带 ticket 的 overload（`:440-446`）直接进入已 admission 的执行路径，不重复 begin。

### 3. 锁顺序和 generation fencing

- **Preview 先 admission、Cancel 后到**：Preview 在锁内完成授权/revision/ticket 登记，解锁后做抽取和规划。Cancel 之后取得同一 stripe 并 reset generation。领域流程在 continuation 或 proposal 发布处校验 generation（`PlanAdjustmentService.java:318-319,343-344,384-390,575`），旧 Preview 不能发布 proposal。
- **Cancel 先取得锁**：Cancel 在锁内 reset continuation 并释放；后续 Preview 才能获锁并取得新 ticket，因此作为一个新的请求运行，不被旧的 cancel generation 误杀。
- Apply 也取同一 stripe（Application `:186-209`），并在 CAS 成功后 `completeCommittedApply` 推进 continuation/epoch（domain `:945-964`）。因此在单实例内，Apply、Cancel/Reset、Preview admission 对 session 生命周期采用单一串行边界，规划计算本身不持锁。
- 两项判断建立在 Java synchronized stripe 与共同 service instance 的静态调用图上；stripe 锁不是分布式锁。

### 4. R3 的锁顺序测试质量

`ai/src/test/java/com/ai/guide/application/planner/PlannerPreviewUseCaseTest.java:276-404` 是 R3 强化的主场景：

- Preview-first：在 ticket 注册后用 latch 暂停 Preview，启动 Cancel；Mockito spy 在共享锁调用处发信号，断言 Cancel 线程进入 `Thread.State.BLOCKED` 且 Future 未完成（`:286-335`）。释放 admission 后让 Cancel reset，再释放 extractor；断言旧 Preview conflict、没有 proposalId、proposal writer 未写入、SQLite trip/revision/revision count 不变（`:340-354`）。
- Cancel-first：让 Cancel 在真实执行 `resetAuthorizedContinuation` 后、仍持有锁时暂停；启动新的 Preview，并断言它到达同一 `withSessionApplyLock` 边界后处于 `BLOCKED`，直到 Cancel 放锁（`:356-389`）。随后验证 Cancel 成功、后续 Preview 成功并可应用，revision 只增加一次（`:392-398`）。
- R3 移除 timing sleep，使用显式 latch 与有界 `awaitThreadState`；它更直接地区分了“线程已到锁边界并等待”与“仅仅还没调度到”。该测试是单 JVM、基于 Spring 用例、SQLite session repository 与 spy/mock 边界，不证明多节点协调或真实数据库隔离级别。
- 测试方法仍通过 Mockito spy 截取公共 `withSessionApplyLock` 并观察 JVM BLOCKED 状态；基于该方法唯一进入的 synchronized stripe 及锁内 latch，静态证据足以支持本地锁顺序结论。此处不是生产阻断项。

### 5. CAS、receipt 与非阻断韧性限制

- Application Apply 顺序为 `lookupApplyReplay → prepareApply → commitPreparedApply → completeCommittedApply → assembleApplyReceipt → recordApplyReplay`（`PlannerApplicationService.java:186-209`）。Domain 在 CAS 前检查 revision、proposal 所属会话、过期、epoch、option 与硬约束（`PlanAdjustmentService.java:853-901`），然后以 expected revision 更新 repository（`:923-939`）。CAS conflict 直接返回，不触发成功后的 proposal 清理/epoch 更新/replay 写入。
- CAS 成功后，领域完成阶段清理 proposal 并更新 continuation（`:945-964`）；应用层再组装和记录内存 replay。它们不属于同一数据库事务。若在数据库 commit 后、replay receipt 记录前进程崩溃，重试不能保证拿到原成功回执；该风险不由 R3 的测试强化引入，manifest 明确未测 commit-then-throw/response-drop。
- `applyReplays` 为有界 256 项的 service-instance 内存 LRU（`:85-91`）；`PendingIntentContinuations` 的 epoch、generation、remaining actions 也在内存。多实例部署必须有粘性路由或共享/持久化 authority；否则不同节点之间 Cancel fencing 不成立。这是清楚的部署限制，不是单 JVM R3 回归。
- 封装残留：`withSessionApplyLock(String, Supplier<T>)` 和 public `PreviewGenerationTicket`、`ApplyPreparation`、`ApplyCommit` 将生命周期机制作为可直接调用的 API 暴露。当前生产 application workflow 调用顺序一致，未发现新误用路径；建议作为后续防误用封装项，不阻断冻结候选。

### 6. Typed outcome、授权、wire 与兼容路径

- `PlannerApplicationService.java:160-170` 使用显式 `PlanningActor` 定位 session；Controller 从适配层认证上下文创建 actor 并进入 application typed 方法（`ai/src/main/java/com/ai/guide/adapter/planner/web/PlannerController.java:145-181`）。锁内 refresh 使用已授权 session tuple 与 token，不依赖新路径的 ThreadLocal。
- 新 Preview/Apply result 是 typed outcome；Controller 在 `PlannerController.java:188-212` 做 HTTP status mapping。领域 legacy adapter 仍保留旧 status 映射，属于非阻断架构残留。
- `POST /sessions/{sessionId}/conversation`、`POST /sessions/{sessionId}/adjust/preview`、`POST /sessions/{sessionId}/adjust/apply` 以及 `X-Plan-Session-Token` fallback 代码路径仍在（Controller `:145-181`）。旧 `PlanningApplicationService.applyAdjustment(String, ...)` facade 仍留存；legacy Preview 自行 admission，但复用相同锁和 continuation authority。静态源码未见本轮对 wire path/body 的更改；测试清单报告 Controller 用例通过，但本次未发 HTTP 请求。

## 阻断 / 非阻断矩阵

| 审查项 | 结论 | 等级 |
|---|---|---|
| R3 候选身份、manifest/patch/slice hash | 与指定值匹配；生产文件和 tracked patch 与 R2 同 hash | **通过** |
| R1 workflow owner 阻断 | Application 排列 Preview/Apply/Cancel/Reset；领域提供能力 | **已解决** |
| Preview admission vs Cancel/Reset | 同一 stripe 内先授权、比 revision、登记 ticket；取消同锁 reset | **通过** |
| R3 preview-first / cancel-first 顺序证明 | 两边都有 latch，观测共享锁等待并验证结果；无需 sleep | **通过（源码静态审查，未复跑）** |
| shared lock / epoch / replay authority | 同一 `PlanAdjustmentService` 实例，无复制状态 | **通过（单 JVM）** |
| CAS 与 receipt 处理顺序 | CAS 前 validate；冲突不做后处理；成功后 cleanup/epoch/receipt/replay | **通过，事务边界限制已披露** |
| actor / typed outcome / wire compatibility | 新入口显式 actor；controller outcome mapping 与现有路径/header fallback 保留 | **源码通过；未做 HTTP 实测** |
| public lifecycle API 封装 | 可被内部误用，当前没有错误 production callsite 证据 | **P2 非阻断** |
| 多实例 cancel fencing | 进程内 state 不跨节点 | **部署限制，非 R3 回归** |
| commit 后崩溃、HTTP 回执丢失 | 内存 replay 不提供跨崩溃回放保证 | **韧性缺口，另立目标** |
| Maven/测试独立复跑 | manifest 报告 143 项通过，本审查未运行 | **未由本审查验证** |

## 最终意见

CP3 R3 满足本次架构复审门槛，可解除 R2/R3 Preview admission 线性化的审查阻断。R3 增量是测试时序证据强化，生产工作流、共享 authority、CAS/receipt 顺序和 wire path 均与已审查的 R2 保持一致。后续应把多实例共享 authority 与持久化幂等 receipt 作为独立可靠性议题处理，不将其包装成 R3 已验证能力。

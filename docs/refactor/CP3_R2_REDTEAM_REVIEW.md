# CP3 R2 冻结候选红方复核

日期：2026-10-09  
对象：`outputs/20261009-CP3-r2-candidate.json`  
声明 candidate SHA-256：`88abecaee39dd40be370fbe15e97e54106b3636c44e535f873881015aaaacf54`  
声明 tracked patch SHA-256：`96f04a05e1dead4e9962aa405fbb12b76ff678de1fcd1ae4ba3879a3627dc3c1`  
方式：只读源码、测试和已有原始日志；没有运行 Maven、修改源代码或访问共享 `target`。

## 裁决

**总体：PARTIAL；R1 pre-begin 阻断在源码层已修复，CP3 仍不作全项验收。** Candidate JSON 与 tracked patch 哈希均匹配。R2 将应用 Preview 的授权读取、revision 检查、ticket 登记放到 Apply/Cancel 共用 session lock 内，并把 ticket 带入耗时生成路径；proposal 写入仍经 continuation `commit` 验证 ticket。按当前代码的线性化顺序，R1 的“旧 StoredSession 在取消前读取，取消后才分配新 generation”窗口已关闭，**没有复现 R1 原阻断的代码路径**。

新测试以真实 SQLite session repository、spied proposal port 与 latch覆盖 preview-ticket 先登记、随后 Cancel reset、旧 ticket 最终拒绝的关键路径，并核对 proposal writer 零调用、trip/revision/revision count 不变。测试不是完全确定的双向并发验收：Preview-first 的取消等待断言使用 `Thread.sleep(100)`；Cancel-first 只在取消完成后顺序发起新 preview，且仅断言“不是 CONFLICT”，没有证明新 preview 产出可确认 proposal。因此 R1 核心缺陷为 **PASS（静态代码 + preview-first 回归）**，Cancel-first 并发与“后续 preview 可用”测试强度为 **PARTIAL**。

HTTP 回执丢失、CAS 后异常仍分别是 **NOT VERIFIED**，不作为通过项，也不推导出已实现持久 exactly-once。

## 冻结身份

- Candidate SHA-256 实算：`88ABECAEE39DD40BE370FBE15E97E54106B3636C44E535F873881015AAAACF54`，匹配。
- `outputs/20261009-CP3-r2-tracked.patch` 实算：`96F04A05E1DEAD4E9962AA405FBB12B76FF678DE1FCD1AE4BA3879A3627DC3C1`，匹配。
- 当前 `PlannerApplicationService.java`、`PlanAdjustmentService.java`、`PlannerPreviewUseCaseTest.java` 实际文件 SHA 与 manifest 对应条目相符；审查中候选未变化。

## R1 竞态重新验证

### 锁内授权、revision 与 ticket 登记

Application Preview 的非取消路径现在执行：

1. `PlannerApplicationService.previewAdjustment` 进入 `planAdjustmentService.withSessionApplyLock(sessionId, ...)`；
2. 在锁内调用 `findAuthorized(actor, sessionId, token)`；
3. 在锁内核 request baseRevision 与刚授权读取的 `StoredSession.currentVersion()`；
4. 在锁内调用 `beginPreviewForApplication(authorized)` 并返回 `(session, ticket)`；
5. 释放锁后才做 intent extraction、策略规划；
6. 将 ticket 传给 `previewForApplication`。最终 proposal writer 只能在 `continuations.commit(sessionId, generation, action)` 的 generation guard 下调用。

证据：`PlannerApplicationService.java:140-158`；ticket 载体 `:161-162`；`PlanAdjustmentService.java:775-800`；旧 domain preview facade 也在共用锁中重新授权、重验 revision、分配 ticket（`PlanAdjustmentService.java:252-297`）。Cancel/Reset 使用相同 `withSessionApplyLock`，在锁内授权、revision 检查、reset（`PlannerApplicationService.java:125-138`）。因此 Cancel 和 Preview admission 必定有一个先取得该 session stripe lock：

- Preview admission 先：先注册 ticket；随后 Cancel reset generation；Preview 计算完成时 `continuations.current/commit` 拒绝旧 ticket。
- Cancel/reset 先：先更新 epoch/generation；下一次 Preview 在锁内刷新授权/revision，并获取新的 ticket，按实现契约成为取消之后的新 Preview。

### 对 R1 原场景的复演

在 R1 复演中，让 Preview 阻塞在授权查询返回前、Cancel 完成后再放行。R2 中该查询发生在 app 持有 session lock 时，所以 Cancel 无法先完成；它会等 Preview admission 完成。Preview 获得 ticket 后，Cancel 可先于昂贵生成完成 reset；旧 ticket 随后不能通过 `current` 或 `commit`，proposal writer 不会被调用。

R2 新测试在 ticket 登记处用 latch 暂停（即授权/revision 已在同一锁闭包完成），启动 Cancel，再让提取阶段暂停；取消完成后释放提取，断言旧 Preview `CONFLICT`、无 proposalId、writer 未调用、数据库 trip/version/revision count 不变，并尝试旧 Apply。它直接覆盖了核心“先 admission、后 cancel、再 commit”的场景。

## 既有矩阵复核

| 场景 | 状态 | 独立证据与边界 |
|---|---|---|
| R1 pre-begin race | **PASS（代码层）** | Application 授权、revision、ticket 原子处于共享锁内；ticket向下传入，proposal writer由 `continuations.commit` 守护。此前的旧 snapshot-after-cancel路径不再存在。 |
| Preview-first → Cancel → 旧票据 commit | **PASS（测试覆盖关键时序）** | `PlannerPreviewUseCaseTest.previewTicketAdmissionAndCancelShareOneLinearizationBoundary`：ticket hook在持锁期间暂停，cancel之后 stale preview conflict、writer=0、SQLite snapshot/revision count不变。测试用 `Thread.sleep(100)` 断言 cancel 尚未结束，线程确已在锁上阻塞未由显式 latch/状态探针确认；但后续 reset-before-commit 的断言是确定的。 |
| Cancel-first 与后续 Preview | **PARTIAL** | 代码锁顺序明确定义 Cancel-first后再 admission为新请求。新测试通过在取消完成后顺序发起 Preview，最后只 `assertNotEquals(CONFLICT)`（测试约 `PlannerPreviewUseCaseTest.java:339-342`）；并未以 latch 控制 Cancel 持锁时 Preview 已启动/排队，也没有断言 `SUCCESS + proposalId + canApply` 或可成功确认。建议补双向并发屏障和有效 proposal 断言。 |
| ticket 在解析/写 proposal 时失效 | **PASS** | `PlanAdjustmentService.previewAuthorized` 对带 ticket路径保持该 generation；解析后校验 `current`，proposal 持久写在 `continuations.commit(...generation...)` 中。Cancel/reset会旋转 continuation generation。见 `PlanAdjustmentService.java:318-343`、`:565-576`、`PendingIntentContinuations.java:27-30`。 |
| Apply-first / Cancel-first | **PASS（单 service/Mockito 仓储）** | `CancellationProposalLifecycleTest.java:92-185` 两个 latch 场景：Apply-first只写一次、Cancel conflict；Cancel-first Apply conflict、update=0。不是 SQLite/JDBC双线程 CAS集成。 |
| Application 与 legacy 共用 lock / replay | **PASS（单实例）** | Application用 `withSessionApplyLock`；legacy apply依旧取同一 `sessionApplyLocks`；读取/登记同一 replay map。`PlannerPreviewUseCaseTest` 两方向顺序交叉重放回相同 receipt、revision不再增加。没有多 JVM协调承诺。 |
| CAS前拒绝及持久状态不变 | **PASS（已覆盖子集）** | application用例对缺 proposal、invalid option、取消后旧 proposal核对 trip/version/revisionCount；`prepareApply`在CAS之前先检查 revision、归属、expiry、epoch、option、必需站点和硬规则。未见完整 owner/session/revision/epoch/force矩阵。过期proposal清理可删除缓存，但不写行程DB。 |
| remove异常 | **PASS（代码与替身范围）** | Application `completeCommittedApply` 捕获 remove RuntimeException后继续 epoch/remainder/receipt；legacy路径也 catch。专项测试 `CancellationProposalLifecycleTest.proposalCleanupFailureAfterCasStillRotatesEpochAndReplaysCommittedReceipt` 走 legacy facade/mock port，update一次、同key replay一致；没有 app入口remove故障注入和真实Redis测试。 |
| forceApply幂等请求身份 | **PASS** | Fingerprint同时包括 proposal/option/revision/force；专项用例同 key、不同 force返回409且版本只增长一次。 |
| 复合任务剩余项 | **PASS（SQLite应用用例）** | 第一项receipt返回pendingIntent，按该intentId继续预览并应用第二项，SQLite revision 1→2→3。进程内 continuation，没有重启恢复验证。 |
| HTTP response lost | **NOT VERIFIED** | 没有在 Controller/MVC/HTTP 层丢弃已提交成功响应并用同key重试的注入测试。当前同进程 replay代码不能替代wire证明。 |
| CAS commit后方法异常 | **NOT VERIFIED** | 没有真实 JDBC委托先提交后抛异常的故障注入；与HTTP回执丢失分开保留。进程内 replay和后处理异常窗口未证明可恢复相同receipt。 |
| status/body兼容 | **PARTIAL** | typed outcome→controller status映射与controller直接调用测试在；非 MockMvc/wire，失败body及旧调用者所有字段/状态未全矩阵。 |
| Redis/PostgreSQL/多进程 | **NOT VERIFIED / 不承诺** | 候选说明测试使用独立 SQLite，Qdrant降级；没有 live Redis、PostgreSQL 或多实例锁。 |

## 测试日志边界

本次没有执行测试。Candidate manifest 声明隔离副本 143 项全绿；既存日志 `outputs/20261009-CP3-r2-targeted-tests.log` 末尾为 `Tests run: 143, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`。该结果不覆盖本报告建议的真正并发 Cancel-first admission屏障，也不覆盖 HTTP response-drop 或 commit-then-throw。

## 后续补齐建议

1. 增加 Cancel-first 并发测试：在 Cancel 持共享锁并刚完成 reset 前用 latch 暂停；启动一个 Preview 请求；释放 Cancel；断言 Cancel先返回，随后 Preview 获取新 ticket 并产生可确认 proposal（如 `SUCCESS`、存在 proposalId、`canApply=true`）；Apply后 revision只加一次。
2. 将 `Thread.sleep(100)` 替换为确定性证据：提供测试协调钩子，或在 bounded wait内观察 cancel worker进入 `BLOCKED`/lock wait状态后才释放 ticket 注册；避免“线程没调度到”也满足断言。
3. 增加 HTTP response-drop 与 JDBC commit-then-throw的独立故障测试，并继续将跨重启receipt列为未承诺，除非新增持久化幂等存储。

# CP3 R3 红方复审

日期：2026-10-09  
范围：只读核对 CP3 R3 冻结候选的并发顺序测试和 Apply/Cancel 关键边界；未运行 Maven、未修改源码或测试。  
候选 manifest SHA-256：`821921b0f17fe71db5abd671baa0bb6086c462cb2aab3f2e1fcc8a9d8bfe344c`（与要求一致）  
tracked patch SHA-256：`96f04a05e1dead4e9962aa405fbb12b76ff678de1fcd1ae4ba3879a3627dc3c1`（与要求一致）

## 裁决

**CP3 R3 两种 Preview/Cancel 锁序验收：PASS。CP3 全生命周期验收：PARTIAL，尚不能整体判定通过。**

新增测试确实在共享 session monitor 的边界上建立了两个确定顺序，不再依靠 `Thread.sleep` 或单纯的线程启动先后猜测。Cancel-first 中，取消 reset 已完成但仍持有锁时，后续 preview 被证明阻塞；锁释放后它返回 `SUCCESS`、有 `proposalId` 且 `canApply=true`，再经真实应用服务应用后仅增加一次版本和 revision。Preview-first 中，票据登记发生在锁内，取消请求被证明等待锁；取消完成后先前 preview 变为 `CONFLICT`，不写 proposal，session 快照和 revision 均不变。

这组证据仅证明同 JVM、测试应用服务和 SQLite session repository 上的协调行为。它不覆盖真实 HTTP 响应丢失，也不覆盖 CAS 已持久提交后业务代码抛异常；这两种边界均为 **NOT VERIFIED**，与 R3 manifest 声明一致。除此之外，proposal 删除异常、跨进程幂等持久性及真实 Redis/PostgreSQL 故障恢复仍未被这轮锁序测试证明，所以本轮不能把 CP3 生命周期矩阵整体标记为通过。

## 冻结身份与测试证据

- Manifest 与 patch 的 SHA-256 均匹配委托给定身份。Manifest 中 CP3 切片文件哈希与工作区实际文件匹配：`PlannerPreviewUseCaseTest.java`、`PlannerApplicationService.java`、`PlanAdjustmentService.java`、`CP3_IMPLEMENTATION_REPORT.md`。
- Manifest 引用隔离副本 Maven 日志 `outputs/20261009-CP3-r3-targeted-tests.log`，记录 143 tests、0 failures、0 errors、0 skipped、BUILD SUCCESS。此为读取已有日志，不是本次运行结果。
- Manifest 对环境限制有明确声明：单 JVM/SQLite；无 live Redis、PostgreSQL、多进程、HTTP response-drop、commit-then-throw 验证。

## R3 锁序测试审查

测试位置：`ai/src/test/java/com/ai/guide/application/planner/PlannerPreviewUseCaseTest.java:275-415`，新增测试 `previewTicketAdmissionAndCancelShareOneLinearizationBoundary`。

### Preview admission 先行、Cancel 后到：PASS

1. `beginPreviewForApplication` spy 先调用真实方法完成 ticket 登记，再发出 `ticketRegistered` latch，并在 admission callable 返回前暂停（测试 296-301 行）。应用入口 admission 在同一 `withSessionApplyLock` 内完成授权、revision 检查和 `beginPreviewForApplication`，然后才释放锁并调用耗时 preview（`PlannerApplicationService.java:140-154`）。因此暂停点确实位于“ticket 已登记且 admission 锁仍持有”的状态。
2. 后续 cancel 到达同一个 `withSessionApplyLock` spy 后，测试轮询到其线程状态 `BLOCKED`，同时断言 cancel future 未完成（测试 322-332 行）。锁实现是固定 stripe 对象上的 `synchronized` monitor（`PlanAdjustmentService.java:775-779`），故这里的 BLOCKED 是等待 admission monitor 的可观察证据，不只是线程已经启动。
3. 释放 admission 后，preview 进入被 latch 暂停的 intent extraction；cancel 可先取得锁并完成 reset。之后释放 extraction，旧 preview 返回 `CONFLICT`、不含 proposalId，proposal writer `never().put(...)`；测试也比较了 session version、trip 快照与 revisionCount 未变化（测试 334-355 行）。
4. 这覆盖了旧 preview 不能越过 cancel 写回 proposal 的关键风险。额外的 fake proposal apply 断言为 `BAD_REQUEST`，只证明不存在该伪造 proposal；它不能替代读取实际旧 proposal 再应用的独立测试，但 `never put` 已证明本分支没有创建可应用的旧 proposal。

### Cancel reset 先行、Preview 后到：PASS

1. `resetAuthorizedContinuation` spy 调用真实 reset 后触发 `secondCancelReset` latch，并在返回前暂停；调用栈仍处于取消路径的共享 session lock 内（测试 356-369 行；生产路径 `PlannerApplicationService.java:124-137`）。
2. 随后启动新 preview，先等待它进入 `withSessionApplyLock`，再断言其线程确实为 `BLOCKED` 且 future 未完成（测试 371-383 行）。这建立了明确的 cancel-reset-before-preview-admission 顺序，而非靠 sleep 推断。
3. 释放取消后，取消结果为 `SUCCESS`；后续 preview 返回 `SUCCESS`、非空 `proposalId` 且 `canApply=true`，并通过应用服务成功应用。最终 session version 与 revisionCount 各恰好增加 1（测试 384-398 行）。这满足本轮委托要求的“后续 preview 可正常新建并应用一次”。

### 确定性与线程状态观察：PASS（有轻微测试工程观察）

- R3 测试不含 `Thread.sleep`。通过 latches 控制票据登记、取消 reset、提取暂停和释放，并以 `Future` 等待结果；关键线程顺序有显式屏障。
- `awaitThreadState` 在有 5 秒上限的循环内检查 `Thread.State.BLOCKED` 并调用 `Thread.onSpinWait()`（测试 407-415 行）。针对当前 `synchronized` stripe lock，BLOCKED 是合适的锁等待状态；断言不是仅凭 `CountDownLatch` 到达就推断已阻塞。
- helper 使用短时忙轮询，可能在慢机器上消耗一个 CPU 核至多 5 秒；它有确定超时且不造成逻辑误判。后续可用测试专用“已进入锁门口”钩子减少忙等，但这不是当前阻断项。
- 新测试的 latch 等待有超时，finally 中会释放所有暂停 latch 并关闭 executor，避免失败时线程永久挂住。

## 其他生命周期矩阵及边界

- **当前锁序用例的最终 DB/session 副作用：PASS（本地 SQLite 范围）**：preview-first 断言 session 快照及 revisionCount 不变；cancel-first 经应用服务实际执行后断言版本/revision 各只增 1。结果不是纯 Mockito 仓储模拟。
- **HTTP/wire status 与 body：PARTIAL**：新增竞态测试直接调用 application service 并检查 outcome/body 字段，不经过 Controller/MockMvc/真实 HTTP 路由。原有 Controller 测试可提供部分 DTO 映射证据，但这两种新竞态的真实 wire response 未在此测试中验证。
- **DB 成功但 HTTP 响应丢失：NOT VERIFIED**：无 HTTP filter/proxy 丢弃成功响应后按原幂等键重试的故障注入。manifest 亦明确列为 limitation。
- **CAS 已提交后业务抛异常/进程中断：NOT VERIFIED**：没有“仓储先提交、后抛异常”的故障注入，也没有验证重试回执及 continuation 恢复。不能将正常 apply 后 revision +1 的断言解释为覆盖此路径。
- **proposal remove 异常及缓存/数据库跨层恢复：本轮未验证**：R3 目标聚焦锁序；现有通用生命周期设计报告列出的 remove-after-CAS 失败情形仍需单独通过应用入口/仓储故障测试验收。
- **同进程 replay、复合任务续接、forceApply fingerprint、拒绝路径和权限/revision/epoch 门禁**：这些并非 R3 新增锁序测试的证明对象。应沿用 CP3 前序审查中的各自证据；不能由本测试的 PASS 推导它们全量通过。特别是单进程 replay 不承诺跨进程/重启恢复。

## 结论与后续验收条件

就 R3 指定的主要风险而言，取消先行与 preview 先行都在真实共享 monitor 的线性化边界上固定，断言了票据/取消时序、proposal 写入与最终 revision 副作用，**本轮锁序修复可接受，没有发现阻断缺陷**。测试统计来自已有隔离运行日志，未由本审查复跑。

CP3 整体仍标记 **PARTIAL**。要解除未验证项，仍需独立故障注入覆盖：（1）数据库事务成功后 HTTP 响应丢失、同幂等键重试；（2）CAS commit 后异常；（3）CAS 成功后 proposal 删除失败及后续恢复；并明确单进程幂等回执的保证边界。对于涉及 HTTP 的验收应记录真实 status/body；对于仓储提交故障应分别断言持久 revision、CAS 次数和重复应用结果。

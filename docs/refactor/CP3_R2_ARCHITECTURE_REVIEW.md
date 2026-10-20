# CP3-R2 冻结候选独立架构复审

日期：2026-10-09  
审查方式：只读源码、测试源码和冻结清单核验；未运行 Maven，未修改业务源码。  
候选清单：`C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP3-r2-candidate.json`  
候选 JSON SHA-256：`88abecaee39dd40be370fbe15e97e54106b3636c44e535f873881015aaaacf54`  
tracked patch SHA-256：`96f04a05e1dead4e9962aa405fbb12b76ff678de1fcd1ae4ba3879a3627dc3c1`

## 裁决

**R1 workflow-owner 阻断已解决，R1 Preview admission/cancel 竞态也已闭环。** 新 Preview 流程由 `PlannerApplicationService` 持有步骤顺序：在与 Apply、Cancel/Reset 共用的 session stripe lock 中授权、核对 base revision、登记 generation ticket；释放锁后才进行意图抽取与领域规划，并把 admission 时签发的 ticket 传入领域执行。Cancel/Reset 在同一把锁内重读授权 session、核对 revision 并 reset 同一个 continuation。慢 Preview 在取消或新一轮预览之后必须通过 generation 门禁才能发布提案，因此旧结果不能复活。

**CP3 应用层工作流归属：通过。** Application 不再把 Preview/Cancel 整段委托给领域方法；它编排 admission、分流和 typed outcome。此前 CP3 的 Apply 多步排序仍由 application 明确拥有，领域服务提供准备、CAS 提交和提交后状态能力。Controller 仍负责 HTTP outcome 到状态码的映射，旧 facade 保留。

**本候选没有发现静态阻断项。** 仍有几项非阻断边界：锁/generation/replay/continuation 是单 JVM 内存 authority；Apply 的持久化 CAS 与内存 replay 不是一个事务，若在 CAS 成功后、replay 记录前进程崩溃，重试无法保证拿到原成功回执；通用锁 callback 与可公开构造的阶段 record 仍有内部误用风险。这些限制均不抵消本次 R2 的进程内线性化修复。

## 冻结身份

- 候选 JSON 与 tracked patch 的 SHA-256 均与指定值一致。
- R2 slice 的四个文件 SHA-256 与清单一致：`CP3_IMPLEMENTATION_REPORT.md`、`PlannerApplicationService.java`、`PlanAdjustmentService.java`、`PlannerPreviewUseCaseTest.java`。
- 冻结清单包含 CP0–CP2、用户既有工作树差异及此前报告。本审查未覆盖、还原或清理这些改动；只新增本独立审查报告。
- 清单记录的隔离测试为 143 项通过，0 failures/errors/skipped。本次未复跑。清单明确限制为单 JVM/SQLite；没有真实 Redis、PostgreSQL、多进程或 HTTP 响应丢失验证。

## 证据与判断

### 1. Application 是否真正拥有 Preview workflow

- `ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java:115-123` 做 V1 gate、读取 body token，并将 reset/cancel 与普通 Preview 分流。
- 同文件 `:140-154` 是 R2 关键路径：在共享 lifecycle lock 内调用 `findAuthorized`、比较 `baseRevision`、调用 `beginPreviewForApplication`，形成包含已授权 session 与 ticket 的 `PreviewAdmission`；退出锁后调用 `previewForApplication(..., proposalRepository::put, ticket)`。因此耗时抽取/策略计算不会长期占用锁，而 application 明确安排了顺序与授权门禁。
- `ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java:434-446` 的 ticket overload 将 admission ticket 传入 `previewAuthorized`。`previewAuthorized :282-299` 仅在 legacy 调用没有 ticket 时才自行 admission；application 路径使用已登记 ticket，不会二次 `begin()`。session 与 ticket 的 sessionId 不匹配会返回 conflict。
- 领域服务仍拥有意图识别、规划策略、可行性校验和提案生成，这些属于领域计算；Application 不需要复制这些算法。相对于 R1/R2 的 CP3 workflow ownership 目标，执行顺序与生命周期边界现由 application 排列，真实所有权已迁移。

### 2. Preview admission 与 Cancel/Reset 的线性化边界

- Preview admission 和 Cancel/Reset 使用同一个 `withSessionApplyLock(sessionId, ...)`：Application `:125-148`。底层 `PlanAdjustmentService.java:775-780` 以同一 `sessionApplyLocks` stripe 执行同步 callback；数组仅在该 service 实例创建一次（`:92-93`）。
- Ticket 只能在锁内通过 `beginPreviewForApplication` 登记（`:796-800`）；该方法的注释也明确要求调用方持有共享 lifecycle lock。取消分支在相同锁内复读 session、比较 base revision，随后调用 `resetAuthorizedContinuation`（Application `:125-136`；domain `:787-789`）。这关闭了“Preview 已检查 revision，但还没 begin ticket 时 Cancel 抢先 reset，随后 Preview 再登记新 ticket”的窗口：二者只能按同锁先后线性化。
- 领域执行在生成回复/提交 continuation 前再次校验 ticket：`PlanAdjustmentService.java:318-319,343-344,384-390`；提案写入由 `continuations.commit(sessionId, generation, () -> proposalWriter.accept(proposal))` 原子围栏（`:575`）。取消 reset 或更新 generation 后，过期 Preview 无法发布 proposal。
- 测试源码 `ai/src/test/java/com/ai/guide/application/planner/PlannerPreviewUseCaseTest.java:273-347` 使用 latch 在 ticket 登记处暂停，并断言 Cancel 等待共享锁；释放 admission 后暂停 extraction，再执行 Cancel；最后断言迟到 Preview 返回 conflict、没有 proposalId、writer 未调用、trip/revision/revision history 不变，旧 proposal apply 被拒绝，取消先完成后新 Preview 可继续。测试结构覆盖了 admission 与 cancel 两种线性化顺序。测试此次只静态审阅，未执行。
- 同一锁也包围 Application Apply（`:186-209`），因此本地单实例中 Apply 与 admission/reset 不会同时穿过临界区。长时间规划发生在锁外，Apply/Cancel 可及时取得锁并使其 ticket 失效。

### 3. Apply 的 CAS、完成副作用与回执顺序

- Application Apply `PlannerApplicationService.java:175-209`：先基于 `PlanningActor` 授权，再在共享锁内刷新 session；依次执行 replay lookup、`prepareApply`、`commitPreparedApply`；仅 CAS 提交成功后执行 `completeCommittedApply`，由 application 组装 receipt 并调用 `recordApplyReplay`。CAS conflict 会提前返回，不执行后续状态完成或回执缓存。
- Domain `PlanAdjustmentService.java:853-901` 在提交前校验 revision、proposal 存在/归属/TTL/epoch、选项、required stop 和可应用性；`:923-939` 才调用 repository 的 expected-revision update。`:945-964` 在成功提交后清理 proposal、推进 continuation/epoch，并构造应用层组装回执所需的 completion 数据。
- `applyReplays` 是 `PlanAdjustmentService` 单一实例内最多 256 条的 LRU map（`PlanAdjustmentService.java:85-93`），同 session 请求由 stripe lock 串行化。CAS 是 trip 持久化提交点；proposal cleanup、continuation 与 replay 并非同一个数据库事务。
- **非阻断、既有韧性限制**：CAS 成功后到 `recordApplyReplay` 前若进程崩溃或发生未捕获异常，trip 已提交，但内存 replay 还没有原成功 receipt；proposal cleanup/`afterApply` 也可能已发生，客户端重试不能依赖得到与首次相同的成功回执。此问题超出 R2 preview admission 改动范围；当前候选清单亦声明未验证 response-drop/commit 后故障。若要承诺跨崩溃幂等，需要后续持久化操作 receipt 或 outbox/事务化设计。

### 4. Actor、领域边界与 Spring wiring

- Application 是 Spring `@Service`，构造注入 `PlannerService`、`PlannerSessionRepositoryPort`、`PlanAdjustmentService`、`ProposalRepositoryPort`（`PlannerApplicationService.java:31-47`）。状态协调仍通过同一个 `PlanAdjustmentService` 实例访问，不复制 lock、generation 或 replay 权威。
- 新请求中的 `PlanningActor` 在 application 的 `findAuthorized` 使用（`:160-170`）；新 HTTP controller 从认证上下文构造 actor 并调用 typed application contract（`adapter/planner/web/PlannerController.java:145-181`）。锁内 Apply refresh 用已授权 session 和 token（Application `:186-190`），没有把新 actor workflow 绕回 ThreadLocal。旧 `PlannerService`/domain 入口保留给兼容调用。
- typed application result 包含 enum outcome 与 body，不带数字 HTTP status；Controller 在 `PlannerController.java:188-212` 映射 HTTP code。Domain 仍通过 `PlannerService.ServiceResult.status()` 适配旧状态语义，属于 transport-shaped legacy 残留，非新 wire 破坏。
- 非阻断封装债务：`withSessionApplyLock(String, Supplier<T>)` 是公开通用 callback；`PreviewGenerationTicket`、`ApplyPreparation`、`ApplyCommit` 为可公开构造的 record。当前 production application 调用顺序正确且检索未见第二条新 workflow caller，但未来内部调用可误用 lock / 阶段 API。建议后续以窄 lifecycle port 或不可伪造阶段 token 收敛；不是本候选可由外部请求触发的缺陷。
- 单实例限制：这把锁及 `PendingIntentContinuations`、`applyReplays` 是 JVM 内存对象；多副本部署时不同实例的 Preview/Cancel generation fencing 不共享。持久化 trip 的 expected-revision CAS 可避免部分重复提交，但不能让跨节点的取消 generation 线性化。该部署限制不是 R2 引入，当前清单没有多进程测试。

### 5. Legacy 与 wire compatibility

- Controller 仍使用 `POST /sessions/{sessionId}/conversation`、`POST /sessions/{sessionId}/adjust/preview`、`POST /sessions/{sessionId}/adjust/apply`，请求 token 缺省时仍从 `X-Plan-Session-Token` 填入（`PlannerController.java:145-181`）。Apply 路径、Preview/Conversation 分流与 Controller status mapping 均保留。
- `PlanningApplicationService` 同时保留显式 actor typed overload 与旧兼容 Apply facade（接口 `:43-50`；实现 `PlannerApplicationService.java:249-251`）。旧领域 Preview 仍自带 admission lock (`PlanAdjustmentService.java:282-293`)；新 application overload 使用 ticket admission。两条入口最终共享同一个状态 authority，没有另建一套 generation。
- 兼容结论是静态路径检查；本审查没有独立发送 HTTP 请求或执行 controller 测试。实现方清单报告 143 项定向测试通过。

## 阻断/非阻断矩阵

| 项目 | 结论 | 严重度 |
|---|---|---|
| CP3 workflow owner | Application 明确排序 Preview、Apply、Cancel/Reset 阶段；R1 整段委托阻断解决 | **通过** |
| R1 pre-begin Preview/Cancel race | admission authorization/revision/ticket 与 reset 共用 stripe；latch 测试源码覆盖两种先后 | **通过（静态审阅测试源码）** |
| ticket 到领域执行 | application ticket 原样传递；typed overload 不重复 begin；过期 generation 不能写 proposal | **通过** |
| shared locks / epoch / continuation / remaining actions | 同一个 `PlanAdjustmentService` 持有 authority，legacy 入口复用 | **通过（单实例）** |
| Apply CAS 与后处理 | CAS conflict 无后处理；成功后 cleanup/epoch/completion，再组 receipt 与 replay | **通过；非事务性限制已披露** |
| Spring / DIP / actor | constructor wiring 正常，explicit actor 用于新入口，未绕回 ThreadLocal | **通过** |
| typed outcome / HTTP mapping | 新 application result 不带 HTTP 数字状态，controller 映射；domain 仍有 legacy adapter | **通过，P2 架构残留** |
| wire 与 legacy path | routes、POST、header fallback、typed 新路径与旧 facade 保留 | **源码通过；本次未跑 HTTP 测试** |
| 通用锁 API / 阶段 record | 当前调用顺序正确，但内部 API 容易误用 | **P2 非阻断封装债务** |
| 多节点一致性 | JVM 内存 generation/replay/continuation 无跨进程 fencing | **部署限制；不属于 R2 回归** |
| CAS 后崩溃及幂等 receipt | commit 与 replay 非原子，崩溃窗口可导致重试无法复现原回执 | **非阻断、既有韧性限制；需独立设计** |
| 独立执行测试 | implementation report 记载 143 项通过；本次未运行 Maven | **未由本审查执行验证** |

## 最终意见

CP3-R2 可通过本次架构复审：Application 已实际拥有流程排序；preview admission 与 cancel/reset 在相同 lifecycle lock 中建立唯一线性化边界；旧的 pre-begin 竞态已关闭，且迟到 Preview 的 proposal 写入受 ticket/epoch 门禁保护。无需因本次复审阻塞 R2。

建议后续登记三项架构韧性工作：将进程内 lifecycle authority 迁为可跨节点共享或明确固定单实例；把成功 receipt 与 trip commit 做持久化幂等；收窄公开 lock callback/阶段对象 API。它们是额外目标，不应误报为当前 R2 的回归失败。

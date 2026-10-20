# CP3 红方测试设计：Apply / Cancel 生命周期

日期：2026-10-09  
范围：按 `COLA_REFACTOR_EXECUTION_PLAN.md` §3.4 设计确定性验收，不运行测试、不修改生产代码。本文是实施前的测试设计，不表示 CP3 已实现或通过。

## 红方结论

CP3 的退出条件不能仅由 `PlanAdjustmentService` 的单元测试、Mockito 调用次数或 `PendingIntentContinuations` 纯状态测试证明。至少要有一组应用入口级的测试覆盖 wire 状态、一个真实原子 CAS 仓储（或能严格模拟提交语义的测试仓储）覆盖最终持久状态，以及可控 latch/barrier 固定并发顺序。

现有测试有一项较好的 apply-first 竞态测试，但使用 Mockito repository；没有看到对称的 cancel-first 屏障测试。尚未见“提交后 HTTP 回执丢失”“提交后 proposal cache remove 异常”“迟到预览越过取消”“真实复合任务首项应用后续项仍可续接”的集成生命周期覆盖。幂等 replay 仅有同请求重放；现有指纹不包含 `forceApply`，因此同 key、仅 force 标志不同的请求会被当作同一请求，这是当前明确的测试缺口/实现风险。

## 契约锚点与现有测试证据

- 计划 §3.4 明确要求：共享会话生命周期协调器；验证顺序为所有权→请求身份/幂等→revision→提案归属/有效期/epoch→选项与硬约束→CAS→回执/剩余任务；明确 DB、缓存、HTTP 回执不是一个全局事务；覆盖 DB 提交但响应丢失、缓存失败、两种 apply/cancel 顺序；版本仅增一次、拒绝不改 DB、首项应用后保留剩余任务、迟到预览不能复活已取消 proposal。定位：`D:/scenic-guide/docs/refactor/COLA_REFACTOR_EXECUTION_PLAN.md:109-120`。
- 当前 apply 在同一进程内以 session stripe 锁串行；cancel reset 也取该锁。`PlanAdjustmentService.java:733-751`。
- 当前 apply 顺序与副作用点见 `PlanAdjustmentService.java:753-882`：replay fingerprint 为 `proposalId|optionId|revision`（765）；revision/提案 epoch 校验（776-797）；CAS 写入（823-828）；proposal remove 与 `afterApply` 在 DB 更新之后（838-839）；replay 结果最后写入（878-881）。
- 现有 apply-first 测试：`CancellationProposalLifecycleTest.java:62-107`。通过 `updateEntered/releaseUpdate` 固定 apply 先到仓储更新，再启动 cancel；期望 apply 200、cancel 409、单次 update。它使用 Mockito repository，不证明真实 DB 提交语义。
- 现有顺序取消后 apply：`CancellationProposalLifecycleTest.java:14-37`，是先后顺序而非并发屏障；断言取消后 apply 409、没有 update。
- 现有反向否定取消：`CancellationProposalLifecycleTest.java:38-60`，取消语义为 false 后可应用，update 一次。
- 现有幂等重放：`AgentBRedTeamAssaultCampaignTest.java:456-507` 与 `PlanAdjustmentServiceTest.java:820-836`，均覆盖同 key、同请求的顺序重放；前者允许 200 或安全拒绝，未固定 wire 合同，也没有对 update 精确一次的 mock verify。
- 现有 stale revision：`PlanAdjustmentServiceTest.java:839` 起；force/hard constraint 场景见 `PlanAdjustmentServiceTest.java:2587-2618`。它们不是完整的越权、epoch、revision、proposal ownership拒绝矩阵，也未统一断言 DB 未变。
- `PendingIntentContinuations` 纯逻辑测试覆盖部分多动作状态，但不能替代真实 apply → afterApply → 返回 pendingIntent → 续接下一项的完整业务生命周期。

## 屏障测试设计

所有并发测试应使用同一个 `sessionId`、同一个服务实例与显式 `CountDownLatch`/barrier；不要用 `Thread.sleep` 推断顺序。每个场景都记录 HTTP status/body、DB revision/plan、CAS 写次数、proposal 与 continuation 最终状态。生产数据库可用隔离测试 DB；若用 fake，必须实施与生产相同的 compare-and-set 规则，并注明这不是数据库集成证据。

| 场景 | 可控时序与刺激 | 期望 wire 响应 | 期望最终状态/副作用 | 当前覆盖 |
|---|---|---|---|---|
| Apply 先完成，Cancel 后到 | 在 repository CAS 入口 latch；Apply 持有会话协调器并停住；启动 Cancel 并确认线程已发起；释放 CAS | Apply `200 {ok:true, applied:true, currentVersion:V+1,...}`；Cancel 依当前契约 `409` revision conflict，`cancelled` 不为 true | DB 版本只增 1；计划等于选中 proposal；一个 CAS；proposal 已消费；旧 continuation 不可再应用 | **部分覆盖**：`CancellationProposalLifecycleTest:62-107` 有屏障但 mock repo，无应用层/HTTP wire 验收 |
| Cancel 先完成，Apply 后到 | 在 cancel 进入 coordinator 后 latch；启动 apply 并确认其已排队；让 cancel reset/epoch 更新返回，再释放 apply | Cancel `200 {ok:true, reset:true, cancelled:true, modified:false}`；Apply `409` stale/ended proposal | DB 完全不变、版本仍 V；CAS=0；proposal epoch 失效；没有 applied receipt | **没有并发覆盖**；顺序覆盖 `CancellationProposalLifecycleTest:14-37` |
| DB 已提交，HTTP 响应丢失，重试同 key | 真实 HTTP/MockMvc 层：允许 service 完成 commit 和生成 receipt，然后由 response filter 丢弃首个响应；客户端用原请求/同 key 重试 | 首次客户端观测 timeout/断连；重试在进程内应返回相同 200 receipt（若产品契约仅保证 no-double-write，需明确可返回 409）；不可返回伪成功或第二次应用 | DB 版本仅 V+1；CAS=1；回执 `currentVersion=V+1` 与 DB 一致；重启后不承诺持久 replay，除非持久化幂等记录 | **没有**；现有同 key重放是直接顺序 service 调用 |
| CAS commit 后、业务方法异常/进程中断 | 测试仓储先原子提交，再在返回前抛出可控异常；随后同 key 重试 | 首次 5xx/连接异常；重试结果按 CP3 明确的恢复边界定义（通常 409 当前版本冲突，不能谎称 replay）；不可再 200 applied 第二次 | DB=V+1、CAS=1；旧 proposal 不可再次产生副作用；检查 pending continuation 能否恢复/明确标记不可恢复 | **没有**；与“HTTP 返回丢失”不是同一个故障点 |
| Proposal cache remove 抛异常 | CAS 正常 commit；proposal store `remove` 阻塞后抛异常；读取 DB 后重放同请求 | 首次应为受控 5xx；重试不可二次写入。建议返回 409 stale revision（或可重建相同 receipt，但需有真实持久回执依据） | DB=V+1、CAS=1；即使 L2 proposal 仍在，也因 revision/epoch 不可应用；continuation 不得悄悄丢失或需明确恢复策略 | **没有**；`PlanAdjustmentService:838-839` 显示 remove 在 CAS 后、afterApply 前 |
| Proposal remove 未抛错但 L2 删除失败/陈旧命中 | 实际 ProposalRepositoryPort/Redis adapter 故障注入（不能只 mock repository）；删除后重启服务再用旧 proposal apply | 首次 apply 200；旧 proposal 重放 409/400，不能再次 200 applied | DB 只增一次；版本/epoch 拦截二次执行。明确缓存 tombstone/epoch 是否在重启仍有效 | **没有**；只读到 service 层缓存调用，无端到端证据 |
| 迟到旧 preview 在 Cancel 后返回 | fake extractor/strategy 在生成旧 proposal 前 latch；启动 preview，等待 extractor 阻塞；完成 cancel/reset；释放旧 preview | 旧 preview 返回明确 409 stale/superseded（或无 proposal 的业务结果）；不能返回可确认 proposal | DB 不变；proposal store 不新增旧 proposal；epoch 维持取消后的 epoch；用其 proposalId apply 409、CAS=0 | **没有**；已有取消测试未与进行中的 preview 并发 |
| Preview commit 先于 Cancel，Cancel 后立刻 Apply 旧 preview | 让 preview 先完成 commit；cancel reset 后 apply | preview 200 可回旧 proposal；cancel 200；随后 apply 409 | proposal 被取消态/epoch 作废；DB 不变 | **部分覆盖**：顺序 cancel→apply 覆盖，但没有 preview/cancel 双竞态 barrier |
| 同 key、不同 proposal/option/revision | 首次同 key成功；依次改 proposalId、optionId、baseRevision 各发一次 | 每个变体返回 409 idempotency mismatch；body 使用统一错误结构；不能返回首次成功 receipt | DB 仍 V+1；CAS 总计 1；不污染首次 receipt | **部分覆盖**：`PlanAdjustmentService:765,769-771` 有 fingerprint 比较；现有测试没有系统矩阵 |
| 同 key、仅 `forceApply` 不同 | 首次 `forceApply=true` 对软不可行选项成功；同 key、其余字段相同改为 false 重试 | 应按请求身份不匹配返回 409（或 contract 明确规定 force 不属于幂等身份）；不能静默回放强制成功并让客户端以为非强制提交 | CAS=1；DB不再变化；receipt的 `forced` 不与第二个请求语义冲突 | **没有且当前有风险**：当前 fingerprint 不含 `forceApply`（`PlanAdjustmentService:762,765`），same-key retry 会返回第一次 receipt |
| 同 key跨调用者/越权重放 | Owner 成功后，非 owner 用相同 key/proposal 重放；另测无 token/错误 token | 按权限契约 404 或 403；不得返回 owner 缓存的 200 receipt | DB维持 V+1、CAS=1；不泄露 trip、proposal 或 receipt | **缺测试证据**；当前顺序是先 `findAuthorized` 后查 replay（755-767），需在应用/HTTP 层锁定 |
| revision 拒绝 | preview基线 V；先通过另一合法变异将 session 更新为 V+1，再用旧 proposal/旧 revision apply | 409，包含当前 revision 或稳定错误码；不返回 applied | DB保持 V+1，不发生二次写；CAS=1（仅先前合法变异） | **部分覆盖**：单元 stale revision存在，最终 DB side-effect断言需统一 |
| proposal 所属会话/用户拒绝 | session A proposal 用于 B；同一 owner不同 session与跨 owner各覆盖 | 400/404按既有 wire 兼容合同；错误 body不含 proposal 内容 | B、A数据库都不变；CAS=0；不删 A 的有效 proposal | **部分/未知**：源码有 owner/session 校验 `PlanAdjustmentService:755-784`，未定位到完整 wire 矩阵 |
| epoch拒绝 | proposal旧 epoch；推进/取消会话 epoch 后用原请求应用 | 409 stale conversation/proposal | DB不变、CAS=0；新 epoch 不回滚；旧 proposal不能由旧 preview迟到写回 | **部分覆盖**：有单独 stale proposal测试入口，但暂无竞态与副作用组合证明 |
| 过期 proposal | 固定时钟刚过 expiresAt；apply | 400/409按现契约；不能成功应用 | DB不变、CAS=0；过期缓存可清理；后续新 proposal正常 | **部分覆盖/需静态确认**：apply代码会 remove expired后报错（785-788），需明确边界与 wire |
| 硬约束拒绝/force越权 | 固定站、开放时间或物理冲突；`forceApply=true` | 400，语义为硬约束拒绝；不得变成 200 | DB不变、CAS=0；proposal状态可继续选择/明确失效策略 | **部分覆盖**：`PlanAdjustmentServiceTest:2587-2618` 覆盖一类 NO_ACTUAL_CHANGE，不等于所有硬约束 |
| 无候选/不可应用 proposal | preview `canApply=false` 或无 option，再 apply/force apply | 400，不返回 `applied:true` | DB不变、CAS=0；不能生成版本/空 mutation | **部分覆盖**：现有 service 测试有单一情形；缺 wire 与最终 DB全面断言 |
| CAS 竞争者修改版本 | 两个并发合法 apply 或另一条 plan mutation 在 CAS 前提交；屏障卡在 CAS | 一个 200；另一个 409 conflict，并携带最新 revision/快照 | 只允许一个基于 V 的 CAS成功；最终 V+1（若第二请求以 V为预期）；不存在丢失更新 | **未证实真实 DB语义**：当前 apply/cancel屏障使用 Mockito repo |
| 复合任务首项应用后续接 | 生成至少2动作的 continuation；apply首项；读取 wire receipt；按 pendingIntent/nextActionPrompt 回答下一项并生成/应用第二项 | 首次200包含 `pendingIntent` 与 `nextActionPrompt`；续问 preview可承接目标；第二次成功 200 | 第一次DB=V+1且只应用动作1；剩余动作状态可续接；第二次DB=V+2且只应用动作2；取消中间任务后不能复活 | **部分覆盖**：纯 continuation测试不是持久化 apply闭环；需实际 PlanAdjustmentService/Application/HTTP 合约测试 |
| 剩余任务与缓存失败交叉 | 首项CAS成功后令 proposal remove 失败；检查剩余任务状态、再次请求如何恢复 | 失败响应按前述 remove 契约；不能向客户端暗示“首项失败”而行程已变更且后续任务静默丢失 | DB首项只写一次；后续动作仍存在或明确可恢复/明确需重新规划；无重复首项 | **没有** |

## 建议的确定性测试钩子

1. 在应用用例边界暴露测试替身端口，而不是反射私有锁：`ProposalRepositoryPort`、`PlannerSessionRepositoryPort`、生命周期协调器。测试端口用 `CountDownLatch` 分别控制 CAS 前、CAS commit 后、proposal remove 前后、preview continuation commit 前。
2. 两个竞态测试固定因果：`apply-first` 在 CAS latch停住后再启动 cancel；`cancel-first` 在 coordinator 已登记取消但尚未返回时再启动 apply。必须 await 每个阶段信号，不能靠线程启动顺序猜测。
3. “HTTP 回执丢失”要在 service 已返回且事务已提交后丢弃 HTTP 响应；“仓储提交后异常”另测，不得把两者混为一项。
4. 所有拒绝路径统一断言数据库快照深等于操作前快照（包括 revision、plan JSON、applied preferences、日志/事件计数），并断言 CAS写入次数为0；CAS冲突用例除外，需区分另一合法写入。
5. 测试真实wire：`PlannerController`/MockMvc 走实际 POST 路由，核实 HTTP status、字段名、空字段/缺失字段、错误码/消息和 `currentVersion`，不能只看 `ServiceResult`。
6. 恢复边界要写清：同一进程内 replay map 与跨重启 replay 是不同承诺；若无持久幂等记录，跨重启可冲突但必须保证不重复写入，wire应明确要求用户刷新/重新预览。

## CP3 红方门槛

只有当所有表格中标为“没有”或“部分”的关键场景有确定性证据、forceApply 身份语义得到明确处理、apply/cancel顺序双向屏障均通过，且最终数据库 revision/plan 与 wire receipt 一致，才可标记 CP3 通过。Mockito-only、纯 continuation 状态测试或手工报告的“全绿”不构成 DB/HTTP 生命周期验收。

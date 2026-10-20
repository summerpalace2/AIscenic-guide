# CP3-R5 冻结候选独立架构复审

日期：2026-10-09  
审查方式：只读核对 manifest、测试日志、实现报告、源码和测试源码；未运行测试/Maven，未修改业务源码。  
候选 manifest：`C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP3-r5-candidate.json`  
manifest SHA-256：`b73004edcef0e53dafda352bfba6f336f1c8fe010d755a754695ed24e4eaba62`  
tracked patch SHA-256：`96f04a05e1dead4e9962aa405fbb12b76ff678de1fcd1ae4ba3879a3627dc3c1`  
定向测试日志 SHA-256：`a94f870e63ce63f14c613b465eb4dc9dd18a852eaac44fde3f0db93d8fc97acf`

## 裁决

**R5 可通过本次架构复审，没有发现生产路径阻断项。** Manifest、patch、测试日志及 CP3 slice hashes 与指定值相符；`PlannerApplicationService` 和 `PlanAdjustmentService` 的哈希与 R4 一致。R5 是测试证据增强，未改生产代码或 schema。

- 新的 response-loss 测试用 `ContentCachingResponseWrapper` 包住 servlet response，在真实 Controller/Application 处理完 apply 后清掉 buffered body 并抛出指定 `IOException`。断言 cause chain 包含 `IOException`、one-shot drop 标志被消耗，并用 SQLite revision 和 revision history 证明第一次 Apply 已完成。相同 idempotency key 的后续请求回放成功 receipt，且没有第二次写入。
- proposal removal failure 测试扩展为复合两步动作：首个 Apply 的 remove 被替身注入异常后，receipt 含剩余任务 continuation；同 key 回放一致；以新 key 重试旧 proposal 被拒；随后通过 pending intent 预览并应用剩余动作，版本 1→2→3。此项证明失败后剩余队列仍可续接，不是只验证一个成功 receipt。
- `commitPreparedApply` 真实 SQLite CAS 返回后再注入异常的边界仍是：首个调用异常、同 key retry 返回 conflict、revision/write count 不再增长。它满足“不得重复写”的约束，但不保证从尚未登记 replay 的窗口恢复首次成功 receipt。测试与实现报告没有把它包装成 durable idempotency。

以上均是单 JVM/SQLite/servlet filter 或 mock port 故障注入。不能据此声称已验证实际 TCP 断线、容器/进程崩溃、Redis、PostgreSQL、跨节点 replay 或持久化幂等账本。

## 冻结身份核验

- 指定 manifest、tracked patch、测试日志 SHA-256 均匹配。
- manifest 中 CP3 slice hashes 匹配：`PlannerPreviewUseCaseTest.java` `96fbeb4d...565ed94`、`PlannerApplicationService.java` `3d14667d...912e380`、`PlanAdjustmentService.java` `186110d8...f4f9bb6`、`CP3_IMPLEMENTATION_REPORT.md` `54bcba7b...df2716`。
- Application/domain 生产文件哈希与 R4 相同；tracked patch 也相同。R5 manifest 明确称其为 test-only scope，源码核验支持这一点。
- Manifest 记录 isolated clean suite 为 146 tests、0 failures/errors/skipped；本审查未重跑。R5 的验证环境是 SQLite、单 JVM，没有真实 Redis/PostgreSQL、socket loss、process crash 或 multi-process。
- 工作区还包含 CP0–CP2、用户既有差异及此前独立审查报告；本次未还原或清理任何内容，只新增本报告。

## R5 故障场景核验

### 1. Controller 完成后丢弃 HTTP body

`ai/src/test/java/com/ai/guide/application/planner/PlannerPreviewUseCaseTest.java:211-265`：

- Filter 创建 `ContentCachingResponseWrapper`，用它调用 `chain.doFilter`；当该次请求带 drop header 且 one-shot flag 首次成功时，对 wrapper 调用 `resetBuffer()` 并抛 `IOException`，正常返回时才 `copyBodyToResponse()`（`:230-239`）。Filter 位于 Controller 外层，因此注入异常发生在 Controller/Application handler 返回之后。
- `MockMvc.perform` 的异常由 `assertThrows` 接住；`hasCause(..., IOException.class)` 验证异常链包含 filter 指定异常，`dropOnce` 变为 false 验证注入分支确实执行（`:244-248`）。
- 存储 revision 增加 1 且历史 revision count 增加 1（`:250-252`），与 Controller 已完成首个 Apply 相互印证；同一 payload/key 重试两次都 HTTP 200、response body 相同，最终 revision 和写入数仍只增加一次（`:254-264`）。
- **限制**：wrapper 清除的是 MockMvc servlet response buffer，随后 filter 抛异常；这没有建立真实 TCP 连接，也不能模拟客户端在任意网络字节位置断线、服务器进程被杀或反向代理行为。它证明 servlet/controller 边界在响应未交付时的同进程 idempotency replay。

### 2. CAS 返回后抛异常

`PlannerPreviewUseCaseTest.java:267-302` 仍使用 spy 对 `commitPreparedApply` 执行真实方法后再抛 `IllegalStateException`（`:285-288`）。断言 SQLite 版本和历史记录已加 1（`:290-294`）；同 key retry 返回 `CONFLICT`，写入数不再增加，CAS method 只被调用一次（`:296-301`）。

这模拟的是“CAS 成功从 repository 返回后、application 尚未完成生命周期及 replay 写入时抛异常”，不是底层数据库对 commit 成败未知的网络故障。可证明安全地 fail closed、不重复写；不能证明成功回执恢复。R5 没有对此做过度承诺。

### 3. Proposal remove failure 与复合剩余队列

`PlannerPreviewUseCaseTest.java:304-377`：

- 初始请求由删除 day 1 景点和加入 day 2 新点组成，第一次 preview 返回 clarification/pending intent；用户选中第一项后生成 proposal（`:325-338`）。测试挑选的替换 POI 不在已有行程名称集合内（`:311-324`）。
- apply 后通过 proposal port spy 让 `remove(proposalId)` 抛错；Apply 仍成功，同 key retry body 相等，receipt 中存在带 intentId 的 pending continuation，revision/history 只增加一次（`:340-355`）。
- 同一个旧 proposal 配新幂等 key 被拒绝且无新增写入（`:357-362`）；使用 receipt 中 continuation ID 发起“执行下一个”后得到新 proposal 并成功应用，revision/history 再增加一次（`:364-377`）。因此覆盖了 cleanup 失败后 continuation 保留、剩余任务后续可操作和旧 proposal 不可复用。
- **限制**：proposal 删除失败通过 `proposalWriter` mock/spy 注入；session/trip 使用 SQLite，但 proposal port 的真实 Caffeine/Redis 错误路径未运行。不能从这个测试推出 Redis L1/L2 清理、真实网络超时或多副本一致性保证。

## 生命周期架构及 §3.4 边界

- Application 的 Preview/Cancel/Reset admission 和 Apply 步骤顺序仍在 `ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java:115-209`；Apply 先 replay lookup 和规则准备，再 CAS，之后 completion、receipt 及 replay。
- `PlanAdjustmentService.java:85-93` 保有唯一 128-stripe lock、continuation/epoch/generation 与 bounded in-memory replay authority；Preview ticket 与 Cancel/Reset 仍共享原 lock/generation admission，legacy Preview 也复用同一 authority。R5 没有生产代码变化，不改变已审 R3/R4 的竞争边界结论。
- 领域先校验 revision、proposal session/TTL/epoch、option 和硬约束，再调用 repository CAS（`PlanAdjustmentService.java:853-939`）；成功后处理 proposal remove、continuation/剩余任务和 completion（`:945-964`）；application 随后组装成功 receipt 并写 replay（`PlannerApplicationService.java:199-208`）。remove failure 被捕获后不会将已提交行程误报为未提交，但数据库和缓存/内存步骤不是一个事务。
- §3.4 要求明确响应丢失、cache failure 与 post-commit failure 的结果，并要求不重复写；R5 证据使三种结果具体可观察：响应被 filter 丢弃但进程仍活着时同 key 返回原 receipt；缓存端口删除异常时成功 receipt 与剩余项仍可续接；CAS 后调用异常时不双写但尚无成功 receipt、retry conflict。按计划所述单实例边界，这些承诺没有超出实现能力。

## 阻断 / 非阻断矩阵

| 项目 | 结论 | 等级 |
|---|---|---|
| 冻结身份 | manifest/patch/log/slice hashes 均匹配；生产文件无 R5 变化 | **通过** |
| response filter 实际丢弃与异常断言 | wrapper 在 Controller 后 buffer；断言 cause、one-shot flag、revision 和重试回放 | **模拟验证通过；不是 socket 测试** |
| remove failure 后复合剩余任务 | pending continuation 存在、旧 proposal 被拒、剩余动作后续成功应用 | **模拟缓存端口 + SQLite 验证通过** |
| commit-then-throw | 真实 SQLite CAS 后注入异常；不会二次写，但不提供原 receipt | **安全边界通过；恢复能力有限且已披露** |
| Application workflow/锁/epoch/replay | Application 排序；一个共享状态 authority，不创建分裂状态 | **通过（单实例）** |
|真实 Redis、进程崩溃、多进程、DB commit ambiguity | 本候选未覆盖 | **未验证，明确限制** |
| 146 项 Maven suite | Manifest 记录全绿，本审查未执行 | **未由本审查独立验证** |

## 最终意见

CP3 R5 可通过复审。新增断言纠正并加强了两个证据薄弱处：响应丢失现在由响应 wrapper 明确丢弃并检查异常链/one-shot 状态/持久化 revision；proposal remove failure 现在验证复合任务剩余队列真实续接及旧提案拒绝。其故障注入模型边界在代码和实现报告中均有披露，没有发现生产层面过度声称。

本候选仍没有实现跨进程持久幂等 receipt。CAS 后、Application replay 写入前故障时，重试会 fail closed 而非恢复原 receipt；这符合当前单实例恢复边界，但若后续业务要求重启或节点切换后仍返回相同成功回执，必须单独设计持久 operation receipt/idempotency store 与事务/过期规则。

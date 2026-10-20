# CP3 R6 红方复审

日期：2026-10-09  
范围：只读复审 R6 冻结候选中复合动作的持久快照断言，并判断 R5 的内容验证缺口是否闭环；未运行测试，未修改代码。  
Manifest SHA-256：`08ed2f220fa1f23166f0cd1e1fce63f51318f6769c137fec651b4e80eff0c323`（匹配）  
Tracked patch SHA-256：`96f04a05e1dead4e9962aa405fbb12b76ff678de1fcd1ae4ba3879a3627dc3c1`（匹配）  
Final clean log SHA-256：`883ecd1c5962980224838ad21763877625ad6dfefa5b854b902d6b3ebb70442c`（匹配；仅读取）

## 裁决

**R5 关于“缺少持久行程内容断言”的主要 PARTIAL 已实质缓解；严格验证复合动作分阶段语义仍为 PARTIAL。**

R6 新增断言读的是 `PlannerSessionRepositoryPort` 返回的已存 session trip，而不是 preview/apply response 中的计划副本。它证明第一项提交后 day 1 的指定站点确已删除；第二项提交后 day 2 确实包含原始行程中不存在的 `nextPlace`，且 day 1 删除仍然保留。revisionCount 分两步各增加一次。这些断言是有意义的持久状态证据，R5 的核心“只看 revision，不看实际计划内容”缺口已补上。

但测试没有在第一项提交后断言 day 2 尚不含 `nextPlace`。因此在逻辑上仍允许第一项意外提前加入该地点、第二项另做了其他变化，而最终状态和版本断言仍通过。它也没有检查返回 pending intent 的 intent payload 确实描述第二项。故对于“第二项是在第二次应用时才发生”的严格因果归属，缺一个中间态断言；建议第一项后加 `assertFalse(storedStopNamesForDay(storedAfterFirstApply.trip(), 2).contains(nextPlace))`，并可进一步断言 pending intent 的第二项内容。

## 快照断言逐条复核

测试位置：`ai/src/test/java/com/ai/guide/application/planner/PlannerPreviewUseCaseTest.java:304-385`，方法 `proposalRemovalFailureStillReturnsAndReplaysCommittedReceipt`。

1. **目标删除断言有基线**：`targetStopName` 从提交前的 day 1 `stops[1]` 读取（311-315 行），所以目标在原始持久快照中存在。首个 apply 后重新读取 `sessions.findAnonymousByAccessToken(token)`，检查 day 1 不再含该名字（355-357 行）。这证明删除落入 session repository 持久快照，而非只存在于 apply 回执。
2. **新增地点选择确保初始缺席**：`nextPlace` 从 attraction 池中选择，并排除提交前全行程的 `plannedNames`（316-324 行）。因而其在最初持久快照中没有出现。
3. **第二次 apply 后检查目标 day**：第二个 preview 使用 pendingIntentId 与“执行下一个”，再把该 preview proposal apply（367-377 行）。从数据库重新读取快照后，断言版本和 revisionCount 比初始值再多一次；day 2 含 `nextPlace`，day 1 仍不含删除目标（378-384 行）。这强于仅断言 SUCCESS 或返回 proposal。
4. **尚缺中间快照差异**：首个 apply 后虽读取了 `storedAfterFirstApply`，只断言 day 1 删除；未检查 day 2 的 `nextPlace` 仍不存在。现有 final-state 断言能证明地点最终存在于 day 2，不能独立证明首次提交未提前写入它。
5. **pending intent 断言粒度**：首个成功回执只校验 `pendingIntent` 与 `intentId` 非空（350-352 行）；下一预览使用该 ID 且成功，但没检查 `remaining.intent` 或第二项目标字段是否仍是原请求的 `nextPlace`。最后的地点状态提供间接支持，但加 payload 断言能避免错误 continuation 偶然通过。

## R5 其他故障场景承接

R6 manifest 声明是测试-only 变更，tracked patch 与 R5 相同。R5 的 response wrapper、CAS 后抛异常与同 key/no-double-write 测试未被 R6 修改；沿用 R5 审查的结论：

- Servlet response 通过 `ContentCachingResponseWrapper` 缓存，丢弃分支清缓存并抛指定 `IOException`，没有 `copyBodyToResponse()`；用例检查异常 cause、drop flag、SQLite 写入先完成、同 key 重试 200 且 receipt 相同、revision 仅增一次。它模拟的是 servlet 层，不是真实 socket 断连。
- CAS 后异常测试在真实 SQLite 更新后注入异常，重试冲突且不二次写；它不保证异常窗口可恢复原 receipt。
- proposal remove 故障经 fault-injected port 而非真实 Redis；R6 的新快照断言加强了其复合 continuation 持久行为，但没有消除 Redis/多进程/重启验证限制。

## 测试日志与结论边界

- R6 manifest/patch/final log 及 CP3 相关源/测试/报告切片哈希匹配冻结身份。
- Manifest 与已有日志记录隔离副本 **146 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**。本审查未重跑测试。
- SQLite 单 JVM 用例验证了两个已提交阶段后的数据库快照；没有 live Redis、PostgreSQL、多进程或重启后的幂等验证。日志中的 Qdrant localhost 不可用且走降级路径，不影响本次快照断言裁决。

**最终意见：**R6 补上了 R5 缺少实际持久 trip 内容核验的问题，证明首项删除已写入及最终第二地点已写入；因此 R5 的主要 PARTIAL 可视为解决。若验收标准要求证明“第二地点只在第二步提交后出现”，本候选仍应保持 PARTIAL，补上首步后 day 2 缺席断言及 pending intent 内容检查即可形成直接闭环。未发现生产代码缺陷；本轮只审查了测试证据。

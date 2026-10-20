# CP4.4 BFF 路由契约子切片验收

日期：2026-10-09  
状态：**子切片已验收；CP4.4 与 CP4 整体仍在实施**

## 验收范围

本次只验收 BFF Open 的 Java session 身份契约，以及 Restore 返回值对共享 BFF session 的迟到覆盖保护。没有据此宣称 Save/Open/Restore 的浏览器端到端生命周期已完成。

最终候选：BFF 仓库 `C:\Users\summer\Documents\Codex\2026-08-12\loop-p0-functional-inventory-1-home` 中 `docs/refactor/CP4_4_R8_CANDIDATE.json`，SHA256 `fe89d53190b2728cc5fcad6b8813913aaa4f02c56da25722fa97a9de56f9c527`。完整 tracked diff SHA256 `982430a7332bc5116f588e35d74233305f41e776acdc6e070ee30a3516b7e4b3`。候选清单列出的文件、路由 patch、测试 patch 与日志哈希均已由主智能体核对。

## 行为结果

- Open 只接受非空字符串 Java `sessionId`。对象、数组、数字和空值在再次读取 Trip、创建 BFF session 或写入 workspace/draft 前以契约错误结束。
- Restore 在 Java 调用前捕获 BFF `syncRevision`、`trip.version` 和完整 trip JSON。等待期间只要任何计数或快照内容变化，迟到回执返回 409，不替换或持久化新状态。
- Java Restore 回执的 `syncRevision` 与 `trip.version` 必须是正安全整数，并且都严格推进请求开始时的基线。字段缺失、类型错误、未推进或低于当前状态时不会写回 BFF session。
- 回归覆盖 Save 元数据只更新 `sourceTripVersion`、但两个版本计数不变的情况。

## 验证与审查

主智能体在候选冻结后独立复跑：

```text
node --test server/formal-trip-bff-contract.test.mjs server/draft-restore-route.test.mjs server/planner-bridge-contract.test.mjs server/trip-delete-version.test.mjs
```

结果：**26 项通过，0 失败、0 跳过**。`node --check` 对 `server/index.mjs` 和两份相关测试通过，`git diff --check` 通过。Git 对既有 `start-local.ps1` 的 LF/CRLF 提示是工作树行尾提示，不是空白错误。

B 架构复审与 C 红队复审均对 R8 候选给出 **PASS（限本子切片）**，并核对冻结哈希。两位审查员没有运行测试；主智能体完成测试与哈希复核。

## 限制与后续

路由测试通过 VM 提取当前源码并使用受控 Java/session/persistence 替身。没有真实 BFF、Java、数据库、浏览器、多 BFF 实例或真实并发 Apply/Save 端点验证。因此这不是 CP4.4 退出条件的完整证明。

仍需处理/验证：Open 的两次 Java 调用不是原子操作；Java Open 后 Trip 读取失败可能留下孤儿 Java session；BFF session 创建后 workspace/draft 持久化失败尚无完整回滚；Open 两次读取间存在正式行程快照竞态。接下来继续 Open 生命周期补偿与快照一致性，再做前端账号/工作区 stale-response 和真实交互闭环；在完成 Save→Open→局部调整→Save→重载前，CP4 维持实施中。

本次没有 stage 或 commit。BFF 仓库中的既有 dirty 用户文件保留。

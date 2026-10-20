# CP4.4 Frontend stale-Open R4 与浏览器生命周期补充验收

日期：2026-10-10  
结果：**R4 限范围通过；合成浏览器生命周期通过；不代表 CP4.4 整体完成**

## 候选身份与复核

R4 冻结资产位于 BFF 工作区 `docs/refactor/CP4_4_FE_STALE_R4_CANDIDATE.json`，其清单 SHA-256 为 `793BDBEB0862C4EB4B7CAA48FF9F0EC9BC6A9A323EC5381A87122D3F7DFA5099`；R4 报告 SHA-256 为 `378E385CCCD91A5FD6752D6BC766B10DEF3244DCB43F36360659D7A0503705EE`；增量补丁 SHA-256 为 `3F152916CA66388C0441C06DAA687F03A3F483445CB0C4485104054876927AF3`。独立测试审查确认这些冻结身份与当前候选一致，补丁可应用，工作树源文件与快照一致。

两位独立审查者均给出“范围内通过、整体部分通过”的结论：R4 scoped tests、身份和队列 revision/epoch 门禁通过，未发现阻断缺陷；完整 CP4 和浏览器生命周期仍未验收。审查指出的限制是网络请求永不返回时 Open 仍会 pending，以及冲突保留的旧本地会话没有专门的恢复入口。已派发前的 79/81 组合测试结果不能作为基线结论；主智能体确认失败由 VM fixture 漏载 `formalTripWorkspaceWrite` 引起，补齐 fixture 后重新运行 81/81 全绿。

## 行为与验证

R4 的窄范围行为包括：Open 返回期间离开原视图会释放旧操作的 UI 所有权；旧响应不能覆盖新 workspace；本地恢复聊天在同一 epoch 下按队列头已确认 revision 重基；epoch 改变或 workspace 被阻断时，在写入或提交旧 UI 状态前失败关闭。实现和详细限制见 BFF 工作区的 `CP4_4_FE_STALE_R4_REPORT.md`。

主智能体本轮独立复跑：

| 范围 | 命令 | 结果 |
|---|---|---:|
| BFF + scripts | `node --test (Get-ChildItem server/*.test.mjs,scripts/*.test.mjs \| ForEach-Object { $_.FullName })` | 81 passed, 0 failed |
| 前端特性测试 | `node --test (Get-ChildItem frontend/web/src -Recurse -Filter *.test.mjs \| ForEach-Object { $_.FullName })` | 61 passed, 0 failed |
| 前端生产构建 | `npm run build`（frontend） | Vite 成功构建，61 modules transformed |
| Java Save/Open/Restore application | `./mvnw.cmd test -Dtest=PlannerSaveApplicationServiceTest,PlannerOpenApplicationServiceTest,PlannerRestoreApplicationServiceTest` | 19 passed, 0 failed, 0 skipped; BUILD SUCCESS |
| 登录态浏览器生命周期 | 合成账号与内存 fixture：登录 → 规划 → 保存 → 打开 → 删除站点 → 保存 → 刷新 → 重开 | 通过；重开显示正式行程版本 2，站点仅剩洪崖洞 |

测试夹具修正见 BFF 工作区 `scripts/workspace-lifecycle.test.mjs`：VM harness 现在把 `formalTripWorkspaceWrite` 一并装入上下文。这两项此前失败的用例随后实际执行了 BFF workspace PUT 和 reset/epoch 交错断言并通过。

## 未验收项

- 上述浏览器流程使用合成账号和内存 fixture，没有连接真实 Java HTTP 服务、PostgreSQL/Redis 或真实用户账号；所以它验证了实际前端交互、BFF HTTP 路由与工作区生命周期，不构成 Java↔BFF 生产装配的端到端证明。
- 本轮检查时本机 8080/3000/5173 均无监听进程，因此没有可安全复用的真实 Java+BFF 联调服务；不启动连接用户持久化数据的服务来伪造隔离验收。
- 本报告初验时 Java Open 后没有远端 session 补偿 API；后续 R10 已补入 owner/trip/version/revision 受限的补偿删除并通过隔离契约测试。补偿仍不是分布式事务：Open 回执丢失导致 BFF 不知道 session ID、或 DELETE 不可达时，孤儿仍可能存在；详见 `CP4_4_R10_OPEN_COMPENSATION_ACCEPTANCE_REPORT.md`。
- 本报告不覆盖真实 PostgreSQL/Redis、多实例、网络断连及进程崩溃；不进入 CP5。

## 浏览器验收补充（2026-10-10）

使用隔离的内存 fixture 和合成 ADMIN 账号完成真实浏览器交互，不读写真实用户数据。完整路径为：登录、生成含“解放碑步行街/洪崖洞”的合成行程、保存为正式行程、重新打开、移除解放碑、保存正式版本 2、刷新页面、从“我的行程”再次打开。最后一次打开与列表均显示版本 2 且只有洪崖洞，确认界面快照、保存版本和刷新恢复一致。

该流程暴露并修复一处登录后的前端重绘缺陷：认证请求 `finally` 将 `state.loading` 置为 `false` 后只重绘 loading overlay，没有重绘规划页，导致“开始 AI 智能规划”仍保持禁用态。嵌套 Frontend Git 仓库的 `web/app.js` 现在在离开 auth 视图时补做 `renderView()`；HMR 后浏览器按钮恢复可用，随后完整生命周期通过。此修复尚未纳入 R4 冻结候选 hash；该仓库已有其他未提交改动，本轮只补这一处重绘，不整理或覆盖其余改动。

本次补充复跑：`npm run build` 成功（61 modules）；前端测试 61/61；BFF + scripts 测试 81/81。Java Save/Open/Restore 19 项结果见上方表格，为定向 application 测试，并非本次浏览器调用的在线 Java 服务。

## 当前裁决

只接纳前端 stale-Open R4 与相关测试夹具修复在上述边界内的结果；合成浏览器生命周期也已通过。登录重绘修复需作为后续候选单独纳入审查。R10 已实现远端 session 补偿，但本报告的浏览器流程仍使用合成 fixture；CP4 仍为实施中，尚需在隔离数据源下完成真实 Java↔BFF 服务集成验证，并明确补偿无法覆盖的回执丢失/网络不可达边界，之后才能按计划进入下一检查点。

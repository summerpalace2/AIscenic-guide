# CP4.1 V1 Planner Save 用例验收报告

日期：2026-10-09  
裁决：**CP4.1 已验收（Java V1 Planner Save 切片）**。CP4 整体仍实施中；Open/Restore、BFF/前端契约和真实 UI 流程尚未完成。

## 最终冻结候选

- 候选清单：[CP4_1_CANDIDATE.json](CP4_1_CANDIDATE.json)，SHA-256 `87a48be29dc06438e670dfae9a4bba11aa63489fbc880ec458ac315a0bb09d00`
- 实际 Manifest SHA-256：`87a48be29dc06438e670dfae9a4bba11aa63489fbc880ec458ac315a0bb09d00`
- Tracked patch：[CP4_1_TRACKED_PATCH.patch](CP4_1_TRACKED_PATCH.patch)，SHA-256 `27ea7183a4d65edd81344920edf92a4a1c7c018b69d8e3068e0f6cd739efb4fd`
- 测试日志：[CP4_1_TEST.log](CP4_1_TEST.log)，SHA-256 `5559d1103c4c1e110657c41ba422e9f7a60afa5eca6d3d37820084254a310017`
- 实施报告：[CP4_1_IMPLEMENTATION_REPORT.md](CP4_1_IMPLEMENTATION_REPORT.md)
- 架构复审：[CP4_1_ARCHITECTURE_REVIEW.md](CP4_1_ARCHITECTURE_REVIEW.md)
- 红队复审：[CP4_1_REDTEAM_REVIEW.md](CP4_1_REDTEAM_REVIEW.md)

## 变更与行为证据

`PlannerSaveApplicationService` 承担 V1 保存编排：根据显式 actor 授权会话、只读取服务器保存的 session snapshot、按 session+revision 生成幂等键、调用正式 Trip 保存并把稳定 formal ID/version 回链到 session。旧 `PlannerService.save(sessionId, token)` 保留为兼容入口，通过 domain gateway port 委派；Controller 使用显式 actor 入口并维持原状态码与响应 body。application coordinator 不依赖 `PlannerService`，Spring 依赖图无环。

保留的不变量：匿名用户不能保存；用户 session 所有权或 capability claim 检查；不接受 V1 请求中的客户端 trip 作为权威保存内容；幂等重放；正式 Trip ID 稳定；业务快照无变化时不增加版本；正式版本 CAS/rebase；并发编辑时只回链 formal ID/version 元数据，不覆盖更新的草稿主体；保持 controller status/body。

主智能体在共享 Java 工作区独立执行 manifest 的 7 个类：**55 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS**。实施者另在隔离副本执行相同 `clean test` 命令，也得到 55 项全绿。独立架构审查与红队审查均判定当前切片无阻断项。

## 已知非阻断限制

- `PlannerSaveResult.serviceStatus` 仍是整数状态，并由 application/controller 传递；它沿用现有 `ServiceResult`/HTTP 兼容路径，后续可单独将状态映射收敛为 application outcome + adapter 映射。
- 同 capability 错误输入的零写断言、formal ID 链接三次失败场景、真实双仓储并发注入尚未覆盖；当前有授权/服务端快照/幂等/no-op/并发草稿保护等已有与新增证据。
- 连接瞬断重试依赖已有 session+revision 幂等键；本切片未专门故障注入该异常路径。
- 未运行 BFF、前端或浏览器流程；未验证真实 PostgreSQL、Redis、Qdrant。Qdrant 不可用时测试按配置进入降级模式。

## 范围与回切

本切片未改 BFF、前端、Open/Restore、数据库 schema。候选 patch 包含重叠文件上的既有 CP0–CP3 差异，回切时必须按 CP4.1 专属 diff 选择性撤销；保留此前 dirty baseline，不使用 `git reset` 或 `git clean`。

下一步继续 CP4：先处理 Open/Restore 的服务端快照一致性，再覆盖 BFF 路由和前端陈旧响应防护，最后按计划补充保存→打开→调整→保存→重载的真实交互证据。

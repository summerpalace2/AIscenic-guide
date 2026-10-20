# CP4.3 Planner Restore 用例验收

日期：2026-10-09  
裁决：**通过，限定于 Java Planner V1 Restore 应用用例迁移**。CP4 整体仍在实施中。

## 冻结身份与独立复核

- 最终 R2 清单 SHA-256：`72ff4abcb8724ad789c3d86897974d786dbac02b01aeaf4f9c75283c3cfcc959`
- tracked patch SHA-256：`04da05079482732a9442494ea9af910d95ebab4a4810640a0c3ff475e2e156d2`
- 完整 tracked dirty diff SHA-256：`9b55fb55dff29d86dc371c9bee66a1353b74572424fe352d229c150bd57bf0ac`
- 实现报告：[CP4_3_R2_IMPLEMENTATION_REPORT.md](CP4_3_R2_IMPLEMENTATION_REPORT.md)
- 隔离测试日志：[CP4_3_R2_TEST.log](CP4_3_R2_TEST.log)
- 架构复核：[CP4_3_R2_ARCHITECTURE_REVIEW.md](CP4_3_R2_ARCHITECTURE_REVIEW.md)，通过；红队复核：[CP4_3_R2_REDTEAM_REVIEW.md](CP4_3_R2_REDTEAM_REVIEW.md)，通过。
- 主智能体核对候选、补丁、报告、日志及清单中 12 个代码/测试文件 hash，全部匹配。

## 验收结果

实施者在新隔离副本 `clean test` 运行 9 个定向测试类，**74 项通过、0 失败、0 错误、0 跳过**。主智能体在共享工作区不执行 `clean`，使用独立 SQLite 临时库复跑相同 9 类，结果也是 **74 项通过、0 失败、0 错误、0 跳过，BUILD SUCCESS**。启动时尝试连接不可用的本机 Qdrant 端口并按已有逻辑降级；不把该日志当作 Qdrant 集成验证。

Restore 编排已从 `PlannerService` 迁到 `PlannerRestoreApplicationService`。Controller 显式传入服务端 actor 并保留原 status/body 与 header capability fallback；旧 `PlannerService.restoreDraft` 签名保留为兼容委托。formal Trip ID 来自已授权 session，formal Trip 按 owner 查询；浏览器备份的身份与 planner version 不作为身份权威，成功时被服务端值覆盖。expected planner revision、formal Trip source version、共享 `TripPlanLimits` 配置、备份验证、session CAS 及冲突响应均有对应回归测试和源码审查证据。

R1 红队发现并已在 R2 处理的事项：Restore 不再自行 `new TripPlanLimits`，改为注入 Spring 管理 bean，使自定义限制能应用于恢复；USER session 测试改按 owner 身份授权，不再把 token 错当必需条件；增加错误 guest capability 拒绝与 session CAS 冲突测试，断言冲突返回最新 session/trip 且不会重试写入。另有首次隔离运行的测试夹具/断言错误，已保留诊断日志；修正后从新副本 clean 重跑全绿。

## 仍然明确的限制

- formal Trip 读取与 PlannerSession CAS 更新不是一个跨聚合事务。Trip 可能在读取后并发推进；Restore 本身只写 planner session，不覆盖正式行程，后续正式保存会以过期来源版本拒绝。该交错没有 barrier 测试，不宣称跨聚合原子一致。
- 合法 guest capability 被登录用户用于认领 session 时，owner claim 会先于后续备份 freshness/validation 检查。这是既有授权流程，失败时 planner 内容/revision 未更新，但 session owner 可能已变更。
- 最大天数测试通过构造注入的 `TripPlanLimits` 对象设置低上限，验证 application 使用注入策略；没有单独启动 Spring 并以 `trip.plan.max-days` 覆盖属性做端到端绑定测试。bean 的 `@Value` 配置绑定仍是未独立验证项。
- 未验证真实 BFF、浏览器、HTTP filter、PostgreSQL/Redis、多进程及保存→打开→恢复→编辑→保存完整 UI 生命周期；这些仍属于 CP4 后续切片。

## 回切与后续

CP4.3 未提交。回切时仅撤销 Restore 用例新增/装配与测试，不回退 CP4.1、CP4.2 或用户既有改动。下一步继续 CP4 的 BFF 路由/身份契约，再验证前端本地备份与账号/工作区代次护栏，最后完成真实交互的保存→打开→局部调整→保存→重载链路；CP4 未完成前不进入 CP5。

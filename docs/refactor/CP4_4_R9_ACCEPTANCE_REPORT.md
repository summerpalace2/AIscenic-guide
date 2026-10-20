# CP4.4 R9 验收报告：原子化 Open 与工作区队列重基线

验收日期：2026-10-10
候选：`CP4_4_R9_CANDIDATE.json`，SHA-256 `cc45ccfa8c7f0d461290e08f42810da37093a375a91dc41e7bfe5ca5acdf44d4`

## 裁决

**R9 子切片已验收；CP4.4 整体与 CP4 仍在实施。** 本切片修复了 Open 过程的本地半提交窗口、同步 getter 暴露未提交 Map 值，以及 Open 在持久化队列等待期间用旧 workspace 覆盖较新对话状态的问题。

## 实施与行为

`savePlannerOpenState(session)` 在同一串行写队列中读取最新 workspace，准备 draft 与 workspace 两份候选状态，并以单个原子 JSON 写入提交。rename 成功后才同步更新已提交的 Map。Open 路由在持久化结果返回后从该 workspace 水合 session，再将其注册到活动 session Map。

USER Open 不要求独立 session capability；调用依赖 BFF 已认证用户凭证和 Java owner 校验。R5 相关修正确认见 CP4.4 前序候选与复审记录。

## 验证

主智能体在共享 BFF 工作区独立重跑定向套件：**34 项通过，0 失败、0 跳过**。Node 语法检查和 `git diff --check` 通过。实施者与架构审查、红队分别检查冻结候选；架构审查 PASS；红队结论为 PARTIAL，原因是未单独覆盖两个 Open 同时排队及真实服务端到端。主智能体复核后认定这些是非阻断验证缺口：身份关联由单一服务端 session 派生，且本子切片已用可控队列交错验证关键写入顺序，因此仅接纳 R9 限范围子切片，并将缺口保留为明确限制。复审详情见 `CP4_4_R9_ARCHITECTURE_REVIEW.md` 与 `CP4_4_R9_REDTEAM_REVIEW.md`。

测试包括：rename 挂起期间 getter 只读已提交值；写入失败旧 draft/workspace 保持；并发 session GET 在 Open 原子提交前返回 404；先排队的 workspace PUT 提交后 Open 采用最新 epoch/context/messages/mode，同时保留新 itinerary snapshot；真实临时目录 rename 故障与后续写恢复。

## 限制与后续

- 未运行真实 BFF/Java 服务链路；route 并发用受控 harness，持久层用真实临时文件系统测试。
- 未单独测试两个 Open 同时排队；owner mismatch 由同一 session 派生的 owner/formal ID 校验结构性约束。
- `tripWorkspaces` Map 仍公开，但仓库当前无直接运行时写入者。
- Java Open 先于 BFF 本地持久化创建远端 session。BFF 本地持久化失败时可能留下 Java orphan；目前没有 owner-scoped session delete/compensation API。
- 前端迟到响应防护和保存→打开→局部修改→保存→重载的真实交互仍未验收。因此 CP4 未完成，不进入 CP5。

未 stage 或 commit；未触碰无关 dirty files。


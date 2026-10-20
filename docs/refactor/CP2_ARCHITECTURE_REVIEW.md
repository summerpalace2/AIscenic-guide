# CP2 独立架构审查：Preview / Clarification 用例边界

日期：2026-10-09  
审查类型：只读源码复审；未运行 Maven，未编辑业务源码。  
冻结清单：`C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP2-candidate.json`  
冻结清单 SHA-256：`a427e5c1c3cf14ed5d002f89c164746ef1ccdec679af8fb673ff5b70ba051ffa`  
清单内 tracked patch SHA-256：`5bfe8d15042827e6b67264e07008fe61dfb53ca37a3bd8e3b0b49a72886484e4`

## 裁决

**冻结身份匹配，CP2 的 Preview 路径已有真实的 application-layer 编排：** application service 读取可信 actor、解析匿名 capability/用户所有权、检查请求 revision，并把已授权的 `StoredSession` 和 proposal 写入 capability 传给领域预览策略。domain/application 依赖方向合理：application 依赖领域服务和领域 port，领域层没有反向依赖 application。

**但 Clarification 并未成为 application 层单独编排的用例。** `converse()` 只是转调同一个 `previewAdjustment()`；追问解析、pending intent 延续、澄清判定/构造仍在 `PlanAdjustmentService.previewAuthorized()` 中。application 只根据领域返回的 `requiresClarification` 字段把结果标签分类为 `CLARIFICATION`。所以这是“application 控制入口、身份/revision 与存储能力；domain 保留完整预览/澄清策略”的有限切片，不是把 Preview/Clarification 状态机迁到了 application。

新 application result 不携带数值 HTTP 状态，但边界并未完全去除 HTTP 耦合：`PlanAdjustmentService.previewForApplication()` 先拿到旧 `PlannerService.ServiceResult`，再按 200/400/401/… 转换为 typed outcome；两个 outcome enum 也包含 `BAD_REQUEST`、`NOT_FOUND`、`TOO_MANY_REQUESTS` 等 HTTP 语义名称。Controller 已负责把 outcome 映射为 HTTP，属于部分解耦，而非纯业务结果协议。

授权路径使用 `PlanningActor` 与 session capability 核实 session identity。revision 比较存在正常的读后并发窗口：授权查询和 preview 执行之间，另一个请求可能更新 session；此时会生成基于已读取旧 snapshot 的 proposal。后续 apply 仍应以当前 revision 拒绝陈旧 proposal，因此未见绕过提交版本检查的证据；影响是用户可能短暂看到过时预览。该窗口在本次代码中没有单独用并发测试覆盖。

Controller 保持两个既有 POST 路径、header token fallback 和相同 response body map；HTTP 映射下，clarification/no-candidate/non-applicable 仍为 200，且 `canApply:false` 保持阻止应用。apply controller 仍走原来的 `ServiceResult` 路径，apply 方法实现未进入新的 typed preview 用例。取消文本仍由领域 preview 中的 reset 分支处理，CP2 没有新增 application cancel 用例。

**建议裁决：CP2 作为有限 Preview 用例切片通过；若目标要求 application 层编排 Clarification 状态机或完全移除 HTTP 语义，则尚未达成。** 父任务提供的 95 tests / 0 failures 记录及冻结文件哈希已由候选清单记录；本次没有复跑。

## 冻结一致性

- CP2 清单 SHA 与指定值一致。
- 清单内 11 个 CP2 slice 文件哈希与当前源码一致；tracked patch artifact SHA 与清单一致。
- 清单声明有大量既存 CP0/CP1 工作区变更；本审查未覆盖、还原或清理这些内容。

## 逐项证据

### 1. Application 编排、Preview 与 Clarification

- `ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java:113-139`：`previewAdjustment` 检查 feature flag，调用 `findAuthorized`，检查 `baseRevision`，之后调用 `planAdjustmentService.previewForApplication(session, request, proposalRepository::put)` 并组装 `PreviewAdjustmentResult`。这是实际的 application orchestration，不是只把参数传给旧 `PlannerService.previewAdjustment`。
- `:91-96`：`converse(actor, ...)` 直接委托 `previewAdjustment(actor, ...)`，两条入口共享同一个用例，没有独立的 conversation/clarification application state machine。
- `ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java:263-406`：领域服务仍执行 continuation begin、待处理 proposal 复用、intent 提取/短答承接、缺少目标时澄清、取消 reset 分支及 proposal 生成。澄清判定和内容生成仍由领域策略负责。
- `:409-437`：领域结果被归类为 `CLARIFICATION` 的依据是 response map 中的 `requiresClarification`；无候选与不可应用也以 map 字段推断。application 对这些情况接收既成结果并作标签组装，并未编排澄清状态迁移。

**判断：** 用例入口授权、版本门禁与持久化能力已进入 application；Preview/Clarification 业务状态机仍在 domain。是否算“真实由 application 编排”取决于目标口径；若目标是把授权和工作流入口移出 domain，满足；若要把追问/澄清状态机迁移到 application，不满足。

### 2. Port 方向与 persistence capability

- `PlannerApplicationService.java:32-40` 注入 `PlannerSessionRepositoryPort`、`PlanAdjustmentService`、`ProposalRepositoryPort` 和 `PlannerService`。这些依赖方向是 application → domain/domain port，没有发现 domain → application 引用。
- `:136-138` 将 `proposalRepository::put` 作为能力传给 domain preview，而不是让新的 application preview 直接依赖 infrastructure adapter。
- `ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java:263-265,405-406` 通过 `Consumer<PlanAdjustmentProposal>` 使用该能力；缺省 legacy 路径仍使用 `proposalStore::put`。Proposal storage contract 位于 `ProposalRepositoryPort`，adapter 在 infrastructure。
- 依赖方向通过源码结构确认；Spring ApplicationContext 启动/注入行为本轮没有运行时验证。清单记录的测试包含 Spring Boot test，但本审查只读取清单和源码。

### 3. PlanningActor、session identity 与 revision 授权

- `ai/src/main/java/com/ai/guide/adapter/planner/web/PlannerController.java:148-167` 从 `UserContext.getUserId()` / `isAnonymous()` 构造 actor；会话 token 优先取 request，空缺时回退 `X-Plan-Session-Token` header。
- `PlannerApplicationService.java:141-151`：匿名请求先按 access token 解析 capability，再要求 capability 对应的 session ID 与 path 一致，并用 ownerId + token 再查一次。登录用户先按 USER owner 查找，失败后调用 `claimAnonymous(sessionId, token, userId)`。
- `ai/src/main/java/com/ai/guide/infrastructure/planner/repository/PlannerSessionRepository.java:108-121,124-147`：匿名 `find` 比较存储的 token digest；`findAnonymousByAccessToken` 使用 token hash；claim 是一条带 session、ownerType 和 token digest 条件的 ownership 更新。使用明确的 actor/session/capability 关联，没有发现用 sessionId 单独授权的路径。
- `PlannerApplicationService.java:124-134` 对非空 request 比较 `baseRevision` 与读取到的 session currentVersion；领域入口再在 `PlanAdjustmentService.java:268-270` 对传入 snapshot 做一次比较。
- **条件性 P2 并发观察：** 两次 revision 比较都针对同一个 `StoredSession` snapshot。另一个 mutation 若在查询后先更新数据库，这次预览仍可能继续基于旧 snapshot 生成 proposal。该 proposal 使用旧 base revision，apply 需要其自身当前 revision 门禁才能阻止提交；这会造成陈旧预览/拒绝体验，不等于已证实的越权或提交绕过。建议后续增加竞态测试，或在生成 proposal 前复读 revision。
- `PlannerPreviewUseCaseTest.java:116-124` 覆盖匿名无效 token/session 不进入领域 preview；所读新增测试未覆盖 user-owned、匿名 claim 的授权边界矩阵。

### 4. Typed outcome 与 HTTP 边界

- `ai/src/main/java/com/ai/guide/application/planner/PreviewAdjustmentResult.java:7-27` 和 `ai/src/main/java/com/ai/guide/domain/planner/model/AdjustmentPreviewExecution.java:7-27` 都无数值 status 字段；应用层公开结果是 `outcome + body`。
- `PlannerController.java:153-167,185-197` 将 `PreviewAdjustmentResult.Outcome` 映射到 HTTP response，preview controller 不再直接读取 `ServiceResult.status()`。
- **仍有边界残留：** `PlanAdjustmentService.java:413-425` 把 legacy `ServiceResult.status()` 的数值码 switch 成 typed enum；两个 enum 也把 HTTP status 类别直接编码为 outcome 名。故 transport 数值不再穿过 application 接口，但领域 service 仍将 HTTP-shaped legacy result 作为内部 preview API，domain outcome 词汇也仍偏 transport。不是“HTTP status 完全退出领域”。
- Outcome `valueOf(result.outcome().name())`（`PlannerApplicationService.java:138`）依赖两个 enum 成员永远同步；目前同名，但新增任一边缺少成员会在运行时抛 `IllegalArgumentException`。建议改为共享纯业务 outcome 或穷举显式映射；当前枚举内容一致，未构成现有候选的触发故障。

### 5. Controller wire compatibility

- 路径仍是 `POST /sessions/{sessionId}/conversation` 与 `POST /sessions/{sessionId}/adjust/preview`（`PlannerController.java:144-167`），session token body/header fallback 保留。
- `httpStatus` 映射中 SUCCESS、CLARIFICATION、NO_CANDIDATE、PREVIEW_UNAPPLICABLE 均为 200；其余 failure outcome 映射至预期 4xx/5xx（`:185-197`）。Controller 将 `result.body()` 原样作为响应 body。
- `PlannerControllerTest.java:168-218` 的源码用例覆盖 header token、anonymous actor、409 body、no-candidate 200 与 `canApply:false`。这支持基本 wire 兼容；未见运行时 HTTP/BFF E2E。
- 非目标终点（apply）仍在 `:170-179` 调用原 `applyAdjustment` 和 `response(ServiceResult)`；路径、header fallback、服务调用协议保持旧形式。

### 6. Apply / cancel 是否改动

- CP2 的新 application result/route 只用于 `converse` 与 `previewAdjustment`；`PlanningApplicationService.applyAdjustment` 仍为 `PlannerService.ServiceResult`，`PlannerController` apply endpoint 仍走原 `response()`。
- `PlanAdjustmentService.apply()` / `doApply()` 位于 `:745` 之后；冻结 CP2 slice 变更聚焦于 preview entry、proposal writer 和 outcome wrapper，没有把 apply 迁入 application typed use case。CP1 的 proposal repository port 改动仍然是前序工作区的一部分，不应误记为 CP2 apply 重构。
- 取消并非独立 HTTP endpoint，而是在 `PlanAdjustmentService.previewAuthorized()` 中识别 reset/cancel 文本并调用 `resetAdjustmentRound()`（`:272-276`; 实现 `:733-743`）。新 controller 仍将会话文本发到这个 preview policy。未见取消规则或 apply/commit protocol 的 CP2 业务逻辑改动；不过取消仍然是领域 preview 分支，不属于 application 编排。

## 发现矩阵

| 结论 | 等级 | 证据 | 影响 |
|---|---|---|---|
| application 负责 actor/session 解析、revision 门禁和 proposal writer capability | 通过 | `PlannerApplicationService.java:113-150` | 形成有实质内容的 Preview 用例切片。 |
| Domain/Application 端口方向符合依赖倒置 | 通过 | app 字段与 `proposalRepository::put`; domain 不引用 application | 未见依赖方向倒置。 |
| Clarification 状态机仍在 PlanAdjustmentService | 范围未完成 | `PlanAdjustmentService.java:272-405`; app `converse` 只是别名 | 若目标包括追问/澄清编排迁入 application，尚未完成。 |
| Typed outcome 移除接口上的数值 HTTP 状态，但领域内部仍按 status 整数分类 | 架构残留 P2 | `PlanAdjustmentService.java:413-425`; enums `...Result.java:9-22` | 新边界部分解耦，内部 domain API 仍受 HTTP 语义影响。 |
| 读后 revision 并发窗口 | 条件性 P2 可用性/体验 | app `:119-138` 对单次 StoredSession 检查并转发 | 并发更新可造成陈旧预览；需 apply revision 门禁拦截提交。未见当前提交绕过证据。 |
| Wire contract 基本保留 | 通过（有限） | controller routes/mapping `:144-197`; tests `PlannerControllerTest.java:168-218` | 只有单测证据，没有 browser/BFF E2E。 |
| apply 未迁移；cancel 规则未改且仍在领域 preview | 通过所声明的保持边界 | controller `:170-179`; domain `PlanAdjustmentService.java:272-276,733-750` | CP2 没改变 commit/cancel 业务 protocol，但也未把 cancel 纳入 application orchestration。 |
| Frozen candidate identity | 通过 | JSON SHA + 11 slice hashes + raw patch hash | 审查对象与指定 frozen candidate 一致。 |

# 渝游智策核心业务契约与系统不变量规范 (Business Invariants Spec)

版本：1.0  
制定日期：2026-10-07  
状态：**正式业务契约规范，作为系统重构与验收测试的最高判定准则**

---

## 1. 契约目标与基本原则

任何架构分层重构（包括包名调整、模型类型化、用例提取、端口隔离）均必须遵循**行为等价性（Behavior Equivalence）**。重构后系统在处理正常输入、边缘异常、并发竞争和权限边界时的行为，必须严格满足以下七大不可违背的**系统不变量（System Invariants）**。

---

## 2. 七大核心业务不变量定义

### 2.1 预览不变量（Preview Invariant）
* **定义**：调用 `previewAdjustment` / 调整预览用例时，系统必须仅计算并返回候选提议（Proposal），**绝对禁止产生任何持久化的正式行程副作用**。
* **判定准则**：
  1. 会话版本号 `currentVersion` 保持不变；
  2. 会话中的正式行程 `trip` 结构、站点顺序与节点数量保持不变；
  3. 预览提议仅暂存在临时缓存（ProposalStore）中，设置严格 TTL（代码实测 `ProposalStore.DEFAULT_TTL_MS` 为 15 分钟 / 900 秒）。
* **对应测试锚点**：[`PlanAdjustmentServiceTest.java`](file:///D:/scenic-guide/ai/src/test/java/com/ai/guide/domain/planner/service/PlanAdjustmentServiceTest.java#L100-L105)

### 2.2 确认不变量（Apply Invariant）
* **定义**：调用 `applyAdjustment` 确认应用提议时，必须通过原子性多重条件检验，成功后原子递增版本；失败时禁止部分生效。
* **判定准则**：
  1. **乐观锁 CAS 守卫**：请求中的 `baseRevision` 必须严格等于会话当前的 `currentVersion`。若不相等，强制返回 HTTP 409 Conflict，严禁私自代取最新版本覆盖；
  2. **提议归属守卫**：提议的 `sessionId` 必须与请求 `sessionId` 一致；
  3. **生命周期守卫**：提案在当前实现中对应有效的 ProposalStore 缓存项；未被消费、未过期（15分钟），且未因取消被显式驱逐或因会话 epoch 递增而失效（在步骤 4–5 中将形式化收口为 `READY_TO_CONFIRM` 显式领域状态枚举）；
  4. **幂等性守卫**：重复提交相同请求凭据时，返回同一回执，严禁重复追加同一站点；
  5. **版本递增**：应用成功后，`currentVersion` 严格单调自增 $+1$。
* **对应测试锚点**：[`PlanAdjustmentServiceTest.java`](file:///D:/scenic-guide/ai/src/test/java/com/ai/guide/domain/planner/service/PlanAdjustmentServiceTest.java#L106-L111)

### 2.3 取消不变量（Cancel Invariant）
* **定义**：提议生命周期支持显式取消，且取消操作具有终态不可逆性。
* **判定准则**：
  1. 显式取消操作从 ProposalStore 中驱逐该提案条目（或递增会话 epoch 阻断），使后续针对该提案的 apply 请求无法命中或被拒绝（目标状态对应 `CANCELLED` 终态）；
  2. 任何已取消或已失效的提案，后续 `applyAdjustment` 请求强制返回 400 或 409（`PROPOSAL_EXPIRED` 或 `PROPOSAL_NOT_FOUND`）；
  3. **自然语言否定词防御**：用户输入“不要取消”、“别取消方案”、“千万别取消”等否定语境时，系统坚决禁止触发取消逻辑；
  4. 取消操作只清理待确认状态，绝对不得回退或篡改正式行程的历史版本。
* **对应测试锚点**：`CompoundTimeAndCancellationTest`、`CancellationProposalLifecycleTest`

### 2.4 追问与多轮续接不变量（Continuation Invariant）
* **定义**：在自然语言澄清（Clarification）和多步组合调整场景下，系统必须具备状态继承能力，杜绝“追问黑洞”。
* **判定准则**：
  1. **意图槽位继承**：当系统因缺失地点发起澄清（如“您想替换哪一个景点？”），用户随后仅补答地点（如“换成三峡博物馆”），系统必须继承上一轮被澄清的意图与时间槽，禁止判定为未知意图；
  2. **时段纠正优先**：当用户明确补正时段（如“改成下午两点”），新时段必须覆盖旧的冲突时段；
  3. **复合任务队列化**：当用户提出多步骤复合任务（如“第一天加茶歇，第二天换酒店”），系统按原始序号建立任务队列，逐项确认，禁止在应用第一项后悄悄丢弃第二项。
* **对应测试锚点**：[`AdjustmentTimeScopeResolver.java`](file:///D:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/AdjustmentTimeScopeResolver.java)、[`PendingIntentContinuations.java`](file:///D:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/PendingIntentContinuations.java)

### 2.5 版本单调与防脏写不变量（Revision Invariant）
* **定义**：正式行程与规划会话的版本推进必须是严格全序且单调递增的。
* **判定准则**：
  1. 客户端提交的变更必须显式附带其当前渲染的 `baseRevision`；
  2. 无实际业务内容变动的保存（例如内部运行时缓存 key 的读取或空保存），绝对禁止自增版本号；
  3. 迟到的旧响应或来自其他会话的旧回执，在前端与 BFF 必须被安全丢弃，绝不能用旧版本覆盖新版本（防逆流脏写）。
* **对应测试锚点**：[`combined-logic-regression.test.mjs`](file:///C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/scripts/combined-logic-regression.test.mjs)

### 2.6 身份隔离与所有权不变量（Identity Invariant）
* **定义**：系统内所有会话、正式行程、偏好记忆与反馈记录严格按租户/用户/访客隔离。
* **判定准则**：
  1. **水平越权拦截**：用户 A 无法通过猜测 `sessionId` 或 `tripId` 读取或修改用户 B 的私有数据（未授权端点统一返回 404 或 403）；
  2. **请求体伪造防御**：请求体中的 `userId` 严禁覆盖经 JWT / Cookie 验证的真实安全上下文；
  3. **匿名会话原子认领**：未登录访客草稿在用户登录后，必须凭高熵 `access_token` 进行一次性原子认领，认领后旧令牌失效，防止重放与跨用户抢领。
* **对应测试锚点**：[`ChatHistoryServiceTest.java`](file:///D:/scenic-guide/ai/src/test/java/com/ai/guide/common/security/JwtAuthFilterTest.java)、[`TripOwnershipTest`](file:///D:/scenic-guide/ai/src/test/java/com/ai/guide/domain/trip/service/TripSaveCapacityRegressionTest.java)

### 2.7 固定站点与时空物理不变量（Fixed Stop & Spatial Invariant）
* **定义**：行程中的硬性时空与用户显式指定约束享有绝对保护特权。
* **判定准则**：
  1. **固定站点绝对保护**：用户锁定或标记为 `fixed` 的站点，其游览停留时长与时间顺序在局部修复算法（Local Plan Repairer）与加站挤压中享有最高保护，系统宁可报错/标记不可行，也绝对禁止私自移除或压缩固定站点；
  2. **营业全区间闭环**：站点开始游览至游览结束的完整区间，必须落在场馆营业时间与开放日期内；周一闭馆、午间休馆、晚间停运等事实不可被 `forceApply` 绕过；
  3. **跨午夜物理越界守卫**：加站或时序顺延导致行程推进至 `00:00` 跨午夜次日凌晨时，必须显式诊断出 `MIDNIGHT_OVERFLOW`，禁止在未征得用户同意下生成危险红眼行程。
* **对应测试锚点**：[`AddStopAdjustmentStrategy.java:test_lateCableCarCutoffRejectsFeasibility`](file:///D:/scenic-guide/ai/src/test/java/com/ai/guide/domain/planner/service/PlanAdjustmentServiceTest.java#L1880-L1901)、[`OpeningHoursPolicy.java`](file:///D:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/engine/OpeningHoursPolicy.java)

---

## 3. 契约维护与自动化回归门禁

在后续步骤中，任何代码变更（尤其是 Pilot 试点与用例抽取）完成后，必须通过以下套件确保上述 7 大不变量无一被破坏：
1. `PlanAdjustmentServiceTest`（覆盖预览、确认、取消、固定站与时空物理不变量）；
2. `CombinedLogicRegressionTest` 与 Node `combined-logic-regression.test.mjs`（覆盖版本单调、追问与身份不变量）；
3. `ColaArchitectureGuardTest`（覆盖分层与依赖单向不变量）。

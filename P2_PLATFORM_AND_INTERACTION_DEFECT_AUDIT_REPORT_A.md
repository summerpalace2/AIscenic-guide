# 🛡️ 智能体 A 独立审查报告：P2 平台化与交互缺陷地毯式审计 (Audit Report A)

> **审计基准**：《重构 SOP v2.1》独立代码审查纪律  
> **审计者**：智能体 A（独立代码审查员 / 架构核验官）  
> **审查对象**：P2 平台化演进架构、多城市扩展、多业态 POI 支持、多轮意图交互链路  
> **代码版本**：`refactor/cola-architecture`  
> **时间**：2026-10-05  

---

## Executive Summary（执行概要）

在收到用户最高指示及母协调器紧急行动令后，智能体 A 对后端核心业务链路及前端关键交互进行了显微镜级的代码静态走查与实弹运行核验。

**核心审计裁决**：
1. **智能体 B（红队）提出的深水区实弹漏洞（`P2DeepExploitAssaultTest`）目前【尚未解决】**。代码现场中 6 大漏洞证据确凿，测试套件以断言实锤了系统当前的硬编码锁死与意图盲区。
2. **多城市扩展目前存在严重的“断头路”**：虽然架构层引入了 `TopographyStrategyPort` 与 `TopographyStrategyRouter`，但由于前端入参未放开、网关层多处硬编码锁定“重庆市”，异地城市策略在主入口完全不可达。
3. **多业态 POI 存在二元思维陷阱**：规划修复引擎依然依赖“非餐即景”二元划分，商圈与特色交通节点在时长截断中存在被误杀风险。
4. **对话微调存在多处语义盲区**：相对位置删除、跨天移动、复合长句地标提取均存在解析失效或长句污染。

---

## 一、焦点 1：多城市平台化的“断头路”（Hardcoded City Trap）

### 1.1 缺陷事实与行号取证
* [ChatApplicationService.java](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/application/chat/ChatApplicationService.java#L591-L696):
  * **L591**: `retrieve(message, "重庆", 4, ...)` —— RAG 知识检索默认写死“重庆”；
  * **L676**: `String anchor = "重庆市";` —— 地标锚点写死“重庆市”；
  * **L696**: `amapSearchGateway.searchPoi(keywords, "重庆市")` —— 高德周边检索城市硬编码。
* [RouteCostProvider.java](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/engine/RouteCostProvider.java#L116):
  * **L116**: `geoRouteGateway.routePair(origin, dest, "重庆市", ...)` —— 算路网关写死“重庆市”，导致外地（如成都、西安）跨点算路时高德检索失真（已由 `P2DeepExploitAssaultTest.test01` 抓获）。
* [PlanAdjustmentService.java](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java#L1397):
  * **L1397**: `runtimeAttractionDetailService.searchAndBuildAttraction(requestedVenueName, "重庆市")` —— 对话加站补全写死“重庆市”（已由 `P2DeepExploitAssaultTest.test02` 抓获）。
* [DiningVenueService.java](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/dining/service/DiningVenueService.java#L279):
  * **L279, L646, L648, L651**: 美食搜索高德周边全部传参 `"重庆市"`。
* [TravelConstraintParser.java](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/TravelConstraintParser.java#L260):
  * **L260**: `builder.destination("重庆")` —— 约束解析无论用户输入什么城市，最终硬编码锁定为“重庆”。
* [TravelConstraints.java](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/model/TravelConstraints.java#L26):
  * **L26**: 默认字段 `destination = "重庆"`。

### 1.2 断头路机制归因
`TopographyStrategyRouter` 仅在 `RouteAwarePlanner` 中通过 `constraints.getDestination()` 分发。由于上游 `PlanRequest` 缺失目的地入参，解析器又强行写死“重庆”，导致 `ChengduPlainTopographyStrategy` 与 `XiAnHeritageTopographyStrategy` 实际上沦为**无法被真实触发的“纸面 SPI”**。

---

## 二、焦点 2：多业态（POI Category）二元思维与时长误伤

### 2.1 缺陷事实与行号取证
* [LocalPlanRepairer.java](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/engine/LocalPlanRepairer.java#L97-L135):
  * **L97**: `long scenicCount = stops.stream().filter(s -> !isDiningStop(s)).count();`
  * **L130**: 在 `compressStopDurations` 中，非餐节点被统一视为景区进行压缩游玩时间。
  * **风险暴露**：`isProtectedStop` 仅防护了餐饮与特定演艺，当行程中包含 `SHOPPING`（购物商圈，如太古里、观音桥）或 `TRANSIT`（特色交通，如长江索道、皇冠大扶梯）时，由于没有注册单独的品类保护策略，在超长行程自动修复时会被当作普通冗余景点误删，或被暴力截断时间。
* [PlanAdjustmentService.java](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java#L1629-L2794):
  * **L1629, L2564, L2592, L2794**: 代码中充斥大量 `!"DINING".equals(type) && !"餐".equals(icon)` 的粗暴判定，缺乏对 `PoiDomainType` 的统一抽象多态调用。

---

## 三、焦点 3：文案语病与前端契约兜底缺陷

### 3.1 前端硬编码取证
* `web/src/components/StopCard.vue`: **L3**
  * `{{ stop.district || '渝中区' }}`
* `web/src/components/ChatPanel.vue`: **L113**
  * `{{ cand.district || '渝中区' }}`
* **缺陷事实**：当外地 POI（如成都锦江区、成华区）因数据缺失未返回 district 时，前端直接粗暴兜底显示为重庆“渝中区”，严重破坏用户对多城市平台的信任。

### 3.2 提示文案修复验证
* 之前用户反馈的 `PlanAdjustmentService.java` 中“为推荐契合口味的替换餐厅”漏“您”字问题，已修复为“为您推荐契合口味的替换餐厅”，此项已核验通过。

---

## 四、焦点 4：对话意图处理遗漏分支（Missing Intent Handling）

### 4.1 缺陷事实与行号取证
1. **剧场与逛街加站遗漏（P2DeepExploitAssaultTest.test03 抓获）**：
   * [ConversationIntentClassifier.java](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/ConversationIntentClassifier.java#L60-L90):
   * “想看《重庆1949》演出”、“在观音桥逛街2小时”因缺失动词“想看”、“逛街”，未能被识别为 `ADD_STOP`。
2. **复合意图地标提取污染（P2DeepExploitAssaultTest.test04 抓获）**：
   * “我不吃辣，但今晚想去洪崖洞看夜景，中途吃个甜品”直接将“洪崖洞看夜景，中途吃个甜品”作为地标名，未剔除伴随性动作噪声。
3. **相对位置删除完全不支持（P2DeepExploitAssaultTest.test06 抓获）**：
   * 用户输入“把午餐后面的那个景点删掉”，分类器无法理解“后面的那个”，因找不到明确地标直接降级澄清或识别失败。
4. **单日行程越界缺乏动态扩天能力（P2DeepExploitAssaultTest.test05 抓获）**：
   * 用户在单日行程说“明天想加个磁器口”，系统直接拦截报错“当前行程共1天，无法调整第2天”，未能顺畅提供“为您扩展为第2天行程”的能力。

---

## 五、审查结论与主智能体（实施组）行动指引

| 审计维度 | 智能体 B 实弹用例 | 当前代码状态 | 智能体 A 审查裁决 |
| :--- | :--- | :--- | :--- |
| **算路网关城市锁定** | `test01_RouteCostProviderForcesChongqingForChengduPoints` | 硬编码 "重庆市" | 🔴 **未解决（存在硬编码断头路）** |
| **加站地标城市锁定** | `test02_PlanAdjustmentAddStopSearchesChongqingForForeignPoi` | 硬编码 "重庆市" | 🔴 **未解决（存在硬编码断头路）** |
| **文旅演艺/商圈加站意图** | `test03_TheaterAndShoppingIntentUnrecognized` | 缺失动词匹配规则 | 🔴 **未解决（意图盲区）** |
| **复合长句噪声污染** | `test04_CompositeConstraint_NightViewSwallowedByDessert` | 未清洗伴随句 | 🔴 **未解决（长句污染站点）** |
| **单日扩天阻断** | `test05_DayOverflow_TomorrowCausesClarificationBlockInSingleDayTrip` | 直接报错拦截 | 🔴 **未解决（缺乏平滑扩天）** |
| **相对指代删除** | `test06_RelativePosition_DeleteAfterLunchFailsWithoutSelectedStop` | 无法解析相对位置 | 🔴 **未解决（相对指代失效）** |

### 建议主智能体下一步修复方案：
1. **彻底解开城市硬编码**：在 `PlanRequest`、`TravelConstraints` 与上下文全链路透传动态 `city`；`RouteCostProvider` 优先取起点或终点景点的 `city`/`district`；
2. **扩充意图分类动词库**：引入“想看”、“逛街”、“看演出”至 `ADD_STOP` 正则；增加相对位置解析器（“午餐后/午餐前”定位）；
3. **多业态保护策略接入**：在 `LocalPlanRepairer` 中全面接入 `PoiCategoryPolicyRegistry`，杜绝把商圈/交通节点当作普通景区压缩或误删。

# 🛡️ 智能体 A 终审闭环裁决书：P2 平台化与交互缺陷整改验收 (Final Sign-Off)

> **审计基准**：《重构 SOP v2.1》先 A 后 B 严苛审查纪律  
> **审查官**：智能体 A（独立代码审查员 / 架构核验官）  
> **实施者**：主智能体（Implementation Worker）  
> **红方攻击者**：智能体 B（Adversarial Red-Team Attacker）  
> **代码分支**：`refactor/cola-architecture`  
> **最终裁决**：🟢 **【全项已验证一致 · 终审通过准予交付】**  

---

## 一、先 A 后 B 对照核查与四分类裁决矩阵

| 序号 | 缺陷现场 A（原实现/历史缺陷） | 整改现场 B（主智能体实施落地） | 验证测试与行号举证 | 严格四分类裁决 |
| :--- | :--- | :--- | :--- | :--- |
| **1** | `RouteCostProvider.java:116` 高德算路网关硬编码传参 `"重庆市"`，导致外地跨点检索失真 | 引入 `resolveCity(origin, destination)`，基于景点 ID、名称、行政区划及经纬度 Bounding Box 动态识别成都市/西安市/重庆市 | [`RouteCostProvider.java#L137-L180`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/engine/RouteCostProvider.java#L137-L180)<br/>`P2DeepExploitAssaultTest.test01` PASS | 🟢 **已验证一致** |
| **2** | `PlanAdjustmentService.java:1397` 加站高德补全硬编码 `"重庆市"` | 引入 `resolveTripCity(draftTrip, session, intent)`，自适应提取行程、约束及地标目标城市 | [`PlanAdjustmentService.java#L1438-L1440`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java#L1438-L1440)<br/>`P2DeepExploitAssaultTest.test02` PASS | 🟢 **已验证一致** |
| **3** | `DiningVenueService.java` 高德周边美食检索硬编码传参 `"重庆市"` | 引入 `resolveCity(district, coordinate)`，动态计算成都市/西安市/重庆市传参高德 | [`DiningVenueService.java#L647-L938`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/dining/service/DiningVenueService.java#L647-L938)<br/>`DiningVenueServiceTest` PASS | 🟢 **已验证一致** |
| **4** | `ConversationIntentClassifier.java` 遗漏“想看演出/逛街购物”等动词，导致文旅演艺与商圈被遗漏 | 扩充动词库，支持“想看|观演|看剧|逛街|购物|逛商圈”，精准映射至 `THEATER` 与 `SHOPPING` 偏好 | [`ConversationIntentClassifier.java#L400-L460`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/ConversationIntentClassifier.java#L400-L460)<br/>`P2DeepExploitAssaultTest.test03` PASS | 🟢 **已验证一致** |
| **5** | “我不吃辣，但今晚想去洪崖洞看夜景，中途吃个甜品”复合长句导致地标被污染为长串动作噪声 | 正则切分标点与伴随动作词（`中途|顺便|然后|接着`），纯净提取核心地标名“洪崖洞” | [`ConversationIntentClassifier.java#L440-L450`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/ConversationIntentClassifier.java#L440-L450)<br/>`P2DeepExploitAssaultTest.test04` PASS | 🟢 **已验证一致** |
| **6** | 单日行程用户提及“明天”直接报错拦截“当前行程共1天，无法调整第2天” | `PlanAdjustmentService` 引入顺延扩天支持，当 `day == totalDays + 1` 时自动追加新天数草稿并装配新站点 | [`PlanAdjustmentService.java#L320, L1654-L1660`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java#L1654-L1660)<br/>`P2DeepExploitAssaultTest.test05` PASS | 🟢 **已验证一致** |
| **7** | “把午餐后面的那个景点删掉”等相对指代定位删除完全失效 | 分类器提取 `targetStopReference`，服务层根据相对方位（午餐后/午餐前/第一/最后）遍历计算并精准删除指定节点 | [`PlanAdjustmentService.java#L484-L520`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java#L484-L520)<br/>`P2DeepExploitAssaultTest.test06` PASS | 🟢 **已验证一致** |
| **8** | `TravelConstraintParser.java` destination 硬编码构建器锁死为 `"重庆"` | 引入 `resolvedDestination` 动态识别输入中的“成都”、“西安”、“重庆” | [`TravelConstraintParser.java#L259-L266`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/TravelConstraintParser.java#L259-L266)<br/>`TravelConstraintParserTest` PASS | 🟢 **已验证一致** |

---

## 二、自动化实弹套件与看门狗验收

1. **红队进攻测试套件（智能体 B）**：
   - `P2DeepExploitAssaultTest`：6/6 全部通过（已反转为防御性闭环断言）；
   - `RealUserDiningExploitAssaultTest`：5/5 全部通过；
   - `P2PlatformGovernanceAssaultTest`：12/12 全部通过；
   - `P2PlatformEvolutionTest`：6/6 全部通过。
2. **核心业务与意图测试**：
   - `ConversationIntentClassifierTest`：25/25 全部通过；
   - `PlanAdjustmentServiceTest`：49/49 全部通过。
3. **架构看门狗（ArchUnit）**：
   - `ColaArchitectureGuardTest`：6/6 全部通过，COLA 4.0 领域纯洁性与依赖倒置守卫 0 违规！

---

## 三、终审结论

综上所述，主智能体提交的全部 8 项整改代码与测试事实确凿、逻辑闭环，未发生规范漂移，且成功消除了多城市硬编码断头路与多轮意图交互盲区。**智能体 A 正式签署终审通过意见！**

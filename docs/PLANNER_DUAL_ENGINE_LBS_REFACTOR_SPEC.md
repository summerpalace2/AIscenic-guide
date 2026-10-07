# 【双引擎驱动·去规则代偿】规划引擎权威 LBS + LLM 架构升级评审与改进规范

> **文档编号**：SPEC-2026-PLANNER-LBS-DUAL-ENGINE
> **制定专家**：智能体 B（首席工程落地与攻防专家）
> **执行工程师**：智能体 A（核心算法与规划中枢实现者）
> **审计与攻防专家**：智能体 B（验收把关） & 智能体 C（红方对抗验证）
> **分支基线**：`refactor/plan-adjustment-monolith-decoupling`
> **核心宗旨**：全面贯彻“**坚决废除用大正则替代小正则的打地鼠反模式，事实型地理数据一律归权于高德 LBS，意图抽取归权于 LLM，Java 领域层回归纯粹时空求解器**”的核心准则。

---

## 一、架构重构核心思想与设计哲学（Architecture Philosophy）

在此前的系统演进中，系统大量采用“静态正则匹配”、“手写黑知名名单”和“硬编码经纬度”来补偿外部服务调用的不确定性，导致了严重的“规则打地鼠（Whack-a-Mole）”陷阱。

本规范确立**【双引擎驱动范式（Dual-Engine Architecture）】**，推行三大职责彻底归位：

```
+-----------------------------------------------------------------------------------+
|                            用户自然语言 / 会话上下文                             |
+-----------------------------------------------------------------------------------+
                                         │
                                         ▼
+-----------------------------------------------------------------------------------+
|               【引擎 1：大语言模型（LLM）】意图识别与实体提取                      |
|  - 负责自然语言理解与诉求抽取（如将“我想吃点甜食的”解析为 craving = "甜食"）     |
|  - 废除上千行机械穷举的对话意图正则表达式                                        |
+-----------------------------------------------------------------------------------+
                                         │
                                         ▼
+-----------------------------------------------------------------------------------+
|               【引擎 2：高德开放平台（LBS Web API）】权威地理与业态事实           |
|  - 权威行业类别代码（typecode）：05=餐饮服务, 14=高校设施, 10=住宿酒店, 08=剧场    |
|  - 权威逆地理编码（regeo）：坐标 -> 精确省市区县及 adcode（无需手抄 2844 个区县） |
|  - 权威地理编码（geo）：城市名 -> 官方行政中心经纬度（无需写死解放碑魔数坐标）     |
|  - 实时周边鲜活检索（around）：真实商户营业状态、人均消费、实时评分与距离          |
+-----------------------------------------------------------------------------------+
                                         │
                                         ▼
+-----------------------------------------------------------------------------------+
|               【Java 核心领域层】纯粹时空拓扑约束求解器                          |
|  - 球面 Haversine 大圆距离与时空矩阵加权评分                                     |
|  - 级联时钟顺延（Ripple Recalculation）与因果时间链推算                           |
|  - 单日最少 1 站不变式、开闭馆时间硬窗口校验、会话级细粒度并发锁 (OCC)            |
+-----------------------------------------------------------------------------------+
```

---

## 二、六大重构任务与精确改造方案

### 任务 1：校园/非餐饮判断改由高德 `typecode` 驱动（消灭打地鼠补丁与黑名单）
* **涉及文件**：
  1. [`DiningCategoryGrounder.java`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/engine/DiningCategoryGrounder.java)（第 94 行）
  2. [`RuntimeLocationPlanner.java`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/RuntimeLocationPlanner.java)（第 1156-1172 行）
* **原代码病灶**：
  - `DiningCategoryGrounder:94` 专为 test37 写死 `.*(四教|一教|二教|三教).*`；
  - `RuntimeLocationPlanner:1156-1172` 手写几百个词的黑名单，包含“一教到八教”、“李抄手”、“玫琳凯”、“饲料房”等离谱商户。
* **重构方案**：
  1. **高德 TypeCode 权威裁决**：
     高德 POI 原生返回 `typecode`：
     - `050000 ~ 050400`：餐饮服务大类；
     - `141200 ~ 141207`：科教文化服务；学校；高等院校及教学设施；
     - `200000 ~ 200400`：公共设施；
     当通过 `searchPoi` 或 `searchAround` 检索到候选时，判定 `typecode != null && !typecode.startsWith("05")` 即为非餐饮。
  2. **离线与无网纯文本降级规则规范化**：
     彻底删除 `四教|一教|二教|三教`、`李抄手`、`玫琳凯` 等具体商户与数字编号。
     在 `isNonDiningPlace` 中统一提炼为通用结构化形态：
     `.*(?:大学|学院|校区|实验楼|实训中心|教学区|科研楼|图书馆|保卫处|服务中心|大厦|写字楼|科技园|研发中心).*`，对非餐饮地点进行泛化判定。

---

### 任务 2：餐饮风味提取由 LLM 实体 + 高德全量周边搜索驱动（消灭 8 个 Matches 分支）
* **涉及文件**：[`DiningCategoryGrounder.java`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/engine/DiningCategoryGrounder.java)（第 134-150 行）
* **原代码病灶**：
  写死火锅、小面、甜品、茶馆、西餐、烧烤、清淡、江湖菜等 8 个正则分支；若用户在西安说“想吃羊肉泡馍”，在成都说“想吃钵钵鸡”，或说“椰子鸡/寿喜烧”，直接被粗暴降级为“特色美食”。
* **重构方案**：
  1. 废除 `resolveRequestedFlavor` 中的硬编码正则穷举；
  2. 用户在聊天或调整意图中提到的美食诉求实体（如“椰子鸡”、“寿喜烧”、“羊肉泡馍”、“甜食”），原汁原味作为 `cravingKeywords`；
  3. 在 `DiningVenueService.searchLiveDiningOrTea` 中，将 `cravingKeywords` 直接作为高德 `/v3/place/around` 的 `keywords` 传入，并设置 `types = "050000"`（餐饮全类目），利用高德 1.5 亿+ 实时商户库直接召回真正的“椰子鸡”或“羊肉泡馍”店铺；
  4. 默认兜底品类（Default Flavor）由城市中枢配置提供（重庆->重庆火锅/重庆小面，成都->特色川味/担担面，西安->陕西风味/泡馍），彻底消灭全代码对特定菜系的写死。

---

### 任务 3：城市与区县解析改由高德逆地理与行政区划驱动（消灭 2844 个区县手写地狱）
* **涉及文件**：
  1. [`RouteCostProvider.java`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/engine/RouteCostProvider.java)（第 137-170 行）
  2. [`PlanAdjustmentHelper.java`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/adjustment/PlanAdjustmentHelper.java)（第 271 行）
* **原代码病灶**：
  在 Java 里硬编码成都区县正则 `锦江|青羊|金牛|武侯...` 和西安区县正则；辅助类直接兜底返回 `"重庆市"`。
* **重构方案**：
  1. 构建统一的城市解析策略：
     - **优先级 1**：当前 Trip 的 `destinationCity` 或 `city` 字段（明确的会话城市）；
     - **优先级 2**：若存在景点坐标（经纬度），通过经纬度范围或调用高德逆地理编码（`/v3/geocode/regeo`）获取归属市；
     - **优先级 3**：若只有区县名，建立行政区划前缀映射表或查询高德 `/v3/config/district` API；
  2. 彻底移除 `RouteCostProvider` 中手写的各城市区县正则长字符串，消灭维护 2844 个区县的噩梦。

---

### 任务 4：品牌白名单去硬编码（消灭 `KNOWN_DINING_BRANDS` 21 品牌 Set）
* **涉及文件**：[`DiningCategoryGrounder.java`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/engine/DiningCategoryGrounder.java)（第 23-27 行）
* **原代码病灶**：
  内存 `Set.of("肯德基", "麦当劳", "海底捞"...)` 写死 21 个全国品牌。
* **重构方案**：
  1. 废除静态写死 Set；
  2. 建立商户实体判别原则（三维判定法）：
     - **特征 A（商户后缀）**：名称带有明确的门店后缀（`.*(?:店|分店|总店|支店|专卖店|旗舰店|档口).*`）；
     - **特征 B（书名号/引号包裹）**：用户以 `【】`、`“”` 或 `《》` 强调的商户；
     - **特征 C（高德实景 POI 核验）**：当需要判断时，调用高德 POI 查询，若首条 POI 的 `name` 与输入高度契合且 `typecode` 为餐饮，直接认定为真实商户实体；
  3. 保留内置轻量缓存（Caffeine/ConcurrentHashMap），无需在 Java 代码中维护庞大死表。

---

### 任务 5：动态城市中心坐标解析引擎（消灭解放碑 `106.577000, 29.556000` 宇宙中心）
* **涉及文件**：
  1. [`ReplaceStopAdjustmentStrategy.java`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/adjustment/ReplaceStopAdjustmentStrategy.java)（第 305 行）
  2. [`AddStopAdjustmentStrategy.java`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/adjustment/AddStopAdjustmentStrategy.java)（第 609 行）
  3. [`PlanAdjustmentHelper.java`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/adjustment/PlanAdjustmentHelper.java)（第 131 行）
  4. [`DiningVenueService.java`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/dining/service/DiningVenueService.java)（第 91, 175 行）
* **原代码病灶**：
  无坐标时全部字符串写死 `"106.577000,29.556000"`（重庆解放碑）。
* **重构方案**：
  在 `PlanAdjustmentHelper` 与相关策略中引入动态中枢定位方法：
  ```java
  public static String resolveDefaultCoordinate(Map<String, Object> tripDraft, TravelConstraints constraints)
  ```
  1. **动态质心法（Centroid）**：遍历当日或全程已安排景点的有效经纬度，计算几何平均值（重心），以游客当天实际游玩聚集区为检索中心；
  2. **城市中心中枢法**：若全天无任何景点坐标，根据会话城市名动态解析其行政中心：
     - 重庆：`106.577000,29.556000`（解放碑）
     - 成都：`104.066541,30.572269`（天府广场）
     - 西安：`108.948024,34.263161`（钟楼）
     - 其他城市：调用高德地理编码 `/v3/geocode/geo?address={cityName}` 动态获取 `center` 坐标并缓存；
  3. 杜绝在各个业务策略里直接散落字面量 `"106.577000,29.556000"`。

---

### 任务 6：POI 业态分类改由高德 `typecode` 驱动（消灭 `name.contains("酒店")`）
* **涉及文件**：[`PoiCategoryPolicyRegistry.java`](file:///d:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/PoiCategoryPolicyRegistry.java)（第 52-64 行）
* **原代码病灶**：
  靠 `name.contains("酒店")`、`name.contains("剧场")` 判断业态，导致无“酒店”字样的顶级奢华酒店（如“重庆尼依格罗”、“青城山六善”）与“施光南音乐厅”被错判为普通景点。
* **重构方案**：
  重构 `resolveCategory(Map<?, ?> stop)` 方法：
  1. 优先读取 POI 的 `typecode`（由高德返回）：
     - `100000 ~ 100200`（住宿服务） -> `PoiDomainType.HOTEL`
     - `080600 ~ 080603`（演艺/剧场场馆） -> `PoiDomainType.THEATER`
     - `060000 ~ 060400`（购物/商圈） -> `PoiDomainType.SHOPPING`
     - `110000 ~ 110200`（交通设施） -> `PoiDomainType.TRANSIT`
     - `050000 ~ 050400`（餐饮服务） -> `PoiDomainType.DINING`
     - `110105` 或缆车索道 -> `PoiDomainType.TRANSIT`
  2. 仅当 `typecode` 缺失或为空的降级场景下，才执行轻量兜底正则词缀匹配。

---

## 三、单测 100% 保持通过原则（Zero-Regression Baseline）

本次重构必须严格守卫以下质量底线：
1. **630+ 现有单元测试全量绿灯**：
   - 特别保证 `PlanAdjustmentServiceTest` 中 58 个测试（包括 `test37` 重庆邮电大学四教、`test43` 甜食加站、换餐加餐等）100% 通过；
   - 保证 `DiningCategoryGrounderTest` 全部通过；
   - 保证 `P2DeepExploitAssaultTest` 等红蓝对抗实测全部通过；
2. **纯粹领域层边界守护**：
   - 保持 COLA 分层纯洁度，`domain` 严禁反向 import `infrastructure` 或 `adapter`。

---

## 四、执行工作流与责任分工

```
+---------------------+     规范交接     +---------------------+
| 智能体 B (架构制定)  | ───────────────> | 智能体 A (工程落地)  |
+---------------------+                  +---------------------+
                                                    │
                                                    │ 执行代码修改与 mvn test 验证
                                                    ▼
+---------------------+     代码审查     +---------------------+
| 智能体 B (合规审计)  | <─────────────── | 智能体 A (交付战报)  |
+---------------------+                  +---------------------+
           │
           │ B 确认无问题，移交红方
           ▼
+---------------------+     极限渗透     +---------------------+
| 智能体 C (红方渗透)  | ───────────────> | 实弹验证双引擎抗攻击力 |
+---------------------+                  +---------------------+
```

1. **第一步（当前完成）**：智能体 B 完成本规范并归档；
2. **第二步**：交给智能体 A 严格按照规范执行代码改造，并运行 `mvn test` 验证通过；
3. **第三步**：智能体 B 逐条核查 A 的代码改动与规范的一致性；
4. **第四步**：智能体 C 启动针对“高德 TypeCode 穿透、多城市极端地名、无网降级、边界打地鼠”的专项实弹攻击！

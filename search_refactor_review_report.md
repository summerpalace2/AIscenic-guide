# Search 文旅多源综合搜索域重构·独立代码审查与红方质检报告

> **审查执行角色**：独立代码审查智能体（Independent Code Reviewer / Adversarial Nitpicker）  
> **审查基准版本 (A)**：Commit `42d69a9`  
> **审查目标版本 (B)**：工作区 `refactor/cola-architecture`  
> **审查对象**：Slice 7 - Search 文旅多源综合搜索域  
> **审查纪律依据**：《保持 UI 与功能一致的大规模重构流程（定稿 v2.1）》& COLA 架构分层规范  
> **审查结论**：**PASS（已通过红方显微镜级比对，未发现回归差异，准予合并入库）**

---

## 一、 审查方法与比对现场 (Methodology & Evidence)

本审查严格遵守**“先 A 后 B”**原则：
1. 绝对不采信主任务的自我陈述或单一测试通过结果；
2. 首先在历史基准 Commit `42d69a9` 中还原搜索域所有既有契约现场，提取端点、参数默认值、空参防御、异常转译码、结果截断融合排序等 7 项物理契约；
3. 随后逐行比对重构后 B 的对应实现代码行号，严查规范漂移与凭空脑补；
4. 运行单域自动化测试与全量 401 项工程测试，验证回归稳定性。

---

## 二、 逐项核验对账清单（严格四分类）

| 编号 | 契约/行为特征项 | A 的源码现场 (`42d69a9`) | B 的实现现场 (`refactor/cola-architecture`) | 四分类判定 | 证据与显微镜比对分析 |
|---|---|---|---|---|---|
| **CHK-01** | REST 端点路由映射 (`/ai/search`) | `SearchController.java` [L18-19, L30, L38]:<br/>• `@RequestMapping("/ai/search")`<br/>• `@GetMapping("/baidu")`<br/>• `@GetMapping("/amap")` | `SearchController.java` [L20-21, L30, L37]:<br/>• `@RequestMapping("/ai/search")`<br/>• `@GetMapping("/baidu")`<br/>• `@GetMapping("/amap")` | `[已验证一致]` | 路由路径、HTTP 动词（GET）100% 保持一致，无任何路径前缀或方法漂移。 |
| **CHK-02** | 请求参数与默认值契约 | `SearchController.java`:<br/>• L31-33: `q`, `limit` (默认 "10")<br/>• L38-41: `q`, `city` (默认 "重庆市"), `types` (required=false) | `SearchController.java`:<br/>• L31-35: `q`, `limit` (默认 "10")<br/>• L37-43: `q`, `city` (默认 "重庆市"), `types` (required=false) | `[已验证一致]` | 参数名称、类型以及 Spring MVC `@RequestParam` 默认值与可选属性逐字一致。 |
| **CHK-03** | **切斯特顿围栏**：`/amap` 空参防御与 200 状态码 | `SearchController.java` [L42-44]:<br/>```java
if (q == null || q.isBlank()) {
    return Result.success("请输入搜索关键词", List.of());
}
``` | `SearchApplicationServiceImpl.java` [L38-40]:<br/>```java
if (q == null || q.isBlank()) {
    return Result.success("请输入搜索关键词", List.of());
}
``` | `[已验证一致]` | **重点防线守住**：空 query 未被擅自改成 400 Bad Request，忠实保留了 HTTP 200 + `Result.success` + 提示文案 `"请输入搜索关键词"` 的既有契约。 |
| **CHK-04** | `/amap` 异常映射与 503 状态码 | `SearchController.java` [L45-52]:<br/>• SUCCESS -> 200 "高德POI检索成功"<br/>• INVALID -> 200 "请输入有效搜索关键词"<br/>• 其他 -> 503 "高德检索暂不可用: ..." | `SearchApplicationServiceImpl.java` [L41-48]:<br/>• SUCCESS -> 200 "高德POI检索成功"<br/>• INVALID -> 200 "请输入有效搜索关键词"<br/>• 其他 -> 503 "高德检索暂不可用: ..." | `[已验证一致]` | `q.trim()` 处理完全保留；`OutcomeStatus` 各状态分支转译无遗漏，文案及 503 错误码无任何漂移。 |
| **CHK-05** | `BaiduSearchService` 多源聚合与截断逻辑 | `BaiduSearchService.java` [L43-118]:<br/>1. `limit` 约束在 `[3, 15]` 之间；<br/>2. 本地知识库上限 3 条（带 120 字截断、分类识别、百度直通 URL）；<br/>3. 高德 POI 检索（标题去重、分类识别、百度 URL、实景图片）；<br/>4. 百度全网搜索直通卡片兜底（1 条）；<br/>5. `subList(0, Math.min(results.size(), maxItems))`。 | `BaiduSearchService.java` [L43-118]:<br/>1. `maxItems = Math.max(3, Math.min(limit, 15))`；<br/>2. 本地知识库逻辑完全同 A；<br/>3. 高德 POI 通过 `PoiSearchGatewayPort` 获取，字段填充与去重同 A；<br/>4. 百度直通卡片文案及 favicon 完全同 A；<br/>5. 最终截断同 A。 | `[已验证一致]` | 聚合源顺序（本地库 -> 高德 POI -> 百度全网卡片）、阈值范围 `[3, 15]`、字段生成规则与异常降级策略均 100% 忠实保留。 |
| **CHK-06** | 历史旧 API 目录物理清理 | 42d69a9 存在：<br/>`ai/src/main/java/com/ai/guide/domain/search/api/SearchController.java` | 磁盘与 Git 检查：<br/>• 旧文件已执行物理删除（Git deleted）<br/>• `domain/search/api` 目录已不存在 (`Test-Path` 返回 False) | `[明确获准的变化]` | 彻底清除旧路径冗余，避免 Spring 容器重复加载产生路由冲突。 |
| **CHK-07** | 搜索领域层依赖单向性 (DIP) | 原 `BaiduSearchService.java` [L3]:<br/>`import com.ai.guide.domain.planner.service.AmapRouteService;`<br/>直接强耦合高德路线/POI服务实现。 | 现 `BaiduSearchService.java` [L5]:<br/>`import com.ai.guide.domain.search.gateway.PoiSearchGatewayPort;`<br/>`AmapRouteService` 实现该接口，`domain.search` 对下层依赖彻底为 0。 | `[明确获准的变化]` | 领域层完全清除了对 `infrastructure`、`application`、`adapter` 的任何反向依赖，完全契合 COLA/DDD 依赖倒置原则。 |
| **CHK-08** | 应用层设计权衡（红方深入挑刺） | 控制器直接依赖领域服务与基础设施服务。 | `SearchApplicationServiceImpl.java` [L7]:<br/>`import com.ai.guide.infrastructure.planner.amap.AmapRouteService;`<br/>应用层直接编排基础设施服务。 | `[明确获准的变化]` | 详见第三节红方架构深度分析。 |

---

## 三、 红方深度挑刺与架构设计审查

### 1. 领域层 DIP 纯净化核验
- **审查事实**：
  对 `ai/src/main/java/com/ai/guide/domain/search` 目录下所有文件进行全局包导入扫描：
  - `BaiduSearchResult.java`: 仅依赖 Lombok。
  - `PoiSearchGatewayPort.java`: 仅依赖 `domain.rag.pipeline.AmapResponseNormalizer` 与 JDK List。
  - `BaiduSearchService.java`: 依赖 `PoiSearchGatewayPort`、`KnowledgeDocumentService`、`BaiduSearchResult` 与 Spring/JDK 基础设施组件。
- **挑刺结论**：
  领域层无任何反向 import `infrastructure` 或 `adapter`，原先跨领域直连 `AmapRouteService` 的坏味道已彻底消除。符合高内聚低耦合原则。

### 2. 应用层引入 `AmapRouteService` 的合理性审查
- **审查发现**：
  在应用服务实现类 `SearchApplicationServiceImpl` 中存在：
  ```java
  import com.ai.guide.infrastructure.planner.amap.AmapRouteService;
  ```
- **红方深度推演**：
  1. **为什么此处没有全部转接给 `PoiSearchGatewayPort`？**
     - 因为 `/ai/search/amap` 端点具有特殊契约：它不仅需要 `keywords` 和 `city`，还需要支持可选的 `types`（POI 类型过滤），并且要精确捕获 `PoiOutcome.status()`（如区分 INVALID 与 UNAVAILABLE），以决定是返回 200 还是 503 状态码。
     - 如果强行将 `PoiOutcome` 与全部参数挪入 `PoiSearchGatewayPort`，由于 `PoiOutcome` 本身是 `AmapRouteService` 内部封装的结果对象，会造成领域端口与具体供应商数据结构产生强绑定。
  2. **分层架构评估**：
     - 在 COLA 规范中，**应用层（Application Layer）是跨系统用例的编排者**。应用服务直接协调领域服务（`BaiduSearchService`）与基础设施服务（`AmapRouteService`），符合“应用层组织协调业务用例与适配器”的标准定义。
     - 且在单元测试中，`SearchApplicationServiceImplTest` 对 `AmapRouteService` 进行了完整 Mock，证明应用服务具备良好的解耦与可测性。
  3. **架构建议（后续演进储备）**：
     - 当前实现完全合规且无回归风险。若未来需要支持多地图供应商（例如高德/百度地图实景 POI 动态切换），可将 `searchPoi(q, city, types)` 进一步泛化抽象为独立的领域/应用网关端口。

---

## 四、 自动化测试实测验证记录

1. **搜索域切片单元测试**：
   - 命令：`.\mvnw.cmd test "-Dtest=*Search*"`
   - 结果：**11 支测试全部通过 (0 failures, 0 errors, 0 skipped，耗时 5.3s)**
   - 覆盖范围：
     - `SearchControllerTest`: 3 项（端点路由、参数默认值、503 异常序列化）；
     - `SearchApplicationServiceImplTest`: 5 项（百度编排、空参 200 围栏、成功返回、INVALID 分支、UNAVAILABLE 503 分支）；
     - `BaiduSearchServiceTest`: 3 项（空查询截断、三源多路融合、依赖异常时的优雅降级）。

2. **工程全量回归测试**：
   - 命令：`.\mvnw.cmd test`
   - 结果：**401 支用例全部通过（0 failures, 0 errors, 10 skipped，耗时 21.5s，BUILD SUCCESS）**
   - 验证：历史切片（Chat, Preferences, Trip, Planner, RAG, User）以及全局上下文未受任何负面影响。

---

## 五、 切斯特顿围栏与既有遗留问题确认

| 编号 | 涉及端点 | 原有行为与状态 | B 的继承表现 | 审查意见 |
|---|---|---|---|---|
| **ISSUE-07** | `GET /ai/search/amap` | 当 `q == null || q.isBlank()` 时，返回 HTTP 200 (`Result.success("请输入搜索关键词", List.of())`)，而不是 400 Bad Request。 | 100% 忠实保留在 `SearchApplicationServiceImpl` L38-40。 | **严防死守成功**。未私自“优化”修改状态码，保护既有前端调用方不受破坏。 |

---

## 六、 终审结论

- **四分类汇总**：
  - `[已验证一致]`：5 项（端点映射、参数与默认值、切斯特顿围栏空参返回、异常状态码映射、搜索结果多路融合与截断）；
  - `[明确获准的变化]`：3 项（控制器迁移适配层、提取应用服务门面、领域层 DIP 依赖倒置）；
  - `[回归差异]`：**0 项**；
  - `[尚未核实]`：**0 项**。
- **最终裁决**：**PASS（验收通过，准予提交与合并）**。

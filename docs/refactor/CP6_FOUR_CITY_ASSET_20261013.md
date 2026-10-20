# CP6 四城景点与知识资产核验（2026-10-13）

本板块复核北京、杭州、西安、武汉现有的各 3 条景点记录，没有扩大城市清单或写入固定坐标。下表的官方页面只用于证明名称、城市和简短简介；`verifiedFields` 均限于 `name`、`city`、`summary`。12 条记录的坐标继续留空，票价、预约、营业时段、入口坡度、无障碍与实时交通继续标为待核验。

| 城市 | 景点 | 本次核对的官方出处 |
| --- | --- | --- |
| 北京 | 故宫博物院 | [故宫博物院：总说](https://www.dpm.org.cn/about/about_view.html) |
| 北京 | 中国国家博物馆 | [国博：本馆简介](https://m.chnmuseum.cn/gbgk/gbjj/) |
| 北京 | 颐和园 | [颐和园：基本介绍](https://www.summerpalace.net.cn/about.html) |
| 杭州 | 西湖风景名胜区 | [文化和旅游部数据服务：西湖](https://sjfw.mct.gov.cn/site/dataservice/culdetails?curId=10&id=34878)；替换了原先只能证明交通管理的通告 |
| 杭州 | 浙江省博物馆孤山馆区 | [浙江省博物馆：文澜阁及孤山馆区](https://www.zjmuseum.com.cn/cn/msp/) |
| 杭州 | 中国丝绸博物馆 | [中国丝绸博物馆：章程](https://www.chinasilkmuseum.com/gwgk/info_4.aspx?itemid=8790) |
| 西安 | 陕西历史博物馆 | [陕西历史博物馆：关于本馆](https://www.sxhm.com/about.html) |
| 西安 | 秦始皇帝陵博物院 | [秦始皇帝陵博物院：概况](https://www.bmy.com.cn/about/) |
| 西安 | 西安城墙景区 | [西安曲江新区：西安城墙景区](https://qjxq.xa.gov.cn/zjqj/qyts/5df21c3a65cbd81235fc1bf1.html) |
| 武汉 | 湖北省博物馆 | [湖北省文化和旅游厅：直属单位](https://wlt.hubei.gov.cn/zfxxgk/fdzdgknr/jgjj/zsdw/202008/t20200805_2740545.shtml) |
| 武汉 | 黄鹤楼公园 | [武汉市园林和林业局：公园管理处](https://ylj.wuhan.gov.cn/zwgk/zwxxgkzl_12298/jggk_12304/xsdwszjzz_12308/202001/t20200110_726398.shtml) |
| 武汉 | 武汉博物馆 | [武汉市文化和旅游局：直属单位](https://wlj.wuhan.gov.cn/zfxxgk/fdzdgknr/jgjj/zsdw/202008/t20200827_1437246.shtml) |

`knowledge_documents.jsonl` 每城增加 1 条 `scope=CITY` 的静态知识，正文与元数据均保留对应官方链接。知识正文明确把当日预约、票价、开放时间、接驳和无障碍可用性视作未知，不将静态简介转换成动态服务保证。SQLite 查询的城市过滤在候选 LIMIT 之前执行；新增回归使用四城唯一文档 ID 检查同城可见、异城不可见。仅四条入门知识不能支持完整的当地知识问答。

若高德未返回景点坐标，规划草稿会标记 `POI_COORDINATES_UNVERIFIED`、`degraded=true` 与 `sourceStatus.coordinatesUnverified=true`；重新刷新得到有效坐标后撤销该原因码。路线与地理距离仍须真实供应商核验。此机制不生成任何城市的固定坐标，也不允许借用重庆坐标填补四城空缺。

验证：`ai` 目录运行 `./mvnw.cmd test -Dtest=AmapPlannerGatewayTest,KnowledgeCityScopeRegressionTest,KnowledgeCityIsolationIndependentTest,CityAttractionCatalogImporterTest,CityCatalogIsolationTest`。本板块没有验证实时高德结果、完整城市覆盖、真实 PostgreSQL 多实例目录更新、源页面的持续可用性或浏览器展示。

## 独立审查后的规划入口修复

目录记录的 `city` 现在优先用于规划城市隔离。四城条目的 `indoor` 以数据库 `NULL` 保存、实体映射仍为 `null`，`environment=UNKNOWN`，未核验室内属性不再参与室内场所加分。选点前对缺坐标的景点按当前目标城市和景点准确名称查询 POI，只采纳同时具备同城、同名、POI ID、合法坐标的结果；查询受规划请求的 `OperationDeadline` 限制，临时坐标仅用于当前草稿，不写回目录。失败时保留空坐标及 `POI_COORDINATES_UNVERIFIED`，不伪造路线时长或已核验状态。真实北京 JSON 导入后的回归覆盖成功多站与错城市拒绝。

后置 POI 刷新也拒绝缺少 `cityName`、异城及同名歧义结果；候选必须与景点名称或精确别名一致。选点前查询按显式提及、必去项优先，单次最多调用 `${planner.catalog-geocode.max-poi-lookups:12}` 次。此预算只覆盖选点前目录地理编码，不代表后置 POI、路线、天气调用的总上限。100 条外置目录回归验证选点前仅发起 12 次查询且优先查用户必去景点。未查询条目仍保留空坐标；若实际计划因此欠量则标记降级，不猜测路线。

独立审查补强：只有目录仍有未解析坐标且最终景点槽位实际欠量或显式必访未满足时，才额外保留 `CATALOG_CANDIDATES_UNRESOLVED`。仅有未被选中的目录候选为空坐标不构成欠量；100 条目录前 12 条成功并排满计划的测试保证不误报。后置刷新即使填好欠量计划中最终入选的站点，也不能把欠量规划标记为完整。选点前确认的 POI ID 和坐标会写入请求内的站点，后置刷新先按该身份与坐标消歧，再作同城同名等核验；若返回不同 ID 或坐标就拒绝覆盖，从而不让排程阶段的路线成本对应另一 POI。测试覆盖完整计划坐标夹具、先失败后成功但仍欠量、POI 身份与坐标漂移、同名干扰项。

`attraction_catalog_entry.source_url` 是目录的结构化出处，但尚未自动映射成行程或知识检索结果的可点击引用；四条知识正文自身包含官方链接。此引用映射仍是后续项，不能把目录 URL 的存在解释为前端已经显示官方出处。

## 根任务最终验收

独立只读复审确认欠量判定与预选 POI 消歧的最后两处补丁，没有发现阻断性新误判。根任务在 `ai` 目录独占运行 `.\mvnw.cmd clean test`，结果为 `BUILD SUCCESS`；209 份 Surefire XML 实际包含 1283 个测试用例，1250 通过、33 跳过、0 失败、0 错误。`git diff --check` 返回 0；`ai/scripts/audit-planner-hardcoding.py` 扫描 124 个生产 Java 文件，报告 0 条已定义规则的违规。扫描不能证明所有语义硬编码都已消失。33 条跳过的可选基础设施/供应商场景不属于本次全量测试的通过范围。

仍需另行验证真实高德请求、正式浏览器交互、PostgreSQL 多实例目录热更新；静态官方链接不等于实时票价、开放和无障碍信息。欠量且有未解析目录候选时，系统保守标记规划不完整，无法严格证明欠量完全由坐标缺失造成，也可能同时受营业时间或用户约束影响。

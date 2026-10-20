# CP6 真实供应商与浏览器验收

验收日期：2026-10-15。范围为当前源码构建的 Java、真实模型与高德、Chrome → Vite → BFF → Java 的受控本地链路。Java 使用临时 SQLite、专用本机 Redis；Qdrant 指向不可达测试端口，不写共享索引。没有提交、推送或部署。

## 已修复问题

- 真实高德名称与目录名称不同，导致选点前地理编码拒绝主 POI。杭州西湖、浙博孤山馆区、西安城墙的精确名称外置到城市 JSON；预选与后置补全共用同城、精确名称、合法坐标与唯一身份规则，不接受相关门口、其他馆区或同名歧义点。
- 原 V5 公交请求仅发送 `city`，实测返回 `MISSING_REQUIRED_PARAMS(20001)`；原步行响应有距离但耗时为 null。依据[高德路径规划 2.0 文档](https://lbs.amap.com/api/webservice/guide/api/newroute)，V5 公交须发送 citycode 类型的 `city1/city2`，V5 耗时在 `cost.duration`。当前单城市合同：城市名直接使用已支持的 V3 兼容请求，合法 citycode 才发 V5；POI ID 成对发送，V3 strategy 保留。解析器支持 V3 顶层与 V5 嵌套耗时、实际分段步行距离和路线折线。没有增加城市编码硬表或逆地理缓存。
- embedding 缺少凭据时原来仍外呼并等待 401/超时。现在请求前明确失败，交给既有本地检索降级；不伪造向量。补充的 Spring 构造注入问题由根任务全量回归发现并修正。
- 真实聊天回复的 Markdown 标题加下一行列表被语义测试误判。修正测试解析器并保留旧确认格式、否定与旧目的地反例；没有放宽模型含义断言。
- Windows runner 的 grep 经 cmd 转义后未选中用例。改为直接 Node Playwright CLI 与参数数组，先用相同参数 `--list` 确认精确选中两项，再实际运行。
- 上轮本地审阅导出误传前端仓库子目录，导致前端清单被错误标记删除。导出工具现拒绝非 Git 根目录；本轮使用真实 `frontend` 仓库根重新导出，替代此前前端不完整的候选包。

## 最新实际结果

Java `mvnw.cmd package`：1293 项，1260 通过、33 跳过、0 失败、0 错误，BUILD SUCCESS。212 份 Surefire XML 的实际 testcase 数与控制台一致。包 SHA-256：`adbe4d2a49f70373a66b7d3a569a4d7d1dadfca0fc69059c529fbe92817939e1`。

真实浏览器选定两项，2 passed（2.5 分钟），runner 退出 0。四城每城只发起一次规划，另外既有聊天用例包含一次重庆规划。实际结果如下（测试最低断言只要求至少一城真实路线成功，以下每城结果来自逐城原始回执核对）：

| 城市 | HTTP | 有坐标目录站点 / 预选身份一致 | 真实路线 |
| --- | --- | --- | --- |
| 北京 | 200 | 2 / 2 | 1 段 VERIFIED_AMAP，fallback=false |
| 杭州 | 200 | 2 / 2 | 1 段 VERIFIED_AMAP，fallback=false |
| 西安 | 200 | 2 / 2 | 1 段 VERIFIED_AMAP，fallback=false |
| 武汉 | 200 | 2 / 2 | 1 段 VERIFIED_AMAP，fallback=false |

北京实际完成预览不改原快照 → 确认移除原站并递增版本 → 正式保存 → 打开签发新规划会话 → 继续删除站点。该生命周期不冒称四城分别全测。

真实行程聊天三轮通过：保存前的随机代号与第一站名称在正式保存、重开后仍可正确复述，模型会话保持连续，聊天不修改行程。此前采集的六轮游客回复仅做离线语义复判（通过、0 新供应商调用），不把原 Playwright 失败改写为自动化通过。

Node 110 项：109 通过、1 跳过、0 失败；前端 typecheck/build 通过；本地导出器 5 项通过。规划硬编码脚本扫描 124 个生产 Java 文件，已定义规则违规为 0；不证明所有语义硬编码或任意自然语言泛化均已验证。

## 证据与失败记录

- 最新真实运行：`C:/Users/summer/AppData/Local/Temp/cp6-four-city-live-USplf7`。包含四份逐城响应、summary、jar hash、进程清理、三条真实聊天回复与脱敏日志。
- 原三项失败保留：`C:/Users/summer/AppData/Local/Temp/cp6-four-city-live-8CEnCm`。其中模型连接重置属于供应商故障；另外的测试格式误判、路线契约问题分别修复。六条原始回复与离线复判仍在该目录。
- Windows grep 未选中用例的启动器失败保留：`C:/Users/summer/AppData/Local/Temp/cp6-four-city-live-IWkchY`；该次未发业务供应商请求，不计入通过。
- 最新后端测试机器记录：`CP6_LIVE_SUPPLIER_BACKEND_TESTS_20261015.json`；最终浏览器摘要：`CP6_LIVE_SUPPLIER_BROWSER_20261015.json`。
- Java/BFF/Vite 与专用 Redis 均已停止，端口 56813、49310、4173、56918 无监听；临时数据库与 Redis 证据文件保留，未删除用户数据。

## 未覆盖与下一板块

本轮不验收真实 Qdrant/RAG 召回质量（隔离配置明确降级），也不保证模型或地图持续无网络故障。Qdrant 已有单独的真实集成验收，不与本轮混算。

下一板块仍为城市资产生命周期：来源链接在行程/知识结果中的结构化展示，以及 PostgreSQL 多实例目录热更新。四城各三条景点与一条入门知识只构成最小覆盖，不等于全国完整目录。Linux 生产拓扑、持久队列和 Qdrant 进程崩溃后自动重试仍是后续能力；本地交付不扩展为推送或部署。

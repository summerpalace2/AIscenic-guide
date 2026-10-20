# CP6 来源引用与城市目录热更新验收

日期：2026-10-15。范围：结构化来源从目录/知识库传递到规划与检索结果，浏览器展示及保存重开，城市目录在共享 PostgreSQL 上的并发更新。交付仅限本地审阅，不提交、推送或部署。

## 本轮实现

- 目录元数据持久化 `verified_fields`，通过 Attraction/JDBC 传递 `sourceUrl`、核验日期和字段范围。规划仅将明确核验的 `summary` 生成“景点简介”事实及官方引用；高德补充动态信息时保留该静态引用。
- 知识库增加结构化 `source_url`。旧种子仅在标题及正文仍与原始资源一致时补充缺失 URL；正文编辑清除来源 URL。检索命中与事实审核分开，普通本地/向量命中保持“待核验”，URL 只提供来源参考。
- Qdrant 文本索引将 URL 写入片段 metadata/payload，并通过 KnowledgeFragment 返回到事实和顶层引用。旧构造、无 URL 文档及旧 payload 仍兼容；历史索引需要重新同步才会携带新增链接。
- 页面仅渲染安全的 HTTP(S) 链接，转义属性，使用 `noopener noreferrer`。未知票价/预约不再默认显示免费；来源用于目录简介，不扩大到门票、开放时间或无障碍设施。
- 导入事务在读取目录前获取既有 revision 单行锁：PostgreSQL 使用 `FOR UPDATE`，SQLite 使用写锁。读取、校验、指纹比对、受管理行更新和 revision 提交在同一事务中；错误批次保留旧快照。别名缓存继续通过共享 revision 自动失效，无新增轮询缓存架构。

## 分工与独立复核

实施 A 负责来源、索引及页面；实施 B 负责事务锁及真实 PostgreSQL 双 JVM 探针，并同步旧测试 schema；审查 C 只读复核调用链和测试证据；主线程统一运行 Maven、Node、浏览器和构建，避免共享 target 并发构建。

独立审查发现并推动补齐：事务 lambda checked exception 编译阻断、动态 POI 覆盖静态引用、检索命中误标审核、向量片段丢 URL，以及未知门票的误导默认值。别名 revision/list 交错读取专项已覆盖，未为没有证据的永久脏读增加复杂重试。

## 验证结果

当前范围验收通过。最终默认 Maven `package`：1296 run / 1261 passed / 35 skipped / 0 failures / 0 errors；逐条 testcase 汇总 213 份 XML，与 Maven 汇总一致。随后新增种子来源断言单独跑 `KnowledgeCityScopeRegressionTest`：4/4 通过，未将其重复累加进全量统计。Node：111 run / 110 passed / 1 skipped / 0 failures；前端 typecheck/build 通过，交付导出器五项通过。

浏览器：1/1 通过，使用最终 JAR `8e5b52ed54fa373c7a098549e3892421b17cdd25ac6e02579eec6790396a8d99`。实际导入中国国家博物馆目录，生成规划、页面渲染安全官方链接、保存并重开后同一简介 URL 保留；核验字段仅为 summary，动态营业字段不引用该来源，门票仍提示核验。机器结果、截图及环境摘要保存在本目录 `evidence/cp6-source-catalog-20261015/browser-*`；原始浏览器运行目录为 `C:/Users/summer/AppData/Local/Temp/cp6-partial-lifecycle-YOEVa3`。

实际检查截图中的来源链接与未知门票展示。来源浏览器使用本地供应商夹具，不调用付费模型或真实高德接口；真实供应商的先前验收见 `CP6_LIVE_SUPPLIER_ACCEPTANCE_20261015.md`，不重复计入本轮。

已执行的真实 PostgreSQL 专项：33 项定向测试全部通过，包含两项独立 JVM 集成测试。两个 worker 对同一共享目录并发安装只增加一次 revision；另一个 worker 在等待行锁期间源目录更新，拿锁后安装最新内容；核验字段单独变化参与指纹、坏 JSON 保留旧快照、改名及删除使缓存失效。两个别名 matcher 是测试进程内两个独立对象，不能据此声称跨进程缓存实例已实测。

## 保留的失败证据

- 首次编译失败：导入器事务回调调用 checked exception 方法；已修正运行时包装，未跳过编译。
- 首次全量：1295 run、8 errors、35 skipped；八个错误均为三个手建测试库遗漏 `source_url`。同步夹具后重新回归；未在生产读取中吞掉缺列异常。
- 原始日志和 worker 日志保存在 `evidence/cp6-source-catalog-20261015/`，首次失败不被后续结果覆盖。

独立审查 C 已复核新语义链、旧 payload 兼容、字段级来源边界，以及真实双 JVM 探针；未发现当前范围新的阻断。临时 PostgreSQL 的随机测试 schema 已全部清理（剩余 0），专用服务已正常停止；本轮 Java/BFF/Vite/供应商及 PostgreSQL 端口均无残留监听。开发服务及业务 env 未变。

## 验收边界

- 行锁只能序列化安装，指纹不是单调版本号。所有可刷新实例须使用同一权威目录发布版本；不同目录、旧 JAR 或滚动部署资产分叉仍可能回写旧内容，本轮不宣称防止该场景。目录发布须原子替换，逐文件编辑中的混合版本也不由数据库锁解决。
- 浏览器验证使用真实 Java/BFF、临时 SQLite 和本地 HTTP 高德兼容供应商；不等同于新一轮真实外部供应商或真实 Qdrant 浏览器验收。
- 四城各三条最小景点目录与每城一条知识不代表全国完整资产；官方简介来源不背书实时运营数据。
- 静态硬编码检查只是列明规则内的扫描：规划规则无命中；全后端报告仍列出五处 Windows PDF 字体路径，不能宣称全项目零字面量。供应商端点和外置资产单独分类。
- 默认 Maven 跳过的真实基础设施测试与本次显式启用的 PostgreSQL 专项分别统计；跳过不计通过。Linux 生产拓扑、持久重试队列及 Qdrant 崩溃后自动重试不在本板块范围。

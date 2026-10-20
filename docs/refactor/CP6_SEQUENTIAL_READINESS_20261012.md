# 剩余任务逐项执行：第 1 项就绪检查

本次前置检查未执行数据库写入、Redis 写入或故障注入，也未更改开发服务配置。随后使用全新随机会话完成真实聊天测试；相关测试产生自己的新会话数据，没有读取其他用户会话。

- 本机未发现 Docker CLI、PostgreSQL/Redis 服务；WSL 未安装可用运行环境。
- 当前进程没有 CP6_IT_* 测试连接变量。
- 仓库根目录 Redis 配置的 TCP 连接可达，但应用协议 PING 探测超时；ai/.env 配置的 Redis TCP 探测超时。TCP 可达不等于认证、协议或应用连接成功，本次未判定具体原因。
- 用户随后指定 `D:/scenic-guide/ai/.env`。已核对其 `KNOWLEDGE_DB_TYPE=sqlite`，缺少完整 PostgreSQL URL、用户名、密码；它能用于本地项目与真实供应商测试，不能据此签署 PostgreSQL 验收。
- 使用该文件进行 TLS、认证、PING，只读检查一次返回 PONG、另一次 TCP 超时；远端网络有间歇性表现。本轮后续真实聊天也成功连接 Redis。
- 已核对 tools/cp6-infra/run-integration.ps1 和四项真实服务探针；不能将未启用的跳过结果算作验收通过。

第 1 项仍需完整 PostgreSQL 测试连接及专用 Redis 测试目标。之后检查测试数据库与 Redis 数据库 15、连通性，再执行迁移、双 JVM 初始化、跨实例提案撤销与已提交记录恢复探针。四项通过后仍须完成文档 INTEGRATION.md 列出的双 HTTP 实例业务流程，方可签署整个基础设施板块。

## 本轮实际修复与独立复查

1. PostgreSQL 首次 CREATE TABLE 原来发生在迁移 advisory lock 之外；现在完整初始化在同一数据源事务中先锁后执行。版本迁移参与外层事务，SQLite 保持原路径。
2. 元数据列检查原来可能误用其他 schema 的同名列；现在限制并核对当前 schema。索引检查也使用当前 schema。PG ALTER 失败直接传播并回滚，不在已 abort 的事务中继续查元数据。
3. 新测试修复前 2 项均失败（cp6-schema-before.log）；修复及补强后迁移与路线联合回归 32 项通过。独立智能体审查未发现锁顺序死锁或本轮引入的确定缺陷。真实 PG 行为仍未验证。
4. 路线任务排队/结果等待与批次等待增加共享截止时间，并记录 build、动态信息、文案等阶段耗时。子智能体实施后由另一智能体只读复查，未发现迟到结果修改返回行程。端到端请求预算尚未闭环，详见 CP6_ROUTE_WAIT_BUDGET_20261012.md。

## 后续板块证据

- 真实游客双轮城市追问通过：1 passed，23.1 秒。
- 真实三轮目的地更正和约束承接通过：1 passed，34.1 秒。北京改为杭州，甜辣并存、轮椅及禁台阶约束仍保留。仅验证会话承接，不证明持久画像或摘要压缩能力。
- 浏览器语音核查发现当前产品没有录音/播放入口和 BFF 用户代理；Java 供应商接口可用不等于浏览器产品已实现。待实施方案在 BFF docs/browser-voice-entry-review-20261012.md。
- 本轮修改后后端全量：1177 run，1163 passed，0 failures，0 errors，14 skipped，BUILD SUCCESS，日志 cp6-sequential-full.log。四项真实基础设施探针仍跳过。
- 首轮修复打包后，在全新 Java 进程、随机新用户上执行首次真实冷规划，仍返回 503；dynamic 76.043 秒、Planner 总耗时 76.071 秒，narrative 0。未预热、未重试，预览/保存/恢复因创建失败未执行。BFF docs/real-cold-plan-first-request-20261012.md 保留失败证据；这推翻了“仅路线批次等待限制已经足够”的假设，后续继续实现请求截止时间。1177 项统计对应该首轮代码，不能替代后续改动验证。

## 请求预算补修及最终回归

首次 76 秒失败之后，新增 OperationDeadline，在 createInternal 的 prepare 之前建立默认 45 秒供应商预算。高德 HTTP、缓存等待、路线批次、动态候选及文案任务传递剩余预算；拒绝过载，不同步执行 HTTP。共享缓存 follower 超时不取消 owner。没有站点时返回 504 且不创建会话；已有完成站点时保留结果，并明确标记 PARTIAL_TIMEOUT / PLANNING_DEADLINE_EXCEEDED。

前端新增部分生成提示，保存和重开时仍读取行程内的降级标记；同时移除页面写死重庆地形及平街通行的泛化建议，未核验路线不再展示“已校验”徽标。离线浏览器测试验证提示、HTML 转义和保留站点/保存入口。

另一智能体独立只读复查未发现确定阻断缺陷。预算范围仍不包含创建幂等锁等待、数据库保存、响应传输、BFF 独立 RAG；shadow/replan 未自动开启新作用域。hydration 的 CallerRuns 工作受请求剩余预算约束，但不能据此宣称独立 15 秒硬上限。详见 CP6_REQUEST_DEADLINE_20261012.md。

- 最终后端全量：1183 run，1169 passed，0 failures，0 errors，14 skipped，BUILD SUCCESS；日志 cp6-sequential-final-full.log。四项真实基础设施测试仍跳过。机器统计见 CP6_SEQUENTIAL_FINAL_TEST_SUMMARY_20261012.json。
- 最终 Java 打包成功；前端构建与类型检查通过。
- 前端选定生命周期与部分生成浏览器回归：5 passed，1 skipped；跳过的是仅真实供应商启用的三轮聊天用例，其独立真实运行结果见前文。
- 两个现有静态硬编码扫描：规划 122 文件、后端 349 文件，均 0 findings。这只证明当前扫描规则未命中，不能证明所有字面量已消除。
- 最终包的全新进程、全新 SQLite、随机新用户首次冷规划：HTTP 200，13.368 秒，7 个站点；Java 动态阶段 13.237 秒。预览 HTTP 200，6.835 秒，确认前原行程不变。确认应用超过测试默认 10 秒等待，未取得响应，尚不能判为业务失败，也不能报告完整生命周期通过。该次未触发 deadline marker，不能证明真实部分生成的保存恢复分支。证据见 BFF docs/real-cold-plan-deadline-after-20261012.md。

## 真实生命周期复核

原确认等待的 Java 日志显示 APPLY_PROPOSAL 已在 9894ms 完成；浏览器 10 秒窗口还包括 BFF 处理与传输，不能据此断言业务失败。将仅真实测试的 apply/save/open/删站等待对齐接口现有 60 秒预算，stub 仍为 10 秒，不修改业务超时、不增加重试、不削弱断言。在另一个全新进程、全新 SQLite、随机新用户下运行一次，1 passed（58.1 秒）。

- 首次创建 200 / 28.355 秒 / 4 站；预览 200 / 13.407 秒，确认前快照不变。
- 确认应用 200 / 4.161 秒 / 3 站，版本递增且选定原站确实移除。
- 保存 200 / 0.180 秒；重新打开 200 / 0.077 秒 / 3 站，签发新 session。
- 页面 session、version、days 与 /open 权威快照一致；继续删除原站成功。
- Java 总规划 28.264 秒（build 24.690 秒、dynamic 3.529 秒、narrative 0），apply 4.118 秒。
- 本次仍没有 deadline marker，因此真实部分生成后保存/恢复分支尚未覆盖。独立运行不是旧失败的自动重试；旧失败证据继续保留。

本轮自有隔离 Java/BFF/Vite 进程均已停止，未重启或停止用户开发服务。复测完整证据见 BFF docs/real-lifecycle-response-budget-20261012.md；前次 10 秒失败保留在 docs/real-cold-plan-deadline-after-20261012.md；最终预算独立审查见 CP6_REQUEST_DEADLINE_INDEPENDENT_REVIEW_20261012.md。

当前不将局部进展写成所有任务已完成。仍缺少真实 PostgreSQL 与专用 Redis 基础设施验收、完整聊天矩阵、真实部分生成的保存恢复、浏览器语音产品入口及后续城市数据覆盖。

## 下一板块追加：浏览器语音

随后已补齐 active web 语音录音/转写/取消与回复朗读入口，保持登录要求及 BFF Origin/CSRF。实施自查、主线程复读、独立浏览器验收、交叉审查依次完成；响应正文取消、录音尾帧和供应商 401 提示问题均已修复。

本板块验证：Java 14 项针对性测试、BFF 24 项、前端 16 项通过；构建/类型检查通过；独立浏览器 11 项通过（模拟麦克风/本地音频 fixture）。真实 BFF → Java → TTS/ASR 回环通过，累计 2 TTS + 1 ASR，前序浏览器工具路径错误如实保留。完整结果在 BFF docs/browser-voice-acceptance-20261012.md。

此前“语音产品入口缺失”已解决。物理麦克风/扬声器、移动端未验证；真实 PostgreSQL/专用 Redis、完整聊天矩阵、真实部分生成恢复与更多城市数据仍待完成。本次不重写之前 1183 项全量成绩为本板块最终源码的全量成绩，语音 Java 改动由 14 项专项回归验证。

## 下一板块完成：聊天追问与模式矩阵

聊天板块已完成实施、自查、独立验收及根线程复读修正。修复旧方案来源、模式切换读值顺序、取消与账号切换的迟到回执、辅助记忆与确认应用竞争、服务端模型会话在保存重开后的连续性、授权站点上下文缺失、POST JSON 传输、近期城市更正与硬约束修订保留。最后补修 SSE 文本 JSON 编码，保留空格、换行和中文。

- 最终 Java 全量：1192 run，1178 passed，14 skipped，0 failures/errors；chat-sse-final-full-regression.log。最终包 SHA256：84064CCF1ED97927A46D4A4D31FA88953532B3E939A536AE733D51CE3A466A7E。
- 根线程枚举全部 Node 测试：57 passed，0 failed/skipped；BFF chat-board-node-final.log。前端构建和类型检查通过。
- 最终受控浏览器：11 passed，1 skipped（真实供应商专属项），0 failed；frontend/cp6-chat-matrix-controlled-frozen.log。
- 真实浏览器最终独立运行：1 passed、1 failed。行程三轮保存重开及指代通过；游客六轮均取得回复，原断言错误拒绝“杭州（北京作废）”。六条原回复留档，经共用语义判定器离线复判通过；原 Playwright 失败仍保留，不改写为全绿。真实调用使用 DE1D638B0FBD2A09E7A6132A4EBD87D5807BFCD47E80B08FEB0D35EC444A0FA8 包；最后 SSE 修复由非计费的 MockMvc 与前端逐块原文断言验证。
- 两个硬编码扫描当前范围均无命中；只代表既有规则覆盖范围，不代表任意自然语言和城市资产均已泛化。

详细独立证据见 BFF docs/chat-matrix-acceptance-20261012.md；后端见 CP6_CHAT_BACKEND_MATRIX_20261012.md。页面清空明确保留模型上下文。城市参数承接已修，但知识库尚无结构化城市过滤，真实回复中仍出现被模型拒用的异城材料；知识数据严格城市隔离留待城市数据板块。真实 PostgreSQL/专用 Redis、多实例与崩溃恢复、真实部分生成后保存恢复仍未验收。本次仅交付本地可审阅版本，不提交、推送或部署。

## 2026-10-13 知识库城市隔离板块

已实现显式 CITY/GLOBAL/UNSPECIFIED、候选截断前过滤、管理端全文及归属编辑、原子更新、队列/回填归属一致性。实施自查、独立 12 项反例和主线程 HTTP 复核完成。最终 Java 1215 run / 1201 passed / 14 skipped / 0 failures/errors；Node 65 passed；最终管理浏览器 2 passed，build/types 通过。详细范围、首轮失败及环境限制见 CP6_KNOWLEDGE_CITY_ISOLATION_20261013.md。

资产仍为 218 条重庆知识；旧未标注资料需明确分类及同步，未修改共享真实索引或重启开发服务。真实 Qdrant/PostgreSQL/专用 Redis、多实例崩溃恢复、真实部分生成后保存恢复与全国知识内容覆盖仍不是已完成项。当前交付为本地审阅，未提交、推送或部署。

## 2026-10-13 部分生成生命周期板块

实施、自查、独立审查与根线程复核完成。真实Java请求执行截止时间产生部分行程，浏览器经真实BFF/Java/临时SQLite和本地延迟HTTP供应商验证生成→预览→应用→保存→重开新会话→继续删站。修复完整性标记被局部路网刷新清除、回执遗漏、页面旧路网徽标、可应用删除误分类，以及真实HTTP中文/保留字符编码；移除Java个人env路径，测试显式隔离env。

最终Java1230run /1216passed /14skipped /0failures/errors；Node69/69；浏览器1/1；build/types通过。同包61CF86F0905BE968FD97C85AFD8284573B6DA3D6DDED0C33D84E3A81D055700B。详情及失败分类见CP6_PARTIAL_PLAN_LIFECYCLE_20261013.md，机器证据CP6_PARTIAL_PLAN_BROWSER_RESULT_20261013.json。

原“真实部分生成后保存恢复”缺口在上述隔离供应商范围内闭环，不扩大为真实外部供应商或PostgreSQL/专用Redis/Qdrant、多实例崩溃恢复验收。全国知识内容仍只有重庆真实资产；BFF默认env路径及缺失Java配置示例的配置契约检查为后续项。当前仅本地可审阅，不提交/推送/部署。

## 2026-10-13 配置可移植性板块

配置实施、自查、独立审查及主线程复核完成。唯一Java示例恢复为公开契约模板，Node共享显式配置源，PowerShell不清空进程配置、不隐式初始化；工具加载后求值，生产测试开关拒绝。Node87/87，配置检查通过，Java实际main七场景符合预期，BFF真实入口临时目录启动通过；导出器4项测试通过，显式补入被Git忽略的工具和报告。

详细证据及限制见CP6_PORTABLE_CONFIG_20261013.md。Java成功启动五场景readiness200但整体health503（隔离Redis不可达），不扩大为真实基础设施验收。Java业务包未变，本轮不计作新Maven全量或UI构建通过。候选仅本地审阅；全国知识、真实基础设施/多实例、真实外部供应商及远程交付仍未完成。

## 2026-10-13 真实基础设施板块（Windows隔离范围）

用户授权后搭建独立PostgreSQL16.15及社区Cygwin真实Redis7.4.3；发现并复现首次同键并发保存导致PG25P02，最小ON CONFLICT补丁修复。真实探针10/10（6服务+4守卫）、行程相关54/54、实际双HTTP实例四检查首跑通过；PG/Redis服务强杀后的WAL/AOF恢复marker首跑通过。最终包4C1156FF566483DA997A789D619100601C46FEBBA8465B55AE541BDD61D81205；本轮未声称旧Maven全量/UI构建为新结果。

详见CP6_REAL_INFRASTRUCTURE_20261013.md及独审报告。专用服务已停止、证据保留，项目env和用户开发服务未改。该范围闭环不等于官方LinuxRedis、真实Qdrant、完整BFF多实例链和全国知识资产已验收；继续保留这些缺口。仅本地可审阅交付，无Git提交/推送/部署。

## 2026-10-13 BFF多实例用户业务链

Windows受控范围实施、自查、独立审查及根实际集群通过。共享BFF认证/draft/workspace；Java共享continuation与原子apply receipt；修复Open PG冲突、跨JVM指纹、聊天完成/清空边界、内部时段codec、BFF token参数接线和前端迟到保存归属。模型/地图为本地HTTP供应商，非生产语义验收。

最终Java专项249/249含13真实PG/Redis；默认全量1252run1229pass23skip0failure/error。最终Node112/112无skip（真实Redis显式启用），build/typecheck/配置检查通过。完整2BFF+2Java实际HTTP同轮15阶段通过；包FA7F6E9721878B957A0C73E68196059BDFF33D9FC0FE0C42508AAC71C15AF050。详情及首失败分类见CP6_BFF_MULTI_INSTANCE_20261013.md和独审BFF_MULTI_INSTANCE_INDEPENDENT_ACCEPTANCE_20261013.md。

专用服务已停、随机schema清理、Redis无FLUSH且数据证据保留；业务env/用户开发端口未改。本轮三类状态共享不包括feedback/运营指标。剩余真实Linux拓扑/Qdrant、全国知识资产、真实供应商质量与远程交付仍未完成，仅交付本地审阅候选。

## 2026-10-15 状态更新：Qdrant、四城与真实供应商

后续独立核查已完成真实 Qdrant 管理知识发布/回填修复验收，含 10 项真实 PostgreSQL/Qdrant 集成测试；见 `CP6_REAL_QDRANT_BACKEND_20261013.md`。四城各三条静态景点与每城一条知识已核对，城市隔离、动态定位与 POI 身份锁定已修；见 `CP6_FOUR_CITY_ASSET_20261013.md`。这些后续证据替代上文对应的未完成状态，不抹去当时限制。

2026-10-15 最新 Java 全量与打包：1293 run / 1260 pass / 33 skip / 0 failure / 0 error。真实浏览器两项通过：四城各两个定位站点、每城一条已核验路线；北京实际完成预览→确认→保存→打开→再编辑；真实行程聊天保存重开后三轮代号/指代连续。Node 109 pass / 1 skip，前端 typecheck/build 与导出器五项通过。详见 `CP6_LIVE_SUPPLIER_ACCEPTANCE_20261015.md`，保留原供应商和测试工具失败，不将跳过项计为通过。

下一板块：结构化来源链接展示、PostgreSQL 多实例城市目录热更新。四城最小目录不代表全国完整资产；Linux 生产拓扑、持久队列和 Qdrant 崩溃后自动重试仍属后续能力。仅本地审阅，不提交、推送或部署。

## 2026-10-15 来源引用与城市目录热更新板块

本板块实施自查、独立只读审查与主线程复核已完成。字段级官方简介链接在规划、动态高德补充、保存重开及浏览器展示中保留；知识 source_url 结构化传入本地/向量检索参考引用，检索成功不再等同正文已核验。PG 导入先锁 revision 再读取共享目录，同事务发布；跨 JVM 并发/等待锁后改源/坏批次保持旧状态专项通过。

默认 Java package 1296 run / 1261 pass / 35 skip / 0 failure/error；显式真实 PG 定向 33/33 含两项独立 JVM 测试；追加种子断言 4/4（不重复累加）。Node 110 pass / 1 skip；typecheck/build、浏览器保存重开来源 1/1、导出器 5/5 通过。同包 SHA256 `8e5b52ed54fa373c7a098549e3892421b17cdd25ac6e02579eec6790396a8d99`。详细证据及首轮编译/测试失败见 `CP6_SOURCE_CATALOG_ACCEPTANCE_20261015.md`。

临时 schema 清理，隔离服务已停。保留限制：旧 Qdrant 索引需重新同步才有 URL；不同目录或旧 JAR 的滚动部署防资产回退未覆盖，实例须共享权威资产发布版本；两个别名缓存对象验证不扩大为跨进程缓存实测。全国完整资产、Linux 生产拓扑、持久重试队列仍为后续能力；本地审阅交付不等于远程部署。

## 2026-10-15 城市目录发布版本防回退

版本化完整外置快照、文件哈希与数据库发布版本门禁实施/独审/主线程验收通过。默认 Java package 1302 run /1265 pass /37 skip /0 failures/errors；最终显式 SQLite+真实 PG 专项7/7，发布工具1/1及四城CLI smoke通过。详见 `CP6_CATALOG_RELEASE_ACCEPTANCE_20261015.md`，同包 SHA256 `5c55cc47d425e6b6512544c28f94020f7cffe2293b274ce18f06e9ed743cf412`。

上一轮不同目录回退风险在启用 manifest、所有刷新程序支持新门禁的范围内闭环。旧无版模式兼容但没有该保护；旧二进制可绕过Java校验，升级前须停旧refresh。完整目录不能只放单城补丁；未验证发布事务进程崩溃或Linux生产部署。专用PG已停、schema已清理，仅本地审阅。

## 2026-10-15 知识索引持久重试与进程恢复

复用文档状态记录TEXT/FILE/DELETE意图、租约及退避，提交请求原子落库，普通异步/手动/恢复共用门禁；先SQL归档/新修订再处理向量。实施/独审/主线程验收完成：默认package1310run1269pass41skip0failure/error；真实PG/Qdrant等定向43/43，随后补充恢复集成5/5（4项重复不累加），覆盖Qdrant写入成功但SQL提交前强杀、自然租约到期后第二JVM恢复。生产代码未因补充测试改动。同包SHA256 `c9b4ae9f83315600e9bb36308312da54f0e6bac9627c32e8464ef98a6021e514`。

知识领域此前“内存任务丢失/无自动重试”缺口在新协议+显式开启调度的范围内闭环；默认未启用业务调度，不扫描历史seed收费索引。详见 `CP6_INDEX_RECOVERY_ACCEPTANCE_20261015.md` 与操作合同。保留供应商at-least-once成本、定时器长期运行、共享文件多机前提、文件物理清理best-effort、旧程序先停止及旧模式未知边界。专用服务/worker清理，随机schema/collection剩余0；仅本地审阅，Linux生产拓扑与全国完整资产仍未完成。


## 2026-10-16 语音增量边界复审

语音入口此前已验，本轮补WAV真实格式/60秒门禁、TTS表单POST与供应商容量、pagehide清理，并简化complete尾帧交接。Java专项25/25，默认package1315run1273pass42skip0failure/error；Node112pass1skip；Chrome fixture13/13，types/build通过。同包真实TTS1+ASR1通过，首次隔离启动0外呼失败保留。旧逻辑Chrome对照同样通过，不宣称修复已实证Chrome60秒故障。详见CP6_VOICE_BOUNDARIES_ACCEPTANCE_20261016.md。系统字体候选5处保留为基础设施平台探测字面量；物理设备/移动端、Linux拓扑、全国完整资产及远程交付仍未完成。仅本地审阅。


## 2026-10-16 PDF字体适配板块

两条PDF链默认共用classpath嵌入中文字体，移除OS候选与STSong/Helvetica回退；提供可选PDF_FONT_FILE，修损坏字体解析句柄泄漏。专项42/42；defaultpackage1322run1280pass42skip0failure/error；实际BootJAR生成3类PDF、FontFile/ToUnicode/中文提取与视觉通过；静态scan360Java0findings。独审通过。详见CP6_PDF_PORTABILITY_ACCEPTANCE_20261016.md。保留首轮1error及日报Poppler两条告警，不扩大为Linux、全字形/emoji或浏览器下载验收。仅本地审阅。
# 2026-10-16 整体验收补充

本轮总体裁决及原始结果见 [CP6 整体验收](CP6_OVERALL_ACCEPTANCE_20261016.md)。默认 Java 1322 run / 1280 passed / 42 skipped；显式真实基础设施 32/32；最终 Node 145/145；受控浏览器 31 passed / 7 skipped；多实例 15 阶段通过。实际 Java 的部分规划、来源链接及偏好—预览—确认—保存—PDF 下载—重开组合测试通过。

真实模型/高德浏览器原始运行 2 passed / 1 failed，匿名轮次失败由目的地文本判定器误判导致，原 6 条回复离线复核的 12 项检查通过；未重新付费跑成全绿。新增前端异步账号归属、同账号读写竞争和 PDF 迟到下载修复，独立复审及最终回归通过。详细范围、跳过项及未验证边界以本轮报告为准。仅本地可审阅交付，未提交、推送或部署。

# 2026-10-16 架构优化验收补充

本轮范围及最终证据见 [架构优化验收](ARCHITECTURE_OPTIMIZATION_ACCEPTANCE_20261016.md)。共享手工/AI 变更规则、应用层手工鉴权/CAS、旧偏好入口退役、生产地址/存储配置、前端真实条件展示及相关边界已完成。最终默认 Java 1359 run / 1317 passed / 42 skipped，Node 164 run / 163 passed / 1 skipped，受控浏览器 33 passed / 7 skipped；类型/构建和新 JAR 实际 Java 拒绝边界及保存/PDF/重开组合通过。部分规划与来源链接实际 Java 流程也通过。未把本轮受控供应商验收当成付费供应商或真实 PostgreSQL/Redis 多实例重验；仅本地审阅交付。


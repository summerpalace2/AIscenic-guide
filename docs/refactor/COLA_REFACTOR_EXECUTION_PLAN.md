# 后端渐进重构执行与子智能体验收计划

版本：1.0；日期：2026-10-09；执行状态更新至 2026-10-12：**CP0–CP5 已按各自范围完成验收（CP3 为单实例语义；CP4 按 §3.5 明确退出条件限定范围验收，Open P2 follow-up 已定点关闭；CP5 六域统一验收见 `CP5_FINAL_ACCEPTANCE_REPORT_20261011.md`）；CP6 冷检出、架构门禁和独立复核完成，完整范围与全国化数据边界见 `CP6_PORTABLE_DELIVERY_ACCEPTANCE_20261012.md`**。

本轮范围是明确后端职责与状态边界，保持已确认的产品行为。首先完成局部调整这一条纵向调用链，再扩展聊天及其他业务域。本次制定文档不等于授权发布、推送、生产数据迁移或开展付费供应商压力测试。

## 1. 文档定位与当前起点

本文是本轮任务拆分、派发和检查点的执行入口，沿用既有 S0–S8 编号，不建立第二套架构目标。

- [重构设计总纲](../COLA_REFACTOR_OPTIMIZATION_PLAN.md)：目标、职责和 A01–A25 验收场景的设计依据。
- [业务不变量](../baseline/BUSINESS_INVARIANTS_SPEC.md)：业务契约参考。文中的测试行号、覆盖声明和历史统计执行前均须核验；用户最新要求与当前有效契约优先。
- [已有试点报告](STEP1_STEP2_STEP3_ACCEPTANCE_REPORT.md)：StopPlan / AdjustmentPreview 加站试点、MemoryRepositoryPort 仓储试点的历史交付证据，不能外推为全领域迁移完成。

### 1.1 当前事实与限制

2026-10-09 的源码检查发现：规划应用入口主要转发领域服务；领域层仍包含 JDBC、Redis、模型 SDK 等实现；核心会话/行程大量使用 Map；提案可从 Redis 恢复，但对话 epoch、待补答及部分幂等回执在进程内；五个领域包存在源码依赖环。依赖环不代表已证实的 Spring Bean 循环或运行死锁。

现有架构守卫在同日的前序架构评估中执行 6 项，0 失败、0 错误、0 跳过。它只证明已有规则通过，不能证明业务等价、领域技术隔离或多实例恢复通过。

| 仓库 | 实际执行根目录 | 制定本文时的 HEAD，仅供定位 |
| --- | --- | --- |
| Java | `D:/scenic-guide`；Maven 在 `D:/scenic-guide/ai` 执行 | `46b1653e81050e3cdd9f23506b327bc6676bcbd8` |
| BFF | `C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home` | `6eb2a07a98fc55eb51c051b57934badddc828bd6` |
| Frontend | BFF 根目录下的 `frontend`；源码在 `web`，package.json 在 `frontend` 根 | `6dd081da50078d39cd76337c018cd5cb4042dd18` |

执行时必须重新读取完整 SHA。当前三个仓库均有未提交修改，HEAD 不能代表当前源码；**不能只 checkout 上表 SHA 就声称复现本轮工作区**。S0 需记录 dirty diff、未跟踪文件清单和文件 hash，保留用户改动。

### 1.2 已有工作如何承接

| 阶段 | 当前定位 | 本轮处理 |
| --- | --- | --- |
| S0 | 有历史基线和不变量文档 | 刷新真实调用清单、工作区身份和测试映射，不照抄旧通过数 |
| S1 | 加站策略已有类型化试点 | 保留并扩展到所选用例；不重新制造 StopPlan / AdjustmentPreview |
| S2 | TravelMemoryService 已提取仓储实现 | 核验事务和画像边界；作为其他领域迁移样板，不宣称全域无 JDBC |
| S3–S7 | 存在零散加固，尚无完整阶段完成证据 | 按本文切片继续，逐项登记 |
| S8 | 未作为当前交付目标 | 条件阶段；本轮不默认增加跨实例无缝恢复 |

## 2. 目标、业务权威与禁止扩大的范围

继续使用模块化单体和当前技术栈。adapter 负责协议、application 负责用例顺序与提交协调、domain 负责业务规则、infrastructure 实现存储与外部能力。Spring 注解、合法枚举和配置化业务窗口不机械视为违规。

| 内容 | 权威与约束 |
| --- | --- |
| 用户身份与资源归属 | 服务端验证后的身份；不能由请求体 userId 替代 |
| 行程 revision、正式 trip ID | Java 仓储的提交结果；客户端不能自动代取最新版本绕过冲突 |
| 意图、提案、epoch、取消和追问队列 | Java 用例拥有转换权；BFF / UI 仅保留展示及关联标识 |
| 记忆与画像 | 正式记忆是来源，画像是带来源的派生结果；不能反向覆盖安全事实 |
| POI、路线、开放信息 | 网关返回的来源事实；缺失不能变成零耗时、可用候选或成功 |

本轮保持单实例的安全边界：丢失 epoch 后旧提案必须拒绝应用并提示重新预览。S8 才讨论恢复 epoch、队列及幂等结果的跨进程权威协议。新增持久化或改变失效行为属于单独的数据/功能变更，不能混入纯端口提取。

不夹带微服务拆分、框架升级、全仓包名整理、算法权重调整和新城市策略。已确认且属于切片范围的旧缺陷可单独修复并登记；疑似旧缺陷需给复现和预期依据，不以模型意见擅改产品行为。

## 3. 总执行顺序与检查点

依赖顺序：`CP0 → CP1 → CP2 → CP3 → CP4 → CP5 → CP6`；S8 独立列为条件检查点。每次只派发一个可独立验收的实施切片。

| 检查点 | 对应阶段 | 主要交付 | 退出条件 |
| --- | --- | --- | --- |
| CP0 基线可信 | S0 | 当前源码身份、入口/状态读写图、测试到不变量映射 | 关键文件可追溯，测试非空；未跟踪测试的携带方式明确 |
| CP1 提案边界可替换 | S3 | 提案存储端口及当前实现隔离，生命周期约定 | TTL、取消、重启失效、Redis 不可用与并发关键路径保持 |
| CP2 预览用例收口 | S1 扩展 + S4 前半 | 预览/澄清应用用例、核心输入输出类型、HTTP 适配 | 预览不改行程；追问不丢目标；旧 wire 字段兼容 |
| CP3 确认生命周期收口 | S4 | 确认/取消/续接提交编排、统一状态协调点 | 版本、幂等、归属、并发、失败重试和剩余队列完整 |
| CP4 保存与边缘兼容 | S4 保存/打开 + S5 | 保存/打开/恢复用例和 BFF 契约清单 | 稳定正式 ID；无空保存自增；前后端快照/版本对齐 |
| CP5 聊天与其他域扩展 | S3 模型端口 + S6 | 按业务域独立迁移技术实现、拆聊天协作者 | 每域职责与回归独立通过；不得以规划通过代替其他域验收 |
| CP6 可携带交付 | S7 | 架构门禁、三库版本组合、冷检出验证和最终报告 | 同一版本组合可复现声明的测试；未验证环境明示 |
| CP8 跨实例连续性 | S8，条件启用 | 持久状态与跨节点一致性方案、真实双实例验证 | 真实重启/切换/并发/故障测试通过，否则不声明支持 |

### 3.1 CP0：冻结可审查基线

实施任务：

1. 记录三库 HEAD、Git 状态、完整 diff 和关键未跟踪文件 hash。备份只包含相关文本和测试数据，不采集密钥或真实用户数据库。
2. 追踪 `PlannerController → PlannerApplicationService → PlannerService → PlanAdjustmentService → 仓储/提案` 的真实路径，记录 BFF conversation / preview / apply 的委派入口。
3. 列出 session、revision、proposal、epoch、pending、remainingActions、applyReplays 的读写点、TTL、取消和失败处理。
4. 将第 5 节场景映射到具体测试方法与 fixture。测试名存在不等于已覆盖场景。
5. 形成 `CP0_BASELINE.md` 与 `CP0_ACCEPTANCE_MATRIX.md`；首次主智能体验证当前核心测试与架构守卫，作为后续对照。

退出条件：无来源不明的基线；缺失测试列为待补；测试/fixture 被版本控制跟踪或有明确的可携带补丁。不能重置用户工作区来制造“干净基线”。

### 3.2 CP1：先隔离提案存储，不改变恢复承诺

允许范围：`ProposalStore`、它的调用方、拟新增端口/实现及相关测试。建议端口名 `ProposalRepositoryPort`，名称是目标而非现存能力。

实施顺序：

1. 固定 get / put / remove、绝对过期时间、序列化、Redis 不可用的现有语义。
2. 在领域能力边界定义端口；将 Caffeine、Redis、序列化和异步写入放到 infrastructure。
3. 让调用方依赖端口，通过启动装配选择实现。原 `ProposalStore` 如暂保留，只作兼容适配，不形成第二个状态权威。
4. 不同时移动追问算法、改变 TTL、替换缓存策略或增加 DB 状态表。发现异步写入/删除竞态时单独给复现并修正，记录行为变化。
5. 补充端口契约测试与重新构造服务后的旧提案拒绝测试；不能只测同一对象 clear 后再 get。

退出条件：迁移范围内的领域实现不再直接操作 Redis/Caffeine；现有缓存降级可解释；旧 epoch 不可重建为公共 INITIAL；过期/取消提案不能重新变成可应用。

### 3.3 CP2：预览与追问形成独立应用用例

允许范围：规划应用层、预览/澄清的实际编排、兼容转换和相关测试；不改确认提交协议。

1. 先提取预览用例，将加载会话、验证身份/版本、调用意图解析/策略、保存提案和组装用例结果放入 application。
2. domain 保留时段解析、槽位续接、范围保护、候选约束和合法状态转换规则；外部 I/O 经已有或本切片新增的能力端口调用。
3. 显式传递操作者/会话上下文，逐条减少领域规则对 ThreadLocal 的隐藏依赖；兼容入口可暂保留 UserContext 读取，不一次改全仓鉴权。
4. 引入或复用强类型预览命令、业务结果。HTTP 状态映射放 adapter；原响应字段及错误码先保持兼容。
5. 优先在此路径扩展 DayPlan / TripDraft 等类型；LLM、旧 JSON 和存储边界保留转换层。未知字段、absent/null/false/空字符串、稳定 ID 不得因类型化丢失。
6. 原 PlannerService 入口可委派新用例；没有调用迁移与回归证据前不删除。

退出条件：应用层确实承担编排；新领域结果不含 HTTP 状态；预览不变更行程；短答继承旧目标而完整新命令能取代旧任务；无候选不是可确认成功。

### 3.4 CP3：确认、取消和追问续接共享生命周期协调

这是本轮首个完整业务闭环检查点。完成 CP1 / CP2 后实施，不把 Preview / Apply / Cancel 分给多个智能体同时改。

1. 将 Apply 和 Cancel 的编排迁到应用层，共享同一个会话状态协调点。不得各自新建锁/epoch Map，削弱原有串行和校验边界。
2. 用明确业务模型表达待澄清、待确认、已应用、已取消、已过期及被新版取代。可通过 revision/epoch 推导的状态不必全部新增持久字段。
3. 保持提交校验顺序：所有权 → 请求身份/幂等匹配 → revision → 提案归属/有效期/epoch → 选项与硬约束 → 仓储 CAS → 回执/剩余任务处理。
4. 说明哪些改变与行程写入处于同一数据库事务，哪些只是缓存/内存后处理。数据库提交、缓存移除与 HTTP 回执不是天然的全局事务。
5. 对“DB 已提交但响应丢失”“提交后缓存失败”“取消先完成”“apply 先完成”分别定义可观察结果。不会重复写入是必需条件；重启后是否可返回相同回执按单实例恢复边界明确，不能承诺未实现的持久幂等。
6. 固定站、授权时段、完整营业区间和物理冲突仍由规则校验；force 只能处理允许的软提醒。

退出条件：屏障控制的竞争测试覆盖两种先后顺序；版本只增加一次；拒绝时数据库不变；应用第一项后剩余任务不丢失；晚到预览不能重新激活已取消提案。

### 3.5 CP4：保存、打开、恢复与 BFF 契约

1. 逐个提取 Save / Open / Restore 应用用例，保持正式 trip ID、无变更保存、归属与 CAS 语义。
2. 打开/恢复返回的 session、trip 和 version 必须属于同一服务端快照。保留本地未提交修改时使用已有备份生命周期，不将旧页面快照拼接到新会话。
3. 验证备份失败不覆盖原编辑内容、不谎报成功，备份有用户可见恢复/丢弃入口，恢复后仍走合法服务端同步。
4. 盘点 BFF 的真实端点和旧调用者。当前主规划路径已委派 Java，不能为了“去重”删除不存在的重复算法；只迁移经调用证据确认的冗余职责。
5. 保持 POST/CSRF、身份桥接、版本错误及响应字段契约；前端校验账号、工作区、代次和版本后应用响应。

退出条件：保存→打开→局部调整→保存→重载完成；空保存不自增；删除冲突采用显示版本；旧工作区/旧账号响应不污染当前页面。涉及 UI 的路径必须有真实交互证据，源码字样检查不替代它。

### 3.6 CP5：聊天与其他领域整体收口

CP5 是一个整体检查点，按下表依赖关系分阶段实施，但中途不把任何单行宣布为 CP5 子项验收通过。每阶段可做构建和针对性回归来控制改动风险；只有所有范围完成后，才冻结统一候选，由架构审查、反例验收和主智能体验收共同裁决 CP5。

| 切片 | 具体职责划分 | 必须保持的行为与验证 |
| --- | --- | --- |
| 聊天/模型调用 | ChatApplicationService 保留用例编排，提取上下文构建、摘要调度、检索协调、模型调用和流式输出适配；通过能力端口隔离 SDK | 长会话预算、硬约束保留、身份上下文、SSE 断开/超时/取消、并发压缩；历史与画像不串号 |
| 餐饮/景点 | SQL、种子加载、POI 供应商实现外移；匹配、时间间隙和路线约束留领域 | 甜食口语等价、跨城市动态中心、无候选、路线缺失、完整营业区间、固定站保护 |
| 用户/授权 | 账号查询写入、密码编解码、令牌提供能力隔离；认证用例协调归属和提交 | 注册冲突、登录兼容升级、撤销、越权、失败无部分写入；不改变现有角色权限 |
| RAG/知识 | 文档持久化、检索/重排供应商、索引更新与缓存失效边界明确 | 删除后不可再读脏知识、重排降级不固化、并发更新、分页限额与超长输入 |
| 统计/PDF/配置 | 查询与渲染实现外移，容量/权限/配置规则保留 | 统计区间、导出上限、管理权限、配置并发写入及保存重读 |
| 共享能力/迁移 | 地理、分类资产、语言规则按明确所有者解环；梳理 KnowledgeDbConfig 迁移职责 | 不把所有依赖搬进 common；已有 schema_migration 台账保留；迁移重复/中断/恢复可验证 |

CP5 实施全程另设硬编码审查清单，最终扫描在上述业务实现完成之后、CP5 统一验收之前执行。扫描覆盖运行时代码与资源文件，并按用途分类：城市/地名/品牌/食物词根/人名/经纬度/固定钟点/营业与时长策略/搜索半径/供应商默认值分别核对是否应进入 taxonomy、业务配置或外部能力；协议字段、算法边界、错误码和测试 fixture 可保留，但必须能说明其不属于可变业务资产。命中项逐条给出路径、行号、调用链与处置结论，不能以简单 grep 结果直接定性，也不能以 0 命中替代对动态配置/隐式默认行为的核查。

#### CP5 整体推进清单（开发追踪，不构成分项验收）

| 工作域 | 当前状态 | 目标关闭证据 |
| --- | --- | --- |
| Chat：模型、上下文、摘要、检索协调、流式输出 | 六域统一验收通过；模型 capability、历史/摘要/偏好存储端口、上下文/检索/流适配协作者已落地 | 组件边界及组装入口、流取消/超时、上下文身份隔离、摘要游标与硬约束回归，见 CP5 总报告 |
| 餐饮/景点：仓储、种子、POI、taxonomy、时空约束 | 六域统一验收通过；目录/种子/POI/taxonomy/地理能力已迁移或外置；缺少时间锚点时保留“待安排” | domain 不直接持有 JDBC/SDK；taxonomy/城市/坐标/餐饮分类来源清晰；跨城市和无候选回归，见 CP5 总报告 |
| 用户/授权：仓储、密码和令牌能力 | 六域统一验收通过；账号仓储、密码编码、JWT 签发/验证/撤销与管理员初始化均经能力端口 | 注册/登录/兼容升级/撤销/越权和失败无部分写入回归，见 CP5 总报告 |
| RAG/知识：存储、切块、查询、重排、索引和缓存 | 六域统一验收通过；文档 SQL/文件/索引/重排及派生缓存失效边界已落到端口和 infrastructure adapter | 删除/更新并发一致性、错误降级、长度和分页上限回归，见 CP5 总报告 |
| 统计/PDF/配置：查询、渲染、容量和权限 | 六域统一验收通过；在线状态、统计查询、PDF 渲染和配置仓储均已外置 | 授权、数据范围、容量边界及配置并发保存回读回归，见 CP5 总报告 |
| 共享能力/迁移：地理、分类/语言规则、KnowledgeDbConfig | 六域统一验收通过；`domain.ai` 供应商中立模型能力、taxonomy owner、地理 provider、schema 初始化/迁移职责已明确 | 单一 owner、无重复策略源、schema_migration 可重复/中断恢复，见 CP5 总报告 |
| CP5 统一硬编码审计及总验收 | 已完成；全后端 345 个 production Java 文件及 18 个主资源资产扫描，JSON/JSONL 全通过；重点代码 122 文件、13 类规则，0 未分类命中；全量 clean suite 通过 | 扫描清单与限制见 `CP5_HARDCODE_AUDIT_20261011.md`；总裁决见 `CP5_FINAL_ACCEPTANCE_REPORT_20261011.md` |

实施顺序按 Chat → 餐饮/景点 → 用户/授权 → RAG/知识 → 统计/PDF/配置 → 共享能力推进；若调用图证明依赖更合适，可调整顺序，但必须仍完成全部行。各行测试是开发回归，不另行发布“子项验收通过”。

SQLite 与 PostgreSQL 方言、持久化格式、画像排名权重等改变须建立各自迁移/功能卡片。没有运行 PostgreSQL 就不得写两种数据库均通过。

### 3.7 CP6：架构门禁与可携带交付

1. 扩充现有 ArchUnit：新增领域代码不能直接引入 JDBC、Redis、网络/模型客户端；新增领域结果不绑定 HTTP；按能力边界检查模块依赖。先禁止新增，再按切片收敛存量。
2. 对必要例外记录类/依赖、理由、负责人、目标阶段和复查条件。不能用全包永久白名单将新问题隐藏；不以“所有 Spring 类都禁止”替代职责判断。
3. 三库分别交付明确版本与补丁组合；新建干净 checkout 复现测试与构建。新关键测试不能仅留在被忽略的 work/scripts 中。
4. 若性能是动机，先以相同 fixture、环境和样本数测量调用数、token、SQL 和延迟；没有对照不声明提速。
5. 代码回滚与数据兼容分开演练。撤回切片不能回退已提交 revision 或删掉用户数据。

退出条件：最终集成版本通过相关行为回归、架构门禁与冷检出验证；业务与架构证据分别列出；真实供应商、浏览器、PostgreSQL、多实例的执行状态不混算。

## 4. 子智能体分工与验收思维

### 4.1 四个角色

| 角色 | 责任 | 交付与限制 |
| --- | --- | --- |
| 主智能体 | 拆分范围、冻结基线、分配文件、集成、复现争议与最终裁决 | 不直接把 A/B/C 自述当完成证明；亲自复跑关键路径 |
| A 实施 | 完成一个切片、必要回归和兼容处理 | 给 diff、原始日志、行为变化、未验证项；不能自验自批 |
| B 架构审查 | 从调用链核对职责、技术依赖、权威、事务/锁和类型边界 | 默认只读；给具体位置与影响，不替 A 同时修改共享文件 |
| C 反例验收 | 从需求和不变量构造独立失败、并发和生命周期场景 | 先写期望与反例再读 A 的测试；结果必须观察状态与副作用 |

主智能体 + A + B + C 共四个槽位。共享目录下 A 实施时 B/C 可盘点旧契约，但最终评审必须针对冻结后的同一候选版本。资源不足时 B/C 顺序执行，仍分别出具结论。

子智能体默认共享工作目录；新建分支不会隔离文件、target 或数据库。并行写入必须使用独立 worktree/checkout，或严格限定不重叠文件并由主智能体串行集成。不得同时在同一 Maven target 中运行测试。

### 4.2 审查者必须遵守的判定方法

1. **先问预期从哪里来。** 用户要求、已确认业务契约、当前状态约束是判据；实施测试的断言不能自动成为判据。
2. **从真实入口追到提交。** 类名、端口名和 package 变化只是线索；必须核对实际调用、数据源、锁/事务与结果写入。
3. **验证失败后的状态。** HTTP 200 不足以证明成功；HTTP 409 不足以证明无副作用。检查最终站点、版本、提案、队列和写入次数。
4. **找能推翻结论的反例。** 例如缓存恢复但 epoch 丢失、DB 成功但回执丢失、同版本不同快照、取消后迟到写入、extras 覆盖核心键。
5. **区分结构与行为。** 包依赖变好但追问失效是回归；行为保持但仅移动同一团逻辑，不能宣布职责重构完成。
6. **控制并发顺序。** 用 latch/barrier 明确制造取消先完成和应用先完成；多发请求且没有异常不是并发正确证据。
7. **允许没有新发现。** 不按发现数量考核，不把风险猜测包装成漏洞，也不把零发现写成全项目无缺陷。

### 4.3 一次完整验收循环

`主智能体任务卡 → A 实施 → 冻结候选 → B 审查 + C 反例 → 主智能体核实 → A 定点返工 → 重冻版本与复验 → 主智能体裁决`

冻结标识至少包括 HEAD + dirty patch hash + 新增文件 hash。B/C 报告必须声明检查的是哪一版；检查后关键文件变化即重新评审受影响部分。不得将旧候选的通过报告附到新 diff 上。

缺陷登记字段：编号、切片、发现者、预期依据、位置、触发步骤、实际/期望结果、最终副作用、证据、修复范围、复验版本、状态。推测/建议单列；误报保留核验理由，不计为已修复漏洞。

## 5. 首条纵向流程的验收矩阵

下表是待执行要求，不表示当前每项均有覆盖。CP0 必须补充具体 test 方法、fixture、运行层级和证据路径。

| 编号 | 输入/交错 | 必须观察的结果 | 最低证据层级 |
| --- | --- | --- | --- |
| V01 | 生成局部预览 | session.trip / revision 不变，可保存临时提案，正式行程无写入 | 行为 + 仓储副作用 |
| V02 | 缺目标后补答地点；随后纠正时段 | 补槽保留目标/保护条件；完整新命令替换旧意图 | 行为 |
| V03 | “甜食”“甜食的”与跨城市请求 | 结构语义一致；搜索来源/城市正确；无候选有真实原因 | 规则 + 网关契约 |
| V04 | 当前方案 A 与多轮前旧 tab | 当前动作进入预览；旧动作验证归属/版本，不重复整篇规划 | 契约 + 实际 UI |
| V05 | 相同幂等凭据重试；同键不同请求 | 相同有效请求至多一次写入；不同请求拒绝；回执行为符合约定 | 行为 + 仓储 |
| V06 | 旧 revision / 他人 proposal / 已过期 proposal | 拒绝且 DB 无副作用，不代取最新版本提交 | 行为 + 仓储 |
| V07 | 真取消与“不要取消” | 真取消使旧提案失效；否定取消保留合法提案 | 行为 |
| V08 | cancel 完成后 apply；apply 完成后 cancel | 已取消不提交；已应用不被取消撤回，结果不误报 | 可控并发 + 最终状态 |
| V09 | 取消后旧预览计算/缓存写入晚到 | 旧提案不可重新应用，当前 revision 不被旧响应覆盖 | 可控并发 |
| V10 | DB 已提交但 HTTP 回执丢失；缓存故障 | 重试不重复加站，错误结果不谎报未提交；恢复边界明确 | 故障注入 + 仓储 |
| V11 | Redis 不可用；新进程仅恢复 Redis 提案 | 降级可解释；无当前 epoch 的旧提案拒绝并可重新预览 | 端口契约 + 新服务实例；真实进程证据另记 |
| V12 | 固定站、跨营业区间、路线缺失、force | 未授权内容保留；硬冲突不可绕过；未知耗时不当零 | 规则 + 用例 |
| V13 | 组合任务逐项确认、跳过和取消剩余 | 已执行项不重复，剩余队列与原始序号准确 | 行为 |
| V14 | Map/类型往返、未知 extras、null/false/数值 | wire 语义保持，核心字段不可被 extras 覆盖，ID 稳定 | 序列化契约 |
| V15 | 保存/打开/恢复后继续变异；备份失败 | 正式 ID 稳定，页面与服务端快照一致，原编辑不被错误覆盖 | 临时 DB + BFF + 实际 UI |
| V16 | 切账号/工作区后旧响应到达 | 归属重验，旧回执/旧流不更新当前页面 | 契约 + 实际 UI |

可复用的现有资产：PlanAdjustmentServiceTest、ConversationContinuationRegressionTest、ContinuationCorrectionBoundaryTest、CompoundTimeAndCancellationTest、CancellationProposalLifecycleTest、ProposalStoreAndChatContextTest、CombinedLogicRegressionTest、StopPlanRoundTripTest、PlannerDraftRestoreTest、TripServiceTest、TripOwnershipTest、MemoryRepositoryPortContractTest，以及 Node 的 planner-bridge-contract、draft-restore-route、draft-backup-restore 和 planning-loop 测试。**文件存在不代表上述 V 项已全部覆盖。**

## 6. 可执行验证入口与证据规范

### 6.1 Java 核心回归示例

在 `D:/scenic-guide/ai` 的专用验收进程执行。测试类均已核对存在；环境变量、JDK 和 provider 替身在运行前核验。下面命令是后续执行入口，本次制定文档没有运行该整组测试。

```powershell
$refactorDb = Join-Path $env:TEMP ('refactor-' + [guid]::NewGuid().ToString('N') + '.db')
$refactorTestArgs = @(
  'test', '-DfailIfNoTests=true',
  '-Dknowledge.database.type=sqlite', "-Dknowledge.database.url=jdbc:sqlite:$refactorDb",
  '-Dspring.ai.openai.api-key=refactor-test-placeholder',
  '-Dspring.ai.openai.base-url=http://127.0.0.1:1',
  '-Damap.web-service.key=', '-Damap.web-service.base-url=http://127.0.0.1:1',
  '-Dalibabacloud.dashscope.api-key=',
  '-Dalibabacloud.dashscope.embedding-url=http://127.0.0.1:1',
  '-Dbailian.rerank-url=http://127.0.0.1:1',
  '-Dbaidu.api-key=', '-Dbaidu.secret-key=', '-Dqdrant.api-key=',
  '-Dspring.data.redis.host=127.0.0.1', '-Dspring.data.redis.port=1',
  '-Dqdrant.host=127.0.0.1', '-Dqdrant.port=1',
  '-Dtest=PlanAdjustmentServiceTest,ConversationContinuationRegressionTest,ContinuationCorrectionBoundaryTest,CompoundTimeAndCancellationTest,CancellationProposalLifecycleTest,ProposalStoreAndChatContextTest,CombinedLogicRegressionTest,StopPlanRoundTripTest,PlannerDraftRestoreTest,ColaArchitectureGuardTest'
)
& ./mvnw.cmd @refactorTestArgs
if ($LASTEXITCODE -ne 0) { throw '核心回归失败' }
```

这些覆盖参数降低误用真实服务的风险，不构成完整网络隔离证明；测试中使用 mock/本机 stub，必要时在隔离环境禁外网。Redis 不可用的端口降级测试与真实 Redis 恢复测试分别执行，不把端口 1 的拒连当真实 Redis 验收。全量回归沿用参数并移除 `-Dtest=...` 选择项，由主智能体独占执行。

### 6.2 Node / Frontend

在 BFF 根目录执行。用明确枚举避免 PowerShell glob 未展开或漏掉 pages/entities 的测试。

```powershell
$refactorNodeTests = @(
  Get-ChildItem -LiteralPath server -File -Filter '*.test.mjs'
  Get-ChildItem -LiteralPath scripts -File -Filter '*.test.mjs'
  Get-ChildItem -LiteralPath frontend/web/src -Recurse -File -Filter '*.test.mjs'
) | Sort-Object FullName -Unique | ForEach-Object { $_.FullName }
if ($refactorNodeTests.Count -eq 0) { throw '测试集为空，不能验收' }
node --test @refactorNodeTests
if ($LASTEXITCODE -ne 0) { throw 'Node 回归失败' }
node --check server/index.mjs
if ($LASTEXITCODE -ne 0) { throw 'BFF 语法检查失败' }
npm run build --prefix frontend
if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
```

源码存在但未跟踪/被忽略的测试须在 CP0 标注；冷检出验证需要能携带相同测试与 fixture，不能只依赖当前电脑。每个受影响组应有非空清单；上面的总数非空不能替代组覆盖检查。

### 6.3 证据包与统计

每切片在 `docs/refactor/evidence/<切片ID>/<运行ID>/` 保存文本证据：版本/差异身份、命令、环境、开始结束时间、退出码、测试清单、原始日志、结果、副作用和未验证项。不存在的脚本/流水线写“待建设”，不得编造运行日志。

- Surefire 只统计本轮新增或实际刷新的报告；记录运行前后的报告清单/hash，排除遗留 XML。重复运行分开记录，不相加宣称覆盖增加。
- 统计分别给 total / passed / failed / errors / skipped；不能同时说“全部通过”又隐藏失败或跳过。动态/参数化用例按实际测试身份记录，报告类数不等于用例数。
- 单元 mock、临时数据库、真实服务集成、浏览器、多实例分别列证据层级；健康检查 HTTP 200 不能替代业务链路验收。
- 新测试必须能对错误实现报错；重要回归保留修复前失败与修复后通过的同一输入。纯重构验证旧/新同 fixture 的可观察结果。

## 7. 派发提示词

以下模板交给内部子智能体，不为这些子任务擅自创建用户侧新聊天。占位内容由主智能体派发前填写，不能让 A 自行选择全项目范围。

### 7.1 智能体 A：实施

```text
任务：执行 COLA_REFACTOR_EXECUTION_PLAN.md 中的 <CP/切片ID>。
先读取当前 AGENTS、任务卡、实际源码和冻结基线 <身份/证据路径>。
允许修改：<文件/能力清单>；禁止修改：<相邻切片/其他智能体文件>。
保持：<具体 API 字段、身份、revision、提案 TTL、追问和固定站契约>。
本切片目标：<一条可验证的职责迁移>。部署目标：单实例；不扩展跨实例恢复。
复用已有端口、模型和测试；发现旧缺陷另列复现，不夹带未知行为变化。
完成 <V编号/现有及待补测试>，使用独立测试库，不并发写共享 target。
交付 diff、测试命令/退出码/原始日志、行为变化、未验证项与回切方法。
不要自行宣布验收通过；完成后冻结修改并通知主智能体安排 B/C 审查。
```

### 7.2 智能体 B：架构审查

```text
只读审查候选 <身份> 的 <切片ID>，不要修改 A 正在拥有的文件。
从实际入口追踪：应用编排、领域规则、技术实现、身份与状态权威、锁/事务和结果映射。
检查是否只是把原逻辑搬到新类，是否实际消除了本切片指定的依赖。
检查引入端口后生产装配和测试装配是否同样成立，失败路径是否绕过事务。
每个问题给位置、触发条件、预期依据、实际影响、证据与最小修正建议。
区分已证实缺陷、回归、设计建议和未验证风险；不考核发现数量。
给出范围内通过/不通过/待验证结论及其限制，不宣称全后端无问题。
```

### 7.3 智能体 C：反例验收

```text
验收候选 <身份> 的 <切片ID>。先从需求和 V矩阵写出反例与期望不变量，再看 A 的测试。
至少覆盖本切片涉及的正常、失败、归属/版本和生命周期边界；涉及并发时用可控交错。
观察最终 DB/行程/revision/proposal/epoch/剩余任务及写入次数，不能只断言响应文案。
新增测试仅在获分配的隔离目录/worktree，不改 A/B 文件；测试资源不得与主构建共享。
给输入、fixture、运行层级、命令、退出码、最终副作用和可复现证据。
不能执行的必跑项标待验证；不把 mock、源码字符串检查或健康检查当真实端到端。
发现问题报主智能体裁决，由 A 定点修复，之后针对新候选复验。
```

## 8. 主智能体裁决、回切与进度记录

阶段记录状态仅允许：`未开始 / 实施中 / 待独立验收 / 返工中 / 已验收 / 阻塞`。当前状态应从本文下表开始，不从历史“全部闭环”措辞推导。

| 检查点 | 当前状态 | 本次文档交付之后的动作 |
| --- | --- | --- |
| CP0 | 已验收 | 基线、状态图和测试映射记录于 `CP0_BASELINE.md`、`CP0_ACCEPTANCE_MATRIX.md` |
| CP1 | 已验收（单实例语义） | 见 `CP1_ACCEPTANCE_REPORT.md`；真实 Redis 与多实例验证仍未完成 |
| CP2 | 已验收（有限预览用例切片） | 见 `CP2_ACCEPTANCE_REPORT.md`；澄清状态策略和兼容 `ServiceResult` 仍在领域层，HTTP wire JSON 矩阵列为后续测试缺口 |
| CP3 | 已验收（单 JVM / SQLite 语义） | 见 `CP3_ACCEPTANCE_REPORT.md`；重启、真实 Redis/PostgreSQL、socket 丢包与多节点未验证 |
| CP4 | 已验收（按 §3.5 明确退出条件限定范围；Open 回执丢失/补偿 DELETE 失败 follow-up 已由 CP4.5 关闭；PostgreSQL/真实 Redis/多实例未验证） | `CP4_5_OPEN_IDEMPOTENCY_CLEANUP_REPORT.md`；本轮安全定向 Java 49 项、Node 144 项与 Vite build 均通过；完整 Maven 套件未取得结果 |
| CP5 | 已完成整体统一验收 | 六域实现完成；全后端硬编码扫描、统一反例/架构审查与全量 clean Maven 测试均完成。限制见 CP5 总验收报告；未验证环境不扩大为已验收。 |
| CP6 | 完成（最终冷检出及独立复核） | 见 `CP6_PORTABLE_DELIVERY_ACCEPTANCE_20261012.md`；三库当前 dirty patch 已绑定到隔离冷候选；Java 1135、BFF 11、前端 5 项通过；Java/BFF 架构门禁、Vue 类型检查、生产构建和依赖审计通过。前端跨城默认值已修复；精选城市 POI 静态数据仍是全国化边界，真实数据库、多实例和外部供应商未验证 |
| CP8 | 不在当前范围 | 有明确跨实例连续性要求后单独立项 |

主智能体判定“已验收”前必须同时满足：

1. 业务行为证据通过；明确获准的行为变化单列；范围内必跑项没有未验证缺口。
2. 架构目标有实际调用和依赖变化证据；不存在靠白名单、删除测试或放宽断言制造的通过。
3. B/C 检查的是最终候选；阻塞缺陷已复现、定点修复并复验。
4. 主智能体亲自复跑该切片关键正常/失败路径，并在 CP3 / CP4 / CP6 执行受影响集成回归；全量测试不必每改一行重跑。
5. 回切路径成立，关键测试和文档可随交付携带，剩余限制与部署目标一致。

结果按“已验证一致 / 明确获准的变化 / 已发现回归 / 尚未验证”报告；必跑项待验证则阻塞阶段完成，可选真实供应商或未启用 S8 不冒充通过，也不阻塞无关切片。

每阶段回切只撤回本阶段代码和装配，不 `git reset --hard` / `git clean` 清除用户改动。涉及新写出格式或 schema 时，先证明旧版可读或提供前滚方案；不能仅回退代码后读取不兼容数据。

**CP0–CP4 已完成；CP3 仅验收单 JVM/SQLite 语义，CP4 按 §3.5 明确退出条件限定范围验收通过。CP4.5 已增加稳定 Open 操作键、BFF 本地保存后 ACK、未确认 session TTL 清理与失败/回执丢失故障注入；代码层 P2 follow-up 关闭，尚未验证 PostgreSQL、真实 Redis、多实例和进程崩溃恢复。CP5 已启动，首卡仅处理 ChatClient 模型调用能力端口；上下文、RAG、记忆与 SSE 业务策略不在 CP5.1 范围内。此前测试误连项目持久数据库及其处置见 `CP4_4_R10_OPEN_COMPENSATION_ACCEPTANCE_REPORT.md`；累计裁决见 `CP4_FINAL_ACCEPTANCE_REPORT_20261010.md` 与 `CP4_5_OPEN_IDEMPOTENCY_CLEANUP_REPORT.md`。**

## 9. 本文自身验收与经验适用性

### 9.1 初始派发的 CP0 任务卡（已完成，供追溯）

```text
智能体 A：执行 docs/refactor/COLA_REFACTOR_EXECUTION_PLAN.md 的 CP0，仅刷新基线与验收资产映射。
工作根：D:/scenic-guide；核对 BFF 与 Frontend 的实际目录和当前版本。
允许写入：docs/refactor/CP0_BASELINE.md、CP0_ACCEPTANCE_MATRIX.md 及本切片文本证据目录。
业务代码、现有未提交改动、真实用户数据库均不得修改；不执行 git reset/clean，不推送。
列出三库完整 HEAD、dirty diff 身份、关键新增文件 hash 与未跟踪测试可携带性。
追踪 PlannerController 至仓储/提案的入口以及 session/revision/epoch/pending/幂等结果读写点。
将 V01–V16 映射到具体测试方法、fixture、环境和副作用；无法覆盖的列待补，不虚报通过。
向主智能体交付测试清单，由主智能体独占运行本计划核心 Java 回归、架构守卫及涉及的 Node 契约。
交付后冻结文档版本，等待 B 的边界复核与 C 对测试映射的独立抽查。
完成 CP0 不自动实施 CP1；由主智能体根据验收结果派发下一切片。本任务卡已于 CP0 阶段完成。
```

### 9.2 本文验证范围

本次仅制定执行文档。已核对项目目录、当前三库 HEAD、已有文档、列出的测试类和 package.json 入口；计划中的完整业务回归、故障注入、浏览器及多实例测试尚未执行。

参考个人原始经验：[行为等价重构评审边界](C:/Users/summer/Documents/ggy的自动化流程/experiences/2026-10-04-session-行为等价重构评审边界.md)、[审计反例与容量保护验收](C:/Users/summer/Documents/ggy的自动化流程/experiences/2026-10-06-session-审计反例与容量保护验收.md)。采用为文档方法：固定候选、按可观察行为分类、共享目录不等于隔离、排除遗留测试报告；其中未经实作验证的建议仍按建议看待。当前项目执行效果需在各检查点记录，不因写入规范就宣称已有效。

经验使用记录：[20261009-a2e698098f8d](C:/Users/summer/Documents/ggy的自动化流程/wiki/tasks/20261009-a2e698098f8d.json)。本地经验链接是维护溯源，缺失时不作为执行重构的前置依赖。

### 9.3 执行记录

- CP0、CP1 的验收依据分别见 `CP0_ACCEPTANCE_MATRIX.md`、`CP1_ACCEPTANCE_REPORT.md`。
- CP2 冻结候选记录于 `outputs/20261009-CP2-candidate.json`（workspace 外）；独立架构与红队报告分别见 `CP2_ARCHITECTURE_REVIEW.md`、`CP2_REDTEAM_REVIEW.md`，主智能体验收见 `CP2_ACCEPTANCE_REPORT.md`。
- CP2 主智能体在共享项目工作区重跑定向 Maven 回归：95 项通过，0 失败、0 错误、0 跳过。外部 Qdrant 不可用，测试按配置使用本地降级；本轮未运行完整 Java 套件、真实 Redis、多实例、MockMvc JSON 矩阵或浏览器端到端。
- CP2 属于有限 Preview 用例切片。追问/澄清策略仍由 `PlanAdjustmentService` 持有，旧 `ServiceResult` 到 typed outcome 的内部转换尚未清除；该边界按渐进迁移保留，不能表述为全量解耦。
- CP3 最终候选 R7 的主智能体验收见 `CP3_ACCEPTANCE_REPORT.md`；R7 的架构与红队复审分别见 `CP3_R7_ARCHITECTURE_REVIEW.md`、`CP3_R7_REDTEAM_REVIEW.md`。
- 主智能体在共享 Java 工作区重跑 CP3 十个定向测试类：146 项通过、0 失败、0 错误、0 跳过，`BUILD SUCCESS`。R7 仅增加测试断言与报告；响应丢失通过 servlet filter 模拟，SQLite 为隔离数据源。
- CP3 对 CAS 提交后异常明确“不重复写入但不保证恢复成功回执”；proposal remove 失败仍完成 continuation/replay；复合任务存储快照验证首项删除、第二项延后添加及 pending ID 续接。pending JSON 的第二项目前以 `clarificationQuestion` 文案呈现，不是结构化复合动作 API。
- CP3 未验证 socket 级丢包、进程崩溃、真实 Redis/PostgreSQL、重启和多进程；下一步按 §3.5 进入 CP4，不与其他检查点并行实施。
- CP4.1 最终候选验收见 `CP4_1_ACCEPTANCE_REPORT.md`；实施、架构、红队报告分别见 `CP4_1_IMPLEMENTATION_REPORT.md`、`CP4_1_ARCHITECTURE_REVIEW.md`、`CP4_1_REDTEAM_REVIEW.md`。主智能体独立复跑 7 类，55 项通过、0 失败/错误/跳过；实施者隔离副本 clean run 同为 55 项全绿。
- CP4.1 只迁移 Java V1 Planner Save 编排，保留 server session snapshot、session+revision 幂等、formal Trip ID/no-op/CAS 链接和 wire status/body；BFF、前端、Open/Restore、schema 未改。`PlannerSaveResult.serviceStatus` 仍是整数状态的非阻断残留；BFF/browser/真实数据库服务及完整 UI 流程仍待后续切片验证。
- CP4.2 最终候选与主智能体验收见 `CP4_2_CANDIDATE.json`、`CP4_2_ACCEPTANCE_REPORT.md`；架构与红队复审分别见 `CP4_2_ARCHITECTURE_REVIEW.md`、`CP4_2_REDTEAM_REVIEW.md`。冻结身份哈希匹配，主智能体在共享工作区复跑 8 类测试 61 项全通过，隔离副本 clean test 同样通过。
- CP4.2 将 Java Planner Open 编排迁入 application use case，保留 owner-scoped 单次 Trip 读取、旧 facade 与 HTTP wire 行为；未改 BFF、前端、Restore 或 schema。审查发现约束/偏好同时降级时诊断标记被覆盖，是迁移前既有 P2，未夹带修复；实现报告的“均保留”表述需按验收报告收窄。真实 HTTP、BFF/browser、PostgreSQL/Redis 与跨进程语义尚未验证；Open 请求级幂等仍无稳定客户端键。
- CP4.3 Restore R2 最终候选及主智能体验收见 `CP4_3_R2_CANDIDATE.json`、`CP4_3_ACCEPTANCE_REPORT.md`；R2 架构与红队复审分别见 `CP4_3_R2_ARCHITECTURE_REVIEW.md`、`CP4_3_R2_REDTEAM_REVIEW.md`。冻结 hash 匹配，隔离副本与主智能体共享工作区复跑均为 74 项全通过。
- CP4.3 将 Java Planner Restore 编排迁到 application，保留 actor/session/capability、planner revision、formal ID/version、备份校验、CAS 与旧响应契约；R2 同时修正 Restore 绕过 `trip.plan.*` bean 配置的既有不一致，并补充低限额、guest wrong-capability 与 CAS 冲突覆盖。formal Trip read→session CAS 跨聚合窗口、guest claim 先于后续校验、Spring 属性覆盖 E2E、BFF/browser/真实数据库仍分别列为限制。
- CP4.4 BFF 路由契约子切片见 `CP4_4_BFF_ROUTE_ACCEPTANCE_REPORT.md`。最终候选 R8 的 B 架构复审与 C 红队复审均通过限定范围；主智能体独立复跑 26 项 Node 回归、JS 语法与 diff 检查。Restore 现有双版本及完整 trip 快照迟到回执门禁；测试仍是 VM/受控依赖，不代表真实服务并发。
- CP4.4 Open 候选 R5 修正了 USER 会话空 capability 契约；R6/R7/R8 在复审中分别发现并修复 workspace 持久化半状态、同步 getter 暴露未提交值、draft 中间可见导致并发 GET 提前注册等问题；R9 在最终候选中一并收口，并增加队列头重基线以保留排队 PUT 的最新对话状态。R9 限范围验收见 `CP4_4_R9_ACCEPTANCE_REPORT.md`，架构与红队复审见对应报告。R5–R8 均为被后续候选取代的返工候选，不作为最终验收记录。
- CP4.4 R11 发现的硬刷新阻断已由 R12 定位为异步认证恢复晚于 `TripsPage` 单次挂载检查，修复账号监听、请求代次防串号、错误重试呈现与 Vite TS/JS 解析顺序；隔离真实浏览器硬刷新及登出/登录均显示已保存行程。R11 当时列出的回执丢失/补偿 DELETE 失败、PostgreSQL/真实 Redis/多实例未验证，后由 R13 按 §3.5 退出条件作累计裁决。详情见 `CP4_4_R11_LIVE_INTEGRATION_ACCEPTANCE_REPORT.md`、`CP4_4_R12_TRIP_LIST_REFRESH_RECOVERY_REPORT.md` 与 `CP4_FINAL_ACCEPTANCE_REPORT_20261010.md`。
- 2026-10-10：前端 stale-Open R4 作为**限范围子切片**通过主智能体复跑和独立复核，验收报告见 `CP4_4_FE_STALE_R4_ACCEPTANCE_REPORT.md`。前端 61/61、BFF 与 scripts 组合 81/81、Java Save/Open/Restore application 19/19、Vite production build 成功；复核者确认候选哈希一致，范围内未发现阻断缺陷。复核列出的限制包括无专门的冲突历史恢复入口及无网络超时；它们不等于整个 CP4 已完成。
- 本轮修复 `scripts/workspace-lifecycle.test.mjs` 的 VM 测试夹具：加载 `persistFormalTripWorkspace` 时遗漏其生产依赖 `formalTripWorkspaceWrite`，导致两项真实源码路由测试以 `ReferenceError` 失败；加载该依赖后组合套件 81 项全通过。该修复只改测试装配，不改生产行为。
- 2026-10-10 浏览器补充验收（当时状态）：在内存 fixture 与合成账号中完成登录→规划→保存→打开→移除站点→保存→刷新→重开；正式行程版本 2 与 UI 仅含洪崖洞，路径通过。过程中发现登录完成后的规划页未重绘，修复 `frontend/web/app.js` 的 auth `finally` 渲染，并由该浏览器流程实测通过。补充复跑前端构建成功、前端 61/61、BFF+scripts 81/81。该修复尚不属于 R4 冻结候选；浏览器没有连接真实 Java 服务或持久数据库。后续 R11 补齐真实 Java↔BFF 集成，R12 补齐硬刷新恢复；累计状态以 R13 裁决为准。
- 2026-10-10 R12：真实浏览器硬刷新 `/trips` 时发现认证恢复异步完成晚于 `TripsPage` `onMounted` 的一次性检查，导致没有发起列表请求并永久保留 `savedTrips=null`。改为监听账号变化加载列表，隔离账号与迟到响应，失败时展示重试入口；Vite 配置优先解析 TS，避免忽略的旧 JS 产物遮蔽源码。硬刷新与登出/登录均显示已保存行程；Vite build 通过，Node 回归 141/141。`vue-tsc` 仍因 `ChatPanel.vue` 8 处既有字段类型错误失败。报告：`CP4_4_R12_TRIP_LIST_REFRESH_RECOVERY_REPORT.md`。CP4 浏览器阻断关闭；累计阶段状态由 R13 最终裁决。
- 2026-10-10 R13：按 §3.5 逐项核对累计证据并独立复跑 Java 43 项、Node 141 项与 Vue Vite build，均通过；R11/R12 的真实 Java↔BFF 与隔离浏览器流程覆盖完整主链路。CP4 按明确退出条件限定范围验收通过。Open 回执丢失/补偿 DELETE 失败仍可能遗留孤儿 session，列为 P2 follow-up；不宣称对网络丢包可安全重试。PostgreSQL、真实 Redis、多实例和进程崩溃恢复不在该门槛中且尚未验证。详见 `CP4_FINAL_ACCEPTANCE_REPORT_20261010.md`。
- 2026-10-10 CP4.5：稳定客户端操作键与 Java 幂等键绑定 formal trip；Java 创建 `OPEN_PENDING` session，BFF 原子保存 draft/workspace 后确认；回执丢失时同 key 得到相同 Java 与 BFF session；补偿失败或进程在确认前退出时，后台按 TTL 清除未确认且未修改的 session。安全定向 Java 49 项、Node 144 项、Vite build 均通过。完整 Java 套件因 SpringBoot 测试上下文会加载项目配置 SQLite 而停止，未报告全量通过；未运行真实 socket 故障、PG/Redis/多实例/进程崩溃实验。报告：`CP4_5_OPEN_IDEMPOTENCY_CLEANUP_REPORT.md`。
- 2026-10-10 CP5 启动：CP5.1 任务卡收敛为 `ChatApplicationService` 三处 ChatClient 调用（用户 SSE、结构化问答、后台摘要）抽取能力端口；首先冻结调用行为与 adapter 边界，再做一项可逆实现。上下文构建、RAG 检索、Redis 历史/画像、压缩调度和 SSE 业务事件先留原位。任务卡：`CP5_1_CHAT_MODEL_BOUNDARY.md`。
- 2026-10-10 CP5 首个实施组成部分：新增 domain `ChatModelGatewayPort` / `ChatModelMessage` 与 `SpringAiChatModelAdapter`，应用服务三条模型路径改为网关调用；聊天及 Controller 回归 21 项、架构守卫 14 项通过。用户明确要求 CP5 整体完成后统一验收，因此上述结果只作为开发阶段回归证据，不单独裁决 CP5.1。领域契约不暴露供应商 SDK，但应用上下文仍使用 Spring AI `Message`。记录见 `CP5_1_IMPLEMENTATION_REPORT.md`。
- 2026-10-10 CP5 持续实施：餐饮 JDBC 目录读取抽至 `DiningVenueCatalogPort` / `JdbcDiningVenueCatalogAdapter`；聊天历史 Redis 读写抽至 `ChatHistoryStorePort` / `RedisChatHistoryStoreAdapter`，领域服务保留 owner 校验与响应映射。对应开发回归 55 项通过（含 Chat、餐饮、历史隔离和 ArchUnit）。种子数据读取、其余聊天 Redis/模型职责、景点/用户/RAG/统计等领域仍未完成；这不是 CP5 验收，统一硬编码审计仍留待所有实现完成后执行。
- 2026-10-10 CP5 RAG 持续实施：新增 `RerankProviderPort` 与 `BailianRerankAdapter`，将 DashScope/Bailian HTTP 请求、响应解析和供应商配置从 `RerankService` 移出；缓存、异步超时与失败不缓存的降级行为仍由领域服务负责。当前开发回归 40 项通过，覆盖重排降级/缓存、适配器异常与边界、聊天历史、餐饮与架构守卫；知识文档存储、索引更新与缓存失效尚未迁移，不构成分项验收。
- 2026-10-10 CP5 Chat 持续实施：`ContextCompressionService` 改为通过 `ChatModelGatewayPort` 调用摘要模型，并通过 `ChatSummaryStorePort` 访问摘要与压缩游标；Redis 键和存储异常兼容逻辑已落到 infrastructure。聊天历史、摘要状态与 Rerank/餐饮边界的开发回归 57 项通过。`SlotTrackingService` 仍直接使用 Redis，聊天上下文职责拆分尚未完成，不构成 CP5 验收。
- 2026-10-10 CP5 继续实施：用户账号 JDBC 查询、密码编码与 JWT 签发分别抽为 domain capability port，由 infrastructure adapter 提供实现；`SlotTrackingService` 已通过用户偏好存储端口访问 Redis。景点目录 SQL/行映射移至 `JdbcAttractionCatalogAdapter`，并新增领域层禁用 JDBC 的 ArchUnit 规则；在线状态 Redis 经 `OnlinePresenceStorePort` 隔离，TTL 改为配置项。用户域 31 项定向测试（含 22 条架构规则）通过；景点、Amap 网关与架构守卫 31 项定向测试（含 23 条规则）通过。以上仅为开发回归，CP5 仍在实施，完整聊天编排、RAG 知识库存储、统计/PDF/config 及共享 taxonomy 尚未关闭。
- 2026-10-10 CP5 持续实施：`ChatApplicationService` 改依赖无 Redis/Spring AI 类型的 `ChatConversationMemoryPort`，Redis 适配仍保留原异步写入、压缩游标、历史恢复与安全约束语义；统计域 SQL 由 `AnalyticsRepositoryPort` / `JdbcAnalyticsRepositoryAdapter` 承接，日报 PDF 仅消费结构化日报数据。日期边界、周/月分桶、PDF 快照独立性、聊天安全恢复和架构守卫联合回归 73 项通过（含 26 条架构规则）。本记录仅为开发回归，Knowledge 持久化/文件存储、配置仓储、完整 Chat 协作者拆分、共享 taxonomy 和最终硬编码审计仍待实施。
- 2026-10-10 CP5 用户/授权边界继续实施：登出应用用例改依赖 `JwtSessionTokenPort`，签名解析与 Redis/本地撤销逻辑由 infrastructure adapter 协调；管理员 bootstrap 从 domain 配置移到 infrastructure，账号查询/创建和密码编码均通过既有端口。管理员初始化、应用登出、账户安全和 28 条架构规则定向测试共 32 项通过。未开始 CP5 总验收。
- 2026-10-10 CP5 RAG 持续实施：新增 `RerankCacheInvalidationPort`，知识正文更改和文档归档后联动清空派生重排缓存；仅改标题等不触发清缓存。知识并发/索引生命周期、运行策略、降级排序与 28 条架构规则共 39 项定向回归通过。知识文档 JDBC/file IO 尚未迁移，此项不构成 RAG 或 CP5 验收。
- 2026-10-10 CP5 跨域开发回归：Chat 会话记忆、聊天安全恢复、analytics 范围/PDF、景点/Amap、账号/登出/bootstrap、Knowledge 缓存失效及全部 28 条当前 ArchUnit 规则合并复跑，94 项通过。此为本轮实现的定向合并测试，不等同 Java 全量测试，也不作为 CP5 验收。
- 本轮经验使用记录：[`20261010-d82ec10bfb1f`](C:/Users/summer/Documents/ggy的自动化流程/wiki/tasks/20261010-d82ec10bfb1f.json)。采用 BO1 作为 BFF 原子提交边界约束；该经验页不覆盖 Java 远端会话幂等/TTL，未用于证明网络故障恢复。dashboard 更新脚本报错 `'kind'`；任务收据已关闭，未声称看板同步。
- 2026-10-10 CP5 持续实施：将语言模型能力/消息收敛为有界 `domain.ai` provider-neutral port，并把聊天、规划意图提取/叙事和 RAG 查询分解接入该端口；移除相关 application/domain 对 Spring AI 消息类型与 ChatClient 的直接依赖。聊天流、压缩游标、历史安全、意图/叙事与分类回归及 29 条当时架构规则合计 99 项通过；随后新增精确禁依赖规则并单独复跑 30 条架构守卫通过。聊天协作者拆分、检索协调、流适配、知识库持久化和其余 CP5 范围仍在实施，本记录不构成分项验收。

### 9.3 执行记录：CP0 与 CP1（2026-10-09）

CP0 已按 dirty baseline 验收；其限制和测试覆盖缺口见 `CP0_ACCEPTANCE_MATRIX.md`。CP1 最终候选由主智能体核验冻结身份，B 架构审查与 C 红队均针对该候选给出通过意见；四轮被红队发现的并发/生命周期缺陷均先修复，再冻结和复审。主智能体在共享 Java 工作区重跑 CP1 定向回归：92 项通过、0 失败、0 错误、0 跳过，`BUILD SUCCESS`。完整结论、哈希、测试范围、回切方法及未验证边界见 `CP1_ACCEPTANCE_REPORT.md`。

该通过结论仅覆盖单实例 proposal 生命周期语义。真实 Redis、跨进程/多节点顺序、生产 Spring 环境及全量 Java 套件仍未验证；需要时按 CP8 或后续集成检查点另行验证，不将本地 mock 测试表述为真实基础设施验收。




### R9 执行记录（2026-10-10）

- 最终候选 `CP4_4_R9_CANDIDATE.json` SHA-256：`cc45ccfa8c7f0d461290e08f42810da37093a375a91dc41e7bfe5ca5acdf44d4`。主智能体独立复跑 34 项定向 Node 回归，0 失败；JS 语法和 diff 检查通过。
- B 架构审查 PASS；C 红队指出未单独覆盖两次 Open 同时排队和运行中服务端到端，未发现阻断缺陷。主智能体按限范围接纳，缺口保留在验收报告。
- R9 仅验收 BFF 本地原子 Open、并发 GET 可见性及队列头 workspace rebase。未验证真实 BFF/Java 服务互调；Java Open 先创建的远端 session 在 BFF 本地持久化失败后仍可能成为 orphan。
- CP4 累计验收已由 R13 按 §3.5 通过；Open 网络丢包与补偿失败的 P2 follow-up、未验证部署环境和 `vue-tsc` 诊断均已明确记录。CP5 尚未开始，后续如进入 CP5 应按 §3.6 逐切片派发，不把本次验收误读为所有环境或故障模式均已验证。

### 9.4 CP5 整体统一验收记录（2026-10-11）

- CP5 严格按整体检查点验收；实施期间的测试均视为开发回归，未分别宣布子域验收通过。六域统一验收报告：`CP5_FINAL_ACCEPTANCE_REPORT_20261011.md`。
- 最终 Java clean suite：1129 项，0 失败、0 错误、10 跳过，`BUILD SUCCESS`；使用隔离 SQLite。范围限制和外部服务未验证项见总报告。
- 硬编码审计报告：`CP5_HARDCODE_AUDIT_20261011.md`。全量扫描覆盖 345 个 production Java 文件和 18 个主资源文件；14 个 JSON/JSONL 校验有效，0 未分类命中。重点代码扫描覆盖 122 文件、13 类规则，0 违规；结论不等于任意语言/城市完全泛化证明。
- 全后端扫描发现旧报告遗漏的百度网页搜索/favicon URL 及固定百度语音 endpoints；已移至端口/基础设施 adapter 与可覆盖配置，测试 YAML 增补隔离 URL。主智能体随后重新运行 clean suite、两套静态扫描并更新候选指纹 `5804ac7c0921681e9a9a535cc2837bc2a1ab5babf363902584af54f2859e52b6`（HEAD `46b1653e81050e3cdd9f23506b327bc6676bcbd8`；dirty worktree）。
- 时间锚点缺失的动态新增站点仍标记“待安排”，不伪造时刻；本轮不宣称这类站点已自动完成时间排程。PostgreSQL、真实 Redis、多实例、进程崩溃恢复、真实供应商和 CP6 clean checkout 仍未验证。
- 经验使用任务记录见 `20261011-61cb75727114`；收据是否关闭以本条更新和任务 JSON 为准，不以 dashboard 同步作为前提。

### 9.5 CP6 架构门禁与可携带交付（2026-10-12）

- CP6 冻结的三库 HEAD、dirty patch 与 untracked manifest 哈希、隔离冷候选位置和实测结果统一记录于 `CP6_PORTABLE_DELIVERY_ACCEPTANCE_20261012.md` 及候选 `cp6-test-attestation.json`。
- final-08 冷候选中 Java clean Maven suite 1135 项（0 失败、0 错误、10 跳过），BFF 11 项、前端 5 项通过；Vue 类型检查、Vite production build、`npm audit`（0 vulnerabilities）通过。Java ArchUnit 54 条守卫和 BFF 13 模块架构门禁通过。独立审查发现并促成修复前端规划偏好默认“重庆”注入；偏好生成回归测试通过。Java planner 静态硬编码扫描 122 文件/13 规则为 0 违规，沿用 CP5 更广的 Java 扫描结论；BFF/前端复核仍发现精选重庆 POI 目录的静态地点数据，这属于全国化产品数据边界，本轮不宣称全项目零硬编码。
- 最终 FE 日志已绑定 final-08 源码指纹，且未放在 FE Git 工作树的 untracked 文件中。PostgreSQL、真实 Redis、多实例/进程强杀恢复、真实供应商与浏览器 E2E 未执行，不纳入通过范围。
- 临时冷候选位于系统 Temp，用于复现脏工作树交付，不是长期发布包。没有提交或推送变更。

# CP5 全领域重构统一验收报告

日期：2026-10-11  
工作根：`D:/scenic-guide`  
范围：Java 后端 CP5 六个工作域。按执行计划要求，在所有实现完成后统一验收，不把期间的开发回归当作子项验收。

## 统一裁决

**CP5 在计划限定范围内通过统一验收。** 六个工作域的能力边界迁移完成；共享候选经过架构审查、硬编码红队复核、主智能体全后端源码核查和全量 clean Maven 测试。最终候选 `clean test` 共 1129 项，0 失败、0 错误、10 跳过，`BUILD SUCCESS`。全后端扫描覆盖 345 个 production Java 文件及 18 个主资源文件；资源规则和 JSON 校验无未分类命中。

本裁决只表示 CP5 计划范围内的 Java 后端重构和所列回归通过，不表示外部依赖环境、部署架构或全部业务输入都已穷尽验证。

## 候选识别

- Git HEAD：`46b1653e81050e3cdd9f23506b327bc6676bcbd8`；项目已有大量未提交/未跟踪变更，HEAD 本身不能唯一标识候选。
- CP5 Java 实现候选指纹：对 `ai/` 下 `git status --short --untracked-files=all` 列出的全部修改、删除和未跟踪文件，按路径排序后拼接路径与文件 SHA-256（删除文件标记为 `DELETED`），再计算 SHA-256：`5804ac7c0921681e9a9a535cc2837bc2a1ab5babf363902584af54f2859e52b6`。该指纹不含验收文档，避免自引用。
- 本次未提交或推送更改。候选指纹不包含工作区外的运行服务、数据库或环境变量。

## 六域验收

| 工作域 | 统一候选审查结论 | 验证关注点 |
| --- | --- | --- |
| Chat / 模型 | 通过 | provider-neutral 模型端口；上下文、摘要、检索与流式输出协作者；长会话游标、硬约束、安全隔离、取消/错误行为 |
| 餐饮 / 景点 | 通过 | JDBC、POI 和种子加载外移；taxonomy、地理能力边界；餐饮意图和标签共用策略；时间间隙、营业区间、无候选/路线缺失路径 |
| 用户 / 授权 | 通过 | 账号仓储、密码、JWT 签发/验证/撤销、偏好与在线状态能力隔离；角色、失败和用户归属约束 |
| RAG / 知识 | 通过 | 文档/文件/索引适配器；重排供应商及缓存失效边界；降级不写缓存、更新/删除与分页/输入限制 |
| 统计 / PDF / 配置 | 通过 | 分析仓储、在线状态及 PDF 渲染隔离；数据范围、容量、权限和配置读写回归 |
| 共享能力 / 迁移 | 通过 | `domain.ai`、geo、taxonomy 等能力所有权明确；schema 初始化与迁移职责边界；ArchUnit 防止领域层重新依赖基础设施 |

新增站点在缺乏有效行程时间锚点时仍保留“待安排”，没有伪造固定钟点。该行为是刻意保留的“不猜测时间”边界；生成有可执行时间的完整排程仍需日期/时段或可推导的相邻站点。它不作为 CP5 结构重构的失败，但不应被报告为时间已自动求解。

## 独立审查与缺陷关闭

- 架构审查覆盖六域的真实调用链和依赖方向；最后发现 `LlmIntentExtractor` 的通用餐饮兜底仍调用空 taxonomy 静态 helper。已删除该 helper，改用注入的 `DiningCategoryGrounder`，并增加使用注入 taxonomy 的回归。
- 硬编码红队指出原扫描器未覆盖 Chat retrieval 中的附近 POI 后缀。已将前缀/后缀外置为必需 taxonomy 词汇，扩展扫描器至 `application/chat`，增加运行时 taxonomy 缺键拒绝热加载和 POI 查询拼接测试。
- 最终 clean suite 首次发现 `ChatApplicationServiceTest` 的 taxonomy mock 缺少新增附近前缀；修正 fixture 与断言后，该类 6 项通过，再执行最终全量 clean suite。该修复属于测试契约同步，没有放宽生产行为断言。
- 扩大全后端硬编码扫描（旧范围仅 121 个 Java 文件）后发现 `BaiduSearchService` 直接拼接百度网页搜索/favicon URL，且百度语音 endpoint 固定在 provider client。现改为 `WebSearchLinkPort` + infrastructure adapter，并将百度网页搜索、favicon、语音 ASR/TTS/token 与 Amap Web Service base URL 放入可由环境变量覆盖的配置。由于测试资源的 `application.yml` 会遮蔽主配置，测试配置补入不可联网 URL；干净全量测试首次暴露了该测试上下文缺键，已补齐并在最终全量运行中验证通过。
- 独立架构与红队审查覆盖核心六域；其后新增的 provider endpoint 配置与 Baidu 搜索端口变更由主智能体按全量源码、配置加载链和专项/全量测试重新复核，不将前序审查描述成对后续差异的审查。保留一条装配边界说明：`PlanAdjustmentService` 构造时设置空 taxonomy/grounder，生产 setter 随后注入真实 taxonomy 并同步更新 grounder；本次未发现该短暂初始化状态被业务调用。
- 详细硬编码范围和局限见 `CP5_HARDCODE_AUDIT_20261011.md`。

## 验证结果

### 主验收

命令：

```powershell
$env:KNOWLEDGE_DB_URL='jdbc:sqlite:C:/Users/summer/AppData/Local/Temp/scenic-cp5-final-20261011-final3.db'
.\mvnw.cmd clean test -DfailIfNoTests=true
```

结果：**Tests run: 1129, Failures: 0, Errors: 0, Skipped: 10；BUILD SUCCESS**。运行使用隔离 SQLite 数据文件，不应写入项目持久数据库。10 项跳过来自需要额外环境/数据资产的可选集成路径。

### 硬编码与资产

- `python ai/scripts/audit-cp5-backend-hardcoding.py`：345 个 production Java 文件、18 个主资源文件、14 个 JSON/JSONL 可解析、0 无效资源、0 未分类命中。
- `python ai/scripts/audit-planner-hardcoding.py`：122 个重点 production Java 文件、13 类规则、0 违规。
- 41 条景点与 50 条餐饮坐标属于 POI 种子资产；百度网页/语音及 Amap Web Service endpoint 已改为环境可覆盖配置。剩余高德 PDF 深链与 Web Service fallback 位于 infrastructure，按 provider 协议常量记录，见硬编码专项报告。
- 主/测试 `dining_taxonomy.json` SHA-256 相同：`D7E653A967CB9A069E406ED701EDC9A27CC7B839A8EF3668B77FC62A332F7025`。
- `git diff --check` 通过。

## 未验证边界

- 本轮仅在本地 SQLite 完成 Maven 全量测试；PostgreSQL 方言和真实数据库升级未运行。
- 未验证真实 Redis、多实例并发/切换、进程崩溃恢复。
- 本地 Qdrant `localhost:6334` 不可用；相关集成用例按其既有配置跳过或走降级，不能据此声称真实 Qdrant 查询通过。
- 未对高德、Bailian、语音等付费或线上供应商执行实网全城市验收；供应商适配以本地单测/受控响应验证。
- CP5 范围是 Java 后端，不含本轮 BFF/浏览器全链路验收。
- taxonomy 外部目录支持部分文件覆盖；相关资产若版本不配套，虽能单独通过格式校验，仍有语义混配风险。需由运维成组发布并在目标环境验证。
- 工作区原先包含大量未提交与未跟踪改动；本报告不声称 clean checkout 可复现，本项应在 CP6 完成。

## 回切与复现

CP5 代码按现有文件差异逐项回切；不得用 `git reset --hard` 或 `git clean` 清除共享工作区的其他改动。taxonomy 配置回切时，应与对应解析器和必需键校验保持同版。可在 `D:/scenic-guide/ai` 用上面的隔离 SQLite 环境重跑全量 Maven 命令；如果使用真实外部数据库，必须先另行隔离数据源。

## 候选指纹

该指纹只识别 `ai/` 下的实现与测试文件，不是 Git commit，也不包括验收文档、被忽略文件、BFF/前端变更或外部运行状态。重新改动 `ai/` 下文件后，指纹和全量测试结论都需重新生成。

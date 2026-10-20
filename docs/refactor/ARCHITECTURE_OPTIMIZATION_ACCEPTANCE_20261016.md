# 架构优化实施与验收

日期：2026-10-16。对应 [审查报告](ARCHITECTURE_HARDCODE_REVIEW_20261016.md) 与 [执行计划](ARCHITECTURE_OPTIMIZATION_PLAN_20261016.md)。本轮列出的优先问题、配置/展示问题及相关职责优化已落地，实施者自查、交叉子智能体复审和根智能体验收完成。本轮范围内未留已确认阻断项；不等于全项目零缺陷或纯领域重写完成。

## 1. 实际落地

| 审查项 | 最终实现 |
| --- | --- |
| 手工/AI 变更门禁不一致 | 新增 PlannerMutationPolicy，共用目的地城市范围、明确避开条件、时间可行性；指定候选和自动替换都过滤；普通/强制 AI 应用也校验。候选拒绝明确区分 DESTINATION_CITY_CONFLICT 与 AVOIDED_PLACE |
| PlannerService 混合用例编排 | 真实 Controller 手工变更入口传显式 PlanningActor；PlannerApplicationService 复用现有鉴权，持有 CAS 写入。领域 prepareReplanAuthorized/prepareStopsAuthorized 只计算并验证候选，不读写会话仓储。旧领域 wrapper 复用同一 prepare/receipt，保留历史调用兼容 |
| 策略双重装配 | 删除 instanceof 依赖同步分支，集中一个策略装配点；注入变化时重建，移除每次 preview 无意义重建。不引入插件框架或新配置体系 |
| 生产个人部署回退 | 删除固定 Zeabur 地址；production/托管环境缺显式 Java 地址即启动失败，本地保留 localhost。范例及指南同步 |
| BFF 第二套偏好解释权威 | 退役旧 chat proposal/confirm，两者统一 410；删除三套相关文本解析函数。核实并迁移 web/app.js 旧按钮分支和两处活跃文本偏好写入，文本现在走 Java memories；preferences POST 只接受结构化 patch 委派 Java，旧 value 请求 410，GET/DELETE 与 revision 冲突保持 |
| 端口决定落盘 | local 的 MEMORY 开关不再被 3000 端口覆盖；显式 redis 模式维持 Redis 权威。真实隔离子进程验证不同端口/开关组合实际文件有无 |
| 前端假默认值 | 未提供的天数、兴趣、步行强度显示待确认/未提供；编辑不自行填业务值，已有值保留；保存列表缺 days 不虚构两天 |
| 全国化文案 | 当前 web 的首页、游客聊天、历史、保存列表、注册页/弹窗、页头和元信息使用中性文案；删除固定 36+ 统计，保留品牌和真实行程标题，不触碰旧 Vue 原型或重命名持久化 ID |

审查时“当前 web 无旧入口调用”的表述已纠正：只检索 web/src 漏掉了 web/app.js。此次追踪实际入口并迁移消费者后退役，不是仅让 API 返回错误留下坏按钮。

## 2. 实施及独立复审发现并闭环的边界

- **原快照浅复制污染**：原 mutableMap 只复制顶层，修改嵌套 stops 可能在拒绝或 CAS 冲突前污染旧对象。现深复制后变更，测试断言整个原 JSON 不变。
- **新路线接驳与旧时刻冲突**：不关闭 TIME_OVERLAP；根据真实路线或现有显式配置的预留时间，仅向后推迟受影响链，保留原空隙、前段和其他日期，不删除站点，changedSegments 包含实际移时 ID。闭馆/返程失败仍拒绝。
- **排时与校验的交通预留来源不同**：手工时间工具也注入同一 DynamicShortTripPolicyPort；配置预留写成 ESTIMATED / CONFIGURED_UNKNOWN_TRANSFER，真实 resolved cost 优先。无配置/无事实时不造分钟，未排时的草稿不冒充已证明可行。
- **旧入边残留**：替换或删除清理直接后继旧 route/cost/mapContext 路线。删首站的新首站清入边且不生成不存在的接驳估算，不清无关日期。既有违规不一概阻止无新增违规的合法删除。
- **startTime-only 兼容**：共用有效时钟读取，优先有效 time，否则有效 startTime，避免占位 time 遮住有效 startTime。涵盖尾站排时及首站 replace/replan。
- **未知偏好字段静默忽略**：Java UserPreferencesPatch 用 scoped JsonAnySetter 拒绝未知字段，覆盖宽松 Spring mapper、unknown-only 和混合合法字段。没有全局收紧 Jackson，也没有在 BFF 复制偏好字段白名单。
- **快照约束映射**：只剥离 asMap 的派生 hardConstraints/softPreferences 视图，保留根 mustVisit/avoid/关键语义及 provenance；严格映射往返有测试。
- **错误原因跨 BFF 丢失**：只投影格式合法的机器 reasonCodes 数组，不把拒绝 trip、session、令牌或任意 details 返回浏览器。

## 3. 根智能体实际验收

| 验证 | 最终结果 | 说明 |
| --- | --- | --- |
| Maven package 全量 | **1359 run，1317 passed，42 skipped，0 failures/errors，BUILD SUCCESS** | 默认 opt-in 跳过仍存在，不计为通过；按实际 XML testcase（含 nested）核对，与 Maven 汇总一致 |
| 中间定向 Maven | 172/172 通过 | 随后新增删除等边界已纳入最终 package；不与全量数相加 |
| 最终 Node 全套 | **164 run，163 passed，1 skipped，0 failed** | 未显式开启真实 Redis 的 opt-in；BFF server 子集 50/50，包括实际隔离启动/存储配置与 HTTP 错误投影 |
| vue-tsc / Vite | 均成功 | 最终生产文案与模块冻结后执行 |
| 最终受控浏览器 | **33 passed，7 skipped，0 failed** | 本地 Java stub/受控接口；语音 fixture 为真实浏览器采样，不是付费 ASR/TTS |
| 新 JAR 真实 Java 组合 | **1 passed** | 本地 HTTP 供应商 + 临时 SQLite；真实前端/BFF/Java，无开发服务或付费供应商 |
| 部分规划生命周期 | 1 passed，1 skipped | 截止时间部分结果→预览/应用/保存/重开；这里跳过的组合用例已独立执行 |
| 来源链接生命周期 | 1 passed | 目录来源→规划/展示/保存/重开 |
| BFF 架构/配置契约/Java URL 检查 | 均通过 | 架构检查 26 模块，不能替代业务边界实测 |

1359 的核对方法：6 个嵌套套件 XML 根 tests=0，但实际含共 52 个 testcase；只累加根属性会漏算成 1307。本报告按实际 testcase 及 skipped/failure/error 子节点计数，不掩盖跳过或重复累计分次运行。

最终 JAR SHA256：`0b07e2a4face8e8d115c97bb1d7c2aa167a4610c9a5423ee3d6e9741b7b212a1`。

### 真实 Java 的拒绝与合法闭环

同一随机合成账号和杭州行程完成：

1. 偏好内容甜口、辣口、人文历史持久化并实际召回，用户明确同意使用。
2. 添加重庆真实目录 ID cq-hongyadong：BFF 返回 400 / DESTINATION_CITY_CONFLICT；权威会话版本和 days 完全不变。
3. unknown-only、unknown+walkingTolerance 两种偏好补丁：400，完整 formalPreferences 不变，revision 保持 2。
4. 合法调整预览取消不变，再预览并确认应用，保存、浏览器 PDF 下载、新会话重开；权威版本和站点一致。

完整摘要、供应商替身调用、运行环境/JAR 指纹及 PDF 在 [证据目录](evidence/architecture-optimization-20261016)。没有运行真实模型、高德或语音供应商付费测试；没有将本轮替身测试写成真实外部服务验收。

## 4. 原始失败记录与修正

- 首次定向失败含严格 snapshot 映射和 Mockito 夹具问题；后两次剩余 TIME_OVERLAP，定位为交通配置来源不一致，已以生产规则统一修复，没有删除重叠断言。
- 第一次全量 package 为 1357 run、1 error：CombinedLogicRegressionTest 将生产必需的目录端口传 null。只补有效 mock 端口，原 canApply 断言保留，没有在生产中绕过缺失依赖。
- 首次全浏览器语音用例固定 300ms 后未收到 PCM，页面提示录音太短且没有 ASR 请求；原样隔离跑通过后，改为等待真实 samples 帧，保留全部上传/迟到覆盖/释放断言。第一次 trace 被隔离默认输出重置，原失败日志保留，页面状态来自重跑前实际读取，不声称原 trace 仍在。
- 第二次全浏览器展示夹具挂在 #app 内，被迟到 auth/session bootstrap 合法重绘删除；只将测试夹具挂在应用根外，并主动延后/释放 session 回执验证竞态。该轮 trace 保留在独立目录，生产未修改。
- 第一次新增真实 Java 权威 GET 404，来自测试 fetch 漏 x-yuyouzhice-device，被设备隔离拒绝；补现有 deviceId 头，不改路由或放宽权限。后续所有新增断言及合法闭环通过。

原始失败与最终日志分开保存；测试流程和功能测试均未通过 retry 或关闭规则制造通过。

## 5. 硬编码、设计取舍与剩余范围

本轮 361 个生产 Java 文件，指定五城市名及长经纬度字面量扫描 0 命中；BFF 个人远程回退和三旧文本解析函数均已删除。扫描不是“所有硬编码归零”的证明。真实地名/坐标、外置城市模板/策略、开发默认、数学常量和历史 ID 兼容保留，不以批量删字符串造成业务破坏。

保留现有模块化单体与 COLA 端口，不引入微服务、事件总线或策略插件平台。应用层接管的是本轮实际手工变更路径，PlannerService 仍有创建/查询及历史响应兼容职责，未宣称领域层完全没有 HTTP 风格兼容类型。动态餐饮/POI 的地理 grounding 仍由既有策略承担；本轮共享城市门禁重点覆盖目录实体，没有增加全国多城生成器。

本轮未重跑 PostgreSQL/真实 Redis/Qdrant、多实例和付费供应商板块；前一整体验收证据只作历史参考。默认跳过、真实基础设施、Linux、物理移动端、长时间压力及 restore 自构造快照契约候选不因本轮通过而自动关闭。

所有实际新启动服务均为专用隔离验收进程，结束后退出；没有改真实 ai/.env，没有提交、推送、部署或重启用户开发服务。示例配置已说明 MEMORY=1 对所有 local 端口都不落盘，现有显式配置不再由端口暗中改写。

## 6. 协同与交付

三名实施者分别负责 Java、BFF、frontend；完成自查后互相只读复审。复审查出的未知字段、startTime、删除入边及响应原因码均补修后再验。根智能体独占 Maven 并执行全量、最终 Node、类型/构建、全浏览器及实际 Java 组合验收，没有将审查者自述当作运行通过。

交付为本地可审阅源码快照、差异和指纹，包含大量既有脏修改，不能全部归为本轮；字体以独立资产补充。没有 Git 历史或远程操作。

经验查询“重构 规则 权威 依赖”无匹配，本轮未采用历史结论作为修复判据，登记 not_used。任务记录：C:/Users/summer/Documents/ggy的自动化流程/wiki/tasks/20261016-a440dd0bc09b.json。经验工具看板有既有 kind 字段异常，记录是否关闭以实际 task 文件为准，不声称看板同步成功。

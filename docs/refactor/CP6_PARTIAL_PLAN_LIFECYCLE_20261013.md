# CP6 部分生成行程生命周期

日期：2026-10-13。状态：本板块实施、独立审查与最终验收通过；环境限制及后续项见末尾。

## 缺口与目标

既有 RuntimePlanningDeadlineTest 验证域层真实预算到期，但浏览器 planning-incomplete 仅注入现成部分响应；真实外部供应商的前序生命周期未触发 deadline。当前需要确定触发真实 Java OperationDeadline，再验证非空已完成结果的预览、确认、保存、打开、继续编辑。这里的“真实链路”指浏览器→实际 BFF→实际 Java→临时 SQLite，外部供应商用本地可控延迟 HTTP；不冒称真实供应商故障验收。

## 已确认问题

- 调整预览与应用只由 sourceMode 推导 degraded，忽略 trip 内 deadline，出现顶层 false 与嵌套部分完成相矛盾。
- runtime refresh 把路线全部已核验等同生成完整，覆盖 degraded，而 deadline 与 PARTIAL_TIMEOUT 仍保留。路线证据与生成完整性必须独立处理。

## 分工与退出条件

后端实施者唯一修改 Java，先留失败证据再最小修复，复用现有 metadata；前端实施者负责实际 HTTP fixture、隔离启动与浏览器链；独立审查者只读生产并构造独立反例；主线程复核真实调用、证据计数和上一板块兼容性。

- 确定延迟触发 Java 预算，不由测试人工写入现成部分行程或拦截 /api/plan 替换。
- 有已完成站点时保留，顶层/嵌套 metadata一致；无站504且不创建成功session。
- 预览不变，确认实际修改且版本递增；save/open换新session并与页面权威快照、partial标记一致；重开后继续修改成功。
- 普通完整规划不误标partial；局部操作不能证明原缺失规划已生成。
- 禁止碰用户8080/3000/5173、真实数据库与共享Redis/Qdrant；所有供应商指本地或禁用，不读取真实.env，不调用付费模型/高德。
- 生产冻结后相关回归、全量Java、Node、浏览器、构建与类型检查按实际修改执行；保留失败、跳过及环境限制，最后本地审阅导出，不提交推送部署。

经验任务：20261013-ad6915eaacfa。采用FQ2实际跨层合同核验，补充参考离线草稿恢复中“重新打开后继续变异才能证明权威快照一致”的项目经验。

## 主线程追加发现与边界

当前页面 currentRouteStatus 优先旧 state.routeDataStatus，重开后可把当前 PARTIAL 显示为已核验；前端已建立失败反例并改为读取当前 trip。Java 启动发现固定开发机 .env 路径回退，BFF env.mjs 也固定读取 ai/.env。测试必须显式隔离：Java 临时工作目录的空 .env 与去除个人 fallback；BFF增加明确禁用文件读取的启动配置，默认契约不在本板块迁移；Vite测试配置禁止读取原项目env。

主线程扩充现有扫描器的 absolute_drive_path_literal 规则：修前识别1处Java个人.env回退及5处Windows PDF字体路径。字体路径是跨平台字体探测候选，不是城市策略或个人配置；保留候选记录而不制造全工程0字面量结论。规则正/反例4项断言通过，真实运行扫描见 CP6_PARTIAL_PLAN_BACKEND_LITERAL_BEFORE_20261013.json。BFF固定env路径仍是可移植性后续项，本轮增加隔离选择但不冒称已迁移默认加载契约。

## 第一轮冻结后的结果与真实HTTP缺口

partial-lifecycle-hydration-final.log：102/102通过，含真实生产refreshStatus方法投影的修前失败与修后对照；该方法测试提供方为mock，不能独自证明完整HTTP。第一轮冻结后的full partial-lifecycle-full-final.log：1226 run / 1212 passed / 14 skipped / 0 failures/errors，09:20:21。BFF/前端Node枚举 partial-plan-node-final.log：69/69通过。

浏览器使用CEFFD3...及含hydration补修C980E8...两包的运行均未触发deadline，实际得到CITY_CATALOG_UNAVAILABLE。保留两次失败日志；“可能未配置key”仅是早期假设，CLI提供fakekey后仍失败，不能当成已证根因。临时RestTemplate DEBUG看到正确localURL但中文URI，尚需raw wire/严格HTTP回归验证，不能仅凭toString中文断定。后续若客户端修复，该1226成绩属于修前源码，必须补最终全量。

## 第二轮实际传输与入口复核

真实本地 HTTP 及原始 ServerSocket 请求字节证明 AmapWebServiceClient 查询值编码缺陷：中文可能被严格 HTTP 服务拒绝，`&` 会拆成额外参数。10处 builder 统一采用 Spring URI 模板的 UTF-8 严格值编码；3项真实字节/服务端一次解码回归覆盖中文、空格、`+`、`%`、`&`、`#`及字面`%20`，修前失败日志保留。专项123/123；编码修后阶段全量 partial-amap-wire-full-final.log 为1229 run /1215 passed /14 skipped，0失败错误，09:27:49。

编码修后首轮浏览器候选同片区，被正常候选去重限制为2站，未触发慢请求；这是fixture未满足前提，不是生产缺陷。下一轮改为不同片区与明确类别，实际生成约4秒后保留3站、deadline与PARTIAL_TIMEOUT成立，但测试在预览错误读取trip（真实契约为proposedTrip）；此处同样不能误报产品缺陷。保留 cp6-partial-real-java-browser-verified-20261013.log 与 acceptance 日志原失败，不依据文件名宣称通过。

主线程另发现实际应用层 PlannerApplicationService.assembleApplyReceipt 仍按sourceMode组装元数据，领域层兼容路径测试不能替代此入口。补修与实际入口回归进行中；如果源代码变化，上述1229仍属于阶段成绩，最终重跑后单独列证据。

第三轮应用入口修前证据已确认：partial-app-entry-before.log 仅证明可应用REMOVE被误分类NO_CANDIDATE，HTTP仍200；不是超时元数据证据。增加canApply=false必要条件后，partial-app-receipt-before.log 4run/1fail实际执行到应用回执，证明trip内PLANNING_DEADLINE_EXCEEDED被顶层普通fallback原因替代。应用层现复用fromTrip，未修改预览trip内容；实际应用入口生命周期和真正不可应用空候选负例共同回归。

## 最终冻结源码回归

最终专项 partial-app-entry-regression.log：137run /137passed /0failed/errors/skipped，09:36:17。包含实际application.converse/apply的SQLite生命周期4项，以及既有preview用例13项。最终全量 partial-app-entry-full-final.log：1230run /1216passed /14skipped /0failures/errors，09:39:12 BUILD SUCCESS。跳过不计为通过；此前1226、1229为阶段结果，不与最终重复累计。

最终包 partial-app-entry-package.log 09:36:38；SHA256 61CF86F0905BE968FD97C85AFD8284573B6DA3D6DDED0C33D84E3A81D055700B。最终全量后生产源码未变，不重新打不同包；浏览器使用此同包。主线程已复读最终日志、检查文件hash，独立审查确认两处入口补修只复用原投影/增加必要谓词，没有补造deadline或扩大业务策略。

Node枚举69/69通过，frontend构建与类型检查通过。完整配置契约脚本仍失败：既有D:/scenic-guide/ai/.env.example缺失；不创建第二套配置模板或把检查改为绕过。BFF固定默认env路径与正常runtime sourceMode普通fallback分类仍是明确后续项，不能把没有deadline的正常对照扩大为所有完整结果degraded都false。

浏览器第7轮已走到保存、重开、继续删除，trip内部部分标记与fallback保留；测试错误要求/api/trip/stops具有未声明的顶层metadata，末尾失败。该接口合同是权威trip，不能为满足测试增加重复生产字段。保留cp6-partial-lifecycle-acceptance-final-20261013.log；修正为对应接口合同后再独立完整运行。

## 最终浏览器与交付裁决

cp6-partial-lifecycle-passed-20261013.log：1 passed，0 failed/skipped，11.1秒业务场景/总15.6秒，runner退出0。根线程亲读实际日志与result-summary并再次计算jar SHA，确认与61CF同包一致。永久机器摘要：CP6_PARTIAL_PLAN_BROWSER_RESULT_20261013.json；完整原始证据在Temp/cp6-partial-lifecycle-mdAkNJ。

实际浏览器→BFF→Java→临时SQLite→本地延迟HTTP供应商：initial3/version1；preview2/version1但当前页面days/version仍为原3/version1；apply2/version2；保存后重开2/version1、换新session、页面权威快照一致；继续delete1/version2。各快照均保留deadline与PARTIAL_TIMEOUT，sourceStatus.fallback仍true。完整对照无deadline；未完成提示与当前行程route徽标通过。正常路线已核验与生成未完成可同时成立。

实施自查、独立只读审查、独立反例、根线程复核均完成，本板块通过。供应商是受控localhost HTTP，不冒称真实高德故障验收；真实PostgreSQL、专用Redis/Qdrant、多实例、进程崩溃恢复、全国知识覆盖仍未完成。用户8080/3000/5173未操作，没有提交/推送/部署。

本地审阅候选：C:/Users/summer/Documents/Codex Deliveries/CP6-20261013-partial-plan-review-final。导出既有三个工作区全部候选dirty源码，包含早前改动，不能全归因本轮；env/运行数据/日志按既有导出规则排除，文件哈希清单提供审阅完整性，不作为全仓秘密扫描认证。原始失败与最终测试日志留在工作区，导出报告引用它们。

补充只读正式库验证：CP6_PARTIAL_PLAN_FORMAL_SQLITE_RESULT_20261013.json确认保存的formalVersion1、2站、degraded=true、deadline与PARTIAL_TIMEOUT；不是仅比较页面。经验任务20261013-ad6915eaacfa已closed，benefited并保存4份变更/验证证据hash；finish随后在旧看板汇总报kind错误，不能声称经验看板已同步，本次没有扩大修复个人经验工具。

BFF与浏览器逐轮证据报告：D:/loop-p0-functional-inventory-1-home/docs/partial-plan-lifecycle-acceptance-20261013.md，记录7轮失败和第8轮完整通过。前三轮实际编码缺陷、第四轮fixture未触发前提、第五/第七轮接口字段误读、第六轮runner Windows路径错误分别分类；未将旧trace被覆盖写成完整保留。独立运行的自有Java/供应商/4173/49310清理后无监听，用户服务未重启。

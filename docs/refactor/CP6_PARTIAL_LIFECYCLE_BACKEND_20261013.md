# 部分生成后保存恢复：后端实施与验收

## 确认的问题与改动

`RuntimeLocationPlanner` 在请求预算耗尽时保留已完成站点，并标记 `PLANNING_DEADLINE_EXCEEDED` / `PARTIAL_TIMEOUT`；`PlannerService.create` 再次确保不保存空的成功行程。正式保存和打开采用完整 JSON 快照，未发现这两个环节主动丢弃标记。

本轮确认两处状态错配：

- `PlanAdjustmentService` 的预览和应用回执只按 sourceMode 重新计算顶层降级信息，丢失快照中的实际超时原因。新增 `PlannerVersionMetadata.fromTrip`，由 PlannerService、PlannerOpenApplicationService 和 PlanAdjustmentService 共同使用，沿用原版本、来源与普通 fallback 规则，从权威快照读取降级标记与原因。
- `RuntimeLocationPlanner.refreshRuntimeMetadata` 在局部替换后的路线全部核验时写入 degraded=false/fallback=false，尽管原规划尚未完成。现保留该快照的 deadline 未完成状态；路线本身仍可如实为 VERIFIED_AMAP。局部操作不能证明缺失部分已补齐；一次全新完整规划从新快照产生状态，可清除旧 timeout，不永久污染新行程。

冻结复读补获同类路径：`PlannerService.mutateStops` 调用实际 AmapPlannerGateway 的 hydration 投影，所有保留站点动态字段可用时仍会将 sourceStatus.fallback 清为 false。新增 `PartialHydrationMetadataTest` 直接执行该真实投影（不发送供应商请求），修前两项一失败，`partial-hydration-before.log` 保留。Runtime 与 hydration 现复用同一 planningIncomplete 判定；不引入第二套状态。先前用于浏览器的 CEFFD3 包不是这项补修后的最终包。

另删除启动入口中指向开发机器 D 盘的 `.env` 绝对 fallback。只支持当前目录及父目录，进程环境/JVM 参数优先逻辑不变。没有读取或修改用户配置。

## 隔离测试方法

`PartialPlanningPersistenceLifecycleTest` 使用测试配置的独立临时 SQLite、真实规划/调整/保存/打开服务与仓储。唯一被替换的是地理网关边界，其内部调用真实 RuntimeLocationPlanner 与本地可控路线提供方；等待真实 OperationDeadline 到期，不直接注入 PARTIAL 成功响应。

覆盖部分生成保留两站 → 预览不修改权威快照 → 确认移除一站 → 正式保存 → 重新打开签发新会话与版本1 → 继续删除，以及正常生成保存打开无 deadline 标记、无站超时504且数据库不新增 session/trip。独立审查另有真实运行时替换、完整重建、打开来源版本与空超时反例。EnvFileResolutionTest 验证路径优先级与缺失配置时不跨工作区查找。

修前证据保留于 `partial-lifecycle-before-confirmed.log`：真实 SQLite 生命周期在预览顶层 deadline reason 断言失败。测试搭建中的首轮字段名编译错误、近坐标被去重未触发 timeout，以及独审 fixture 用完时间预算而非请求预算的失败分别留在之前日志，不误报为新的生产故障，修后保留原业务断言。

最终专项、全量与产物指纹将在本板块冻结后追加。本报告不把可控供应商验收称为真实高德或真实基础设施验收；未重启用户开发服务，未修改共享 Redis/Qdrant 或用户数据库。

## 当前冻结证据

- `partial-lifecycle-regression-final.log` 初步专项 93/93 通过；此后补获 hydration 路径，不能将该初步成绩当最终代码验证。
- `partial-lifecycle-hydration-final.log` 最终专项 **102 run / 102 passed / 0 skipped / 0 failures / 0 errors**，2026-10-13 09:17:42 BUILD SUCCESS。包括独立4项、真实 SQLite 生命周期3项、hydration投影2项、env路径2项及既有规划/保存回归。
- `partial-lifecycle-final-package.log` 09:18:07 BUILD SUCCESS；产物 `D:/scenic-guide/ai/target/scenic-guide-ai-0.0.1-SNAPSHOT.jar`，SHA256 `C980E8A8D83159293D6E3ADFD66A2162F36A93AF24A64F25A8B568D63266373F`。此包供独立可控供应商浏览器链验收。最终全量尚待前端/独审冻结，不提前声称通过。

阶段全量 `partial-lifecycle-full-final.log`：2026-10-13 09:20:21 BUILD SUCCESS，**1,226 run / 1,212 passed / 14 skipped / 0 failures / 0 errors**。此后真实 HTTP 链发现下述中文编码缺陷，该成绩仅对应 C980E8... 阶段，不能当作补修后最终源码全量。

浏览器链的供应商本地 HTTP 配置仍由前端实施者诊断；截至后端全量结束，尚未取得该链路最终通过证据，不能用上述 mock/domain/投影测试替代。主报告将以独立浏览器实际结果追加最终板块裁决。

## 严格 HTTP 传输补修

浏览器通过真实 BFF/Java 请求本地严格供应商时，高德 GET 在进入 Node handler 前即400，无法触发部分生成。排除配置后 DEBUG URI 显示请求含原始中文；`partial-amap-wire-before.log` 的 builder/真实 loopback HTTP 两项分别失败/报错，并确认 `&` 等值还会破坏参数边界。不是把 fixture 改成宽松解析器，也没有调大服务超时。

`AmapWebServiceClient` 十个请求构造方法统一使用 Spring 的 query 模板变量严格 UTF-8 编码与展开。关键词/城市/坐标等值只编码一次，输入字面 `%20` 保留为字面文本；`+` 不被服务端 form 解码成空格，`&/#` 不成为参数或 fragment。没有手写百分编码器、城市补丁或专用测试端点。

`AmapChineseWireContractTest` 覆盖所有涉及中文值的 builder、RestTemplate URI 重载实际 loopback HTTP，以及 ServerSocket 捕获真实首行字节，断言请求行 ASCII、存在 UTF-8 百分编码，服务端一次解码精确还原中文/空格/百分号/加号/分隔字符。URI.toString 断言只作为补充，不替代 wire 证据。补修后专项、包与全量需重新验证，不能沿用上述阶段成绩。

补修专项 `partial-amap-wire-regression.log`：09:25:05 BUILD SUCCESS，**123 run / 123 passed / 0 skipped / 0 failures / 0 errors**；包含既有高德客户端、路线服务与 gateway 测试，无需降低原断言。`partial-amap-wire-package.log`：09:25:46 BUILD SUCCESS，最新 jar SHA256 `D7CC1D65C2936685B177AD5D6C3EB1830F2F3A31B2683671137B55D34A959766`。浏览器改用此包；最终全量 `partial-amap-wire-full-final.log` 仍在运行，不提前统计。

最终全量已完成：`partial-amap-wire-full-final.log`，2026-10-13 **09:27:49 BUILD SUCCESS，1,229 run / 1,215 passed / 14 skipped / 0 failures / 0 errors**。没有将跳过计为通过。包先于该最终全量生成，此后生产源未变化，最终复核 SHA256 仍为上述 D7CC1D...；无需另打与浏览器不同的包。指定生产文件 `git diff --check` 退出0。

上述为 D7CC1D... 阶段冻结证据。后续实际应用入口补查有新改动，不能将 1,229 项统计称为最新源码已通过。

## 实际应用入口补查

浏览器首次到达部分生成预览后，测试读取 `proposal.trip`，而正确契约是 `proposal.proposedTrip`。实际 trace 中 proposedTrip 与顶层均保留 degraded=true、PLANNING_DEADLINE_EXCEEDED 和 PARTIAL_TIMEOUT。此项为测试字段误读，前端修正测试，后端没有添加预览兜底。

将本地 SQLite 生命周期从领域兼容入口切到真实 PlannerApplicationService.converse/applyAdjustment 后，发现两处独立遗漏：

- `partial-app-entry-before.log` 显示可应用删站预览被映射为 NO_CANDIDATE：删站不需要替换候选，却仅因候选列表为空被误分类。映射现要求 canApply=false 才可认定 NO_CANDIDATE。该项是内部 outcome 错误，既有 HTTP200 与响应 body 并未因此变化，不宣称浏览器报错。新增不可应用空候选反例保留 NO_CANDIDATE；实际删站生命周期仍严格要求 SUCCESS。
- 修正分类后 `partial-app-receipt-before.log` 四项一失败，真正执行到应用回执：内部 trip 保留 deadline 原因，应用层 assembleApplyReceipt 却返回 DEMO_OR_ESTIMATED_ROUTE_FALLBACK。此入口现同样复用 PlannerVersionMetadata.fromTrip。之前领域兼容入口通过不能证明实际应用入口通过。

最终专项、重新打包和全量结果待实际完成追加。新增负例直接验证真实私有映射投影，不称为完整 HTTP 反例；主生命周期使用真实应用服务、SQLite 与本地受控地理边界，浏览器仍另行独立验收。

实际入口补修专项 `partial-app-entry-regression.log`：2026-10-13 09:36:17 BUILD SUCCESS，**137 run / 137 passed / 0 skipped / 0 failures / 0 errors**，包含 PlannerPreviewUseCaseTest 及上述生命周期/供应商编码/独审回归。重新打包 `partial-app-entry-package.log`：09:36:38 BUILD SUCCESS，新 jar SHA256 **61CF86F0905BE968FD97C85AFD8284573B6DA3D6DDED0C33D84E3A81D055700B**。已通知独立浏览器使用此同包，生产源码冻结。最终全量记录为 `partial-app-entry-full-final.log`，结果待实际结束补记；不沿用 D7CC1D... 阶段统计。

最终全量 `partial-app-entry-full-final.log` 已于 **09:39:12 BUILD SUCCESS：1,230 run / 1,216 passed / 14 skipped / 0 failures / 0 errors**。全量测试后复核 jar SHA256 仍为上述 **61CF86...**；生产源码未变，保留同一包而没有再次打包。新增一项负例计入最终套件，跳过未算作通过。后端本范围源码与测试冻结；浏览器完整生命周期实际结果仍由主线程收集，不用本项全量统计替代。

根线程最终浏览器复核：cp6-partial-lifecycle-passed-20261013.log 1 passed/0 failed/skipped，同包61CF86...，生成3站→预览不变→应用2站→正式保存→重开新session2站→继续删除1站，全部保留deadline与PARTIAL_TIMEOUT。最终机器摘要CP6_PARTIAL_PLAN_BROWSER_RESULT_20261013.json与正式SQLite摘要由根线程持久保存；完整失败分类及范围见CP6_PARTIAL_PLAN_LIFECYCLE_20261013.md。该结论限本地受控HTTP供应商，不扩大为真实外部供应商和真实远程基础设施验收。

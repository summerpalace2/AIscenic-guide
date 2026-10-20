# 部分生成、局部修改与保存恢复独立验收

日期：2026-10-13。独审只新增独立测试及本文，没有修改生产代码。统一经验记录由主智能体维护。

## 已核验范围

独立文件 `ai/src/test/java/com/ai/guide/domain/planner/service/PartialPlanningLifecycleIndependentTest.java` 的四个测试已随 `partial-lifecycle-regression-final.log` 通过（专项 93 run、93 passed、0 failure/error/skip，09:14:26）。

| 场景 | 断言与边界 |
| --- | --- |
| 没有完成站点就到达请求截止时间 | 实际等待 OperationDeadline 到期，返回504，不创建持久会话，不调用静态行程构建器；线程截止时间退出后清理 |
| 部分规划后更换站点的预览 | 真实 RuntimeLocationPlanner 先形成部分快照，再进行已核验路线替换；原快照 JSON 不变，预览路线可为 VERIFIED_AMAP，但未完成标记、degraded、fallback、PARTIAL_TIMEOUT 保留 |
| 全新完整重建 | 本地供应商恢复正常后重新规划产生新快照，可以清除旧截止时间标记；原部分快照仍不变。此项验证 RuntimeLocationPlanner 的重建路径，不声称任何局部操作都补全原缺失部分 |
| 正式行程打开 | 实际 PlannerOpenApplicationService，仓储与正式行程读取边界为 mock；保留站点与部分来源，签发新会话/版本1，保存 sourcePlannerRevision=7、sourceTripVersion=3，原快照版本7不变 |

实施者的 `PartialPlanningPersistenceLifecycleTest` 提供补充证据：真实临时 SQLite 与规划、预览、应用、正式保存、打开、继续删除服务链；地理边界使用可控本地供应商，真实运行时等待截止时间，而非直接制造部分成功回执。独审不将这些隔离条件描述成真实高德、PostgreSQL 或共享 Redis 验收。

## 确认的问题与复核

1. 原局部替换刷新仅按路线是否核验写 degraded/fallback，可能让部分规划被误标为完成。现在保留原快照的截止时间来源；路线核验状态与规划完成度分别表达。
2. 原预览/应用顶层原因没有继承快照的实际超时原因。三处规划服务改用 `PlannerVersionMetadata.fromTrip`，不再靠顶层普通来源分类覆盖该原因。
3. 收尾复核发现正式站点变更经过 `AmapPlannerGateway.hydrate → refreshStatus` 仍会按供应商覆盖度清 fallback。最小修复已在源码中加入 `planningIncomplete(trip)`，与运行时刷新共用判据。修前 `partial-hydration-before.log` 为2 run / 1 failure，直接确证 fallback 被清；修后 `partial-lifecycle-hydration-final.log` 为102 run /102 passed /0 failure/error/skip（09:17:42）。新增 `PartialHydrationMetadataTest` 通过反射执行真实 refreshStatus，供应商为 mock，不把方法级回归称为全链路 HTTP 验收。
4. 页面路线状态原先优先旧全局状态，可能遮盖恢复快照的部分标记。前端改为当前行程优先；浏览器实际 HTTP 生命周期由前端审计者单独留证。

## BFF、恢复与运行隔离只读检查

`POST /api/trips/:id/open` 采用 Java 返回的新 trip、session 与版本，顶层标记读取本次 trip；没有使用旧页面快照覆盖新权威快照。`GET /api/planner/sessions/:id` 委托 Java 权威读取。源码第1636附近是新规划创建响应，不是同会话恢复接口，因此已撤回据此推断旧标记残留的猜测。

本地浏览器 runner 要求显式 jar 路径，使用临时目录及 SQLite、随机 Java/供应商端口和独立 BFF/Vite 端口；只向子进程传 OS/JDK 必要变量。Java 临时目录先写空 `.env`，BFF 设置 ENV_FILE_ENABLED=0，模型、嵌入、重排、语音及百度 URL 均指向本地禁用/fixture 服务。Java 启动入口已删除个人 D 盘 `.env` fallback，只探测当前目录与父目录。清理只针对 runner 自己启动的进程树与 HTTP server，不触碰用户开发服务。未读取真实配置值，也未发真实供应商请求。

## 失败历史与限制

保留 `partial-lifecycle-before-triggered.log` 的测试编译失败：独立 fixture 曾误用 `status()`，实际 record 方法为 `serviceStatus()`。

保留 `partial-lifecycle-before-confirmed.log` 的 7 run / 4 failures：实施者真实 SQLite 的预览顶层超时原因缺失是生产缺陷；独立三个失败都发生在公共 fixture 的部分生成前置断言，原因是3小时预算和未知景点停留时长让行程正常结束，没有等到第二条路线的请求截止时间。改为6小时及明确景点类型后保留原有断言，四项通过。不得把这三个前置失败称为正式打开或重建的生产缺陷。

正常运行时完整快照的 sourceMode 为“高德实时周边规划”，而既有顶层来源分类只把“高德实时接口”识别为无普通 fallback。因此可能出现 trip.degraded=false、路线已核验，但顶层 generic fallback=true。该旧分类差异本轮保持，正常对照验证的是没有 PLANNING_DEADLINE_EXCEEDED/未完成提示，不能宣称所有完整规划的顶层 degraded 都为 false。

上一知识库板块的城市范围与索引源码未被本板块修改；其回归是否全部运行以统一最终 Maven 日志为准。旧配置检查缺少 `ai/.env.example` 的限制保留，不创建模板伪造通过。静态扫描保留5个 Windows 字体路径候选，不报告为零硬编码。

最终全量与浏览器结果需分别引用真实日志追加；目前不将专项通过扩大成所有基础设施及供应商已完成验收。

## 本地实际 HTTP 暴露的客户端编码缺陷

浏览器连接真实 Java 后前三次诊断运行均未得到预期截止时间部分规划，失败保留，不能算通过。生产 RestTemplate 的 URI 重载收到客户端直接 `.build().toUri()` 的非 ASCII query；单凭 DEBUG 中文显示不能证明 wire 错误，但 `partial-amap-wire-before.log` 的实际 loopback HTTP 已复现服务器400（URISyntaxException），同时原值中的 `&` 被解释成参数边界：2 run /1 failure /1 error。

修复只在 AmapWebServiceClient 的所有请求构建共用 `encodedUri`：把每个原始 query 值替换为内部变量，然后 UTF-8 encode + buildAndExpand 一次严格扩展。独审确认这避免组件编码保留字面 `+` 的问题；中文、内部空格、`%`、`+`、`&`、`#` 和输入字面 `%20` 应在服务端一次解码后保留原意。没有通过字符串拼接供应商 URI，也没有放宽 fixture HTTP parser。

新增 AmapChineseWireContractTest 包含真实 ServerSocket 捕获首行原始字节的 ASCII / UTF-8百分号编码断言，并断言服务器收到的 query 一次解码等于原值；另有实际 loopback HTTP RestTemplate 请求。修后 `partial-amap-wire-regression.log` 实际通过123 run /123 passed /0 failure/error/skip（09:25:05），其中 wire 三项全部通过。最终全量仍需依据完成日志补充。修前1226项全量所用旧编码版本不能算本次最终产物验收。

编码修复后的浏览器运行已经触发真实请求截止时间并保留三站；预览实际 `proposedTrip` 与顶层原因也保留标记，但测试误读不存在的 `proposal.trip` 字段而失败。原 trace 保留，此失败不归因于生产预览丢标记。独审复核 Controller → 应用 admission → previewForApplication → generateProposalPreview → RemoveStopStrategy → LocalAdjustmentRepair：预览深拷贝原快照，该删除路径只修复 days，没有完整重建或将 degraded 清 false 的写入。

同时源码确认应用层 `PlannerApplicationService.assembleApplyReceipt` 仍沿用仅 sourceMode 的顶层元数据投影。这是 apply 回执中的确切遗漏，不能由预览专项替代其验收；实施者将定向复用 fromTrip，并补应用层回归。09:27:49 的编码阶段1229 run /1215 passed /14 skipped 全量是真实阶段结果，但新 apply 源码变化后仍需要重算最终全量。

应用入口回归补充：实际应用层生命周期首次被既有 outcome 映射阻断，因为删除预览的 candidateReplacements 自然为空，被错误标为 NO_CANDIDATE；HTTP200与正文没有变，这不是用户收到HTTP错误。最小修复仅增加 canApply=false 条件，使可应用删除为 SUCCESS，并以不可应用空候选对照保留 NO_CANDIDATE。

修复分类后 `partial-app-receipt-before.log` 的4 run /1 failure 确实进入 application.applyAdjustment，证明快照本身保留部分标记而应用层顶层回执原因遗漏。最终源码只将 assembleApplyReceipt 改为 fromTrip，没有伪造/补写快照的 deadline，也没有重建原行程。测试改走实际 application.converse/apply，并保留预览不变、正式保存打开及继续删除的原断言。最终专项日志 `partial-app-entry-regression.log` 完成后由统一验收补统计。

最终统一验收：应用入口专项137/137；最终全量partial-app-entry-full-final.log 09:39:12为1230run/1216passed/14skipped/0失败错误。受控真实HTTP浏览器cp6-partial-lifecycle-passed-20261013.log 1/1通过，同包61CF86F0905BE968FD97C85AFD8284573B6DA3D6DDED0C33D84E3A81D055700B。根线程亲读log/机器摘要与hash；生产无新增变化。此追加是根线程结果复核，前文独审者未自行运行Maven/浏览器的边界不变。

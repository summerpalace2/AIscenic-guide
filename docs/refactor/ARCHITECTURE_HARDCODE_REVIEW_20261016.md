# 当前架构与硬编码审查

日期：2026-10-16。范围：D:/scenic-guide/ai、D:/loop-p0-functional-inventory-1-home/server、实际 Vite 入口 frontend/web。只读审查生产代码；本轮只新增报告与隔离探针证据，没有修改业务实现，没有启动服务、调用付费供应商或重新执行 Maven 全量测试。

总体判断：**当前架构方向合理，分层、端口和依赖倒置已有实际落地，适合继续渐进重构；缺陷集中在业务规则没有完全统一、旧兼容路径残留和运行配置隐式决策。无需推倒重建，也不能据此宣布重构彻底完成。**

P2 表示应优先处理的实际规则/配置缺口，P3 表示低优先级维护或展示问题；这不是对所有项目风险的完整定级。

## 一、优先处理的三个问题

### 1. P2：手工变更与 AI 调整未共用同一套规则门禁

实际入口是 PlannerController.java:144-159，经 PlannerApplicationService 进入 [PlannerService.replan](../../ai/src/main/java/com/ai/guide/domain/planner/service/PlannerService.java) 的 527 行及 mutateStops 的 575 行。BFF server/index.mjs:1914、1994 的公开入口也会转发至该链路。

- 指定加站/替换通过 ItineraryBuilder.java:443-449 按 ID 从全目录取景点，未验证是否属于当前目的地。
- 自动替换 PlannerService.java:798-803 同样枚举全目录，没有按城市过滤。
- 创建阶段 ItineraryBuilder.java:296-307 有 belongsToCity 隔离，手工写入链没有对应判断。
- 手工变更执行 finalizeTrip → hydrate → repository.update；finalizeTrip 只更新版本、文案和历史，hydrate 补 POI/天气/路线。它们不能代替城市、硬约束和时间可行性校验。
- 相比之下 AI 调整的 PlanAdjustmentService.java:949-956 会执行 optionStatus 检查；两条变更链职责重复而规则不对齐。

影响：北京草稿提交其他城市目录 ID 的构造请求缺少城市拒绝门禁，自动替换也存在跨城候选参与排名的静态条件。**本轮确认的是源码调用链缺口，未启动服务实测跨城写入，不写成已复现正常 UI 串城。**

建议：先建立共享的变更校验策略，由手工和 AI 写入用例统一调用；城市范围必须与明确的跨城旅行需求区分。不要复制整套 AI 方案生成器到手工入口，不要简单禁止一切跨城请求。应加城市范围、原硬约束和受影响时间块的针对性回归。

### 2. P2：生产缺配置会静默连接个人部署地址

[server/java-core-client.mjs](D:/loop-p0-functional-inventory-1-home/server/java-core-client.mjs:14) 写死 DEPLOYED_JAVA_CORE_URL；resolveJavaCoreUrl 在 production 或检测托管平台、且没有显式后端地址时，返回 ggysummer.zeabur.app。153-162 行 transport 会将请求正文和存在时的 Bearer token 发往选定地址。

根智能体从当前源码提取纯函数隔离执行，确认：production 缺配置 → 固定 Zeabur；托管平台缺配置 → 同地址；显式配置 → 正确覆盖。未发送实际网络请求。

影响：复制部署或新环境遗漏配置时，登录和行程请求可能访问旧环境。这不是 localhost 开发默认值问题，也不是已证实发生凭证泄漏。

建议：生产必须显式设置 CORE_BACKEND_URL，缺失时启动失败；本地保留明确的开发默认。修改时同步启动指南、示例配置和缺配置测试。

### 3. P2：BFF 旧偏好入口仍包含固定语义规则

[server/index.mjs:563-574](D:/loop-p0-functional-inventory-1-home/server/index.mjs:563) 的 chatPreferencePatch 用固定正则和顺序决定偏好。公开路径 POST /api/chat/proposal（1539-1550）仍接受 UPDATE_PREFERENCE，确认后 1581-1594 调用 Java mergePreferences 保存。

当前源码纯函数探针结果：

| 输入 | 输出 | 问题 |
| --- | --- | --- |
| 以后不要打车，优先地铁 | transportPreference=TAXI | 否定不理解，且打车先于公共交通 |
| 记住我不是素食者 | dietPreference=VEGETARIAN | 把否定解析为肯定 |
| 记住我喜欢甜食也喜欢辣口 | null | 不处理复合饮食语义，随后还有 legacy fallback |

**当前 frontend/web 未发现调用该旧 API；不能说当前主聊天入口必然发生同样问题。** 问题是仍开放的兼容入口构成第二个业务解释权威，且可将错误结果交由 Java 持久化。用户确认存在但不修正解析错误。

建议：核查兼容消费者后退役旧偏好路径；必须保留时转发统一 Java 偏好用例，BFF 不再补词表。应覆盖否定、复合偏好、取消与确认的契约。

## 二、可维护性及低优先级问题

| 编号 | 位置 | 判断与建议 |
| --- | --- | --- |
| A4 | PlannerService.java:233、527、970 | 同时承担会话权限/读写、用例编排、候选评分、HTTP 状态和动态响应 Map。应用层多处仅转发。建议围绕真实变更用例逐步把编排迁至 application、领域只保留规则；不是单纯因为文件大就拆 |
| A5 | PlanAdjustmentService.java:214、285 | 手动 new 策略，再用 instanceof/setter 同步依赖；新增策略通常修改两处中央代码。建议下次扩展时采用完整构造注入和组合根注册，不必引入复杂插件框架 |
| H4 | server/index.mjs:72-85 | 已有 local/redis 状态模式，但 local 下 YUYOUZHICE_MEMORY=1 又被 PORT===3000 与密钥长度条件覆盖。相同显式内存开关，仅换端口就改变是否落盘。应让显式存储配置决定行为、端口只负责监听；本轮是静态条件真值核验，未声称发生数据丢失 |
| H5 | frontend/web/src/features/trip-planning/slots-form.js:6-8 | 缺 durationDays 时显示 2 天、缺 interests 时显示城市/人文/夜景，却标题为“已识别的旅行条件”。隔离调用 slots({}) 已确认。缺值应显示待确认，默认业务值由 Java 返回；正常响应字段齐全时不触发 |
| H6 | frontend/web/src/pages/trips-page.js:48、home-page.js:10/96、guest-chat-page.js:12/26、trip-chat/chat-panel.js:265 | 全国化界面仍将所有已保存行程称为重庆行程，入口和固定资产数量也带重庆。属于文案与统计配置债，不是后台目的地被改成重庆的证据。区分固定品牌文案和当前目的地，数量使用真实目录统计 |

当前生产 Java 最大文件为 PlanAdjustmentService 1694 行、RuntimeLocationPlanner 1580 行、QdrantScenicKnowledgeAdapter 1329 行、ConversationIntentClassifier 1080 行、ItineraryBuilder 1039 行、PlannerService 986 行。规模只用于定位职责审查，不作为 Bug 或过度设计的独立证据。

## 三、已经合理的设计与排除的误报

- Java 有 adapter/application/domain/infrastructure、领域 gateway 和基础设施适配器。当前 360 个生产 Java 文件的文本扫描，未发现 domain 直接 import application、infrastructure 或 adapter。已有 ArchUnit 守卫；前一轮报告中的该类 54 项通过是历史测试结果，**本轮没有重跑 Maven**。
- BFF 认证及正式行程向 Java 委派，浏览器走同源 API，整体权威方向合理。BFF 此次架构检查实际运行通过，25 个 server 模块均在检查范围内；但该规则只检查导入路径和入口，不检查偏好解释或行程约束唯一权威，不能证明业务边界无缺陷。
- Java 的 TaxonomyPort/DiningTaxonomyPort 与资源消费者已连接；餐饮品牌、风味、餐时策略来自 resources/taxonomy，而非在核心服务堆叠食物名单。剩余自然语言语法兼容正则不能一概删掉。
- 指定五城市名（重庆、北京、杭州、西安、武汉）与长经纬度字面量模式，在生产 .java 中均 0 命中。该扫描不涵盖任意城市、运行期拼接、反射或全部资产，不能说全工程零硬编码。
- resources/attractions.json、dining_venues.json 中真实 POI 名称和坐标属于数据资产；测试 stub 里的固定门店、开发 localhost、日统计边界、数学常量不能一概作为缺陷。
- taxonomy 中仍有 baselineDay1 等城市路线模板与时刻。当前消费者检查配置城市及目录条件，并交给 resolveAndVerifyDays，不是所有城市套重庆模板。它属于需维护的城市策略资产，外置不代表实时事实核验；如果产品要求取消固定路线偏好，应另列明确行为变更，不应仅靠 regex 删除。
- PDF 已使用内嵌字体与通用默认文件名，本轮没有重新报告已修的系统字体候选路径。
- frontend/web/index.html → web/app.js 是当前入口；frontend/src 的旧 Vue 原型、生成 .vue.js 不能混算为当前用户路径。旧代码保留会增加搜索和维护噪声，清理前必须核实引用与迁移边界。

## 四、尚未确认的边界与验证限制

上一轮的 restore API 接受备份 trip.constraints/appliedPreferences，而仓库 update 仅更新 current_trip_json 的契约候选仍保留；没有正常 UI 路径复现，不升级为已确认用户 Bug。本次没有安全压力、实时供应商或 HTTP 城市绕过的运行验收，结论来自源码、当前函数隔离探针和 BFF 架构检查。

所有证据在 [cp6-architecture-hardcode-20261016](evidence/cp6-architecture-hardcode-20261016)：静态库存、关键源码哈希、当前源码函数探针与 BFF 架构检查。根智能体核对两名子智能体的独立结果；没有把子智能体自述或前轮测试当作本轮完整运行验证。

## 五、建议的执行顺序

1. 取消生产个人部署隐式回退：改动最小，先阻止跨环境误路由。
2. 合并手工/AI 变更校验：先做跨城候选及硬约束失败回归，再让两个入口共用最小规则集合。
3. 收敛旧偏好入口：确认兼容情况后退役或委派 Java，避免再次词表打补丁。
4. 去掉端口参与存储决策，清理未识别条件的假默认值，再处理全国化界面文案。
5. 随后按用例分解编排和策略装配，保留现有分层与端口；不安排无目标的全项目重写。

本次经验查询阅读了 structured-trip-planning，C3 仅作为候选检查方向，未采用其为结论依据；判断均来自当前源码与探针。效果登记 not_used，记录 C:/Users/summer/Documents/ggy的自动化流程/wiki/tasks/20261016-a45e570548ee.json。

# 聊天板块后端核验与修复

日期：2026-10-12。范围：Java 聊天历史协调器与流式输出、BFF 对话和规划会话契约。未调用真实供应商，未修改用户 env/数据库，未占用用户 8080/3000/5173。

## 确定问题与修复

| 场景 | 原问题/证据 | 修复 |
| --- | --- | --- |
| 已缓存的规划会话被其他用户/设备引用 | `restorePlannerSession` 缓存命中直接返回，跳过恢复分支的账户/设备检查。修前隔离 HTTP 测试得到 200，预期 404，日志 `D:/loop-p0-functional-inventory-1-home/chat-ownership-before.log`。 | 内存与持久恢复共用账户/设备检查；旧 proposal 两入口也复用检查。 |
| 旧聊天方案绕过新版预览/版本权限 | `/api/chat/proposal/confirm` 用旧 shadow candidate 直接写正式行程，使用确认当时读取的新版本，而非提案版本。 | 废止旧修改型创建/确认，返回 410 引导调整模式。删除旧 shadow mutation/正式行程写入逻辑和无调用目标解析函数；保留已有偏好确认契约。 |
| 已登录用户保存、打开后“页面记得、模型忘记” | draft 对话使用 `plan-${sessionId}`，打开正式行程页面切为 `trip-${formalId}`，Java 历史 namespace 不同。 | BFF 签发稳定 `chatSessionId`，正式 workspace 保存，open 恢复，stream 按已授权规划会话强制采用该 ID；忽略客户端伪造 chat ID，不把客户端显示历史当模型历史。缺失/恢复异常分别 404/503，禁止降级到客户端会话。 |
| 60 轮后明确撤销安全限制丢失 | 独立安全原文只保留负向限制关键词，普通明确更正未命中，移出近期窗口后旧限制可能复活。 | 现 taxonomy hints 增加通用修订信号，仅已存在安全限制时保留修订原文。旧事实和修订按时间并存，由模型处理最新明确更正；不自动删除其他过敏事实。 |
| 流式已输出部分答案后报错，下一轮指代缺上下文 | `ChatStreamOutputAdapter` 错误分支仅保存通用失败信息，丢弃用户已见文本。 | 保存已见文本并加“回答未完成”标识；前端仍收到 text/error，不将半截答案当完整成功。 |

## 可重复验证

- BFF `node --test server/*.test.mjs`：26 通过、0 失败、0 跳过。日志 `D:/loop-p0-functional-inventory-1-home/chat-bff-regression-final.log`。
- 新 HTTP 契约包含不同账户（即使知道设备 ID）与不同设备的会话读取/预览/聊天/旧提案拒绝，合法用户正常访问；旧修改创建/确认 410；保存/open 前后实际转发 Java 的模型 session ID 相同；客户端伪造 session ID/workspace chatSessionId 无效。
- Java 专项 `ChatApplicationServiceTest,ChatContextRecoverySafetyTest,ChatLongConversationLifecycleTest,PlanAdjustmentServiceTest`：87 通过、0 失败、0 跳过。日志 `D:/scenic-guide/chat-backend-regression-final.log`。
- 新 60 轮测试使用真实 `ChatHistoryContextCoordinator` 与 mock 模型/内存端口，每轮 user/assistant 共 120 条。摘要故意遗漏安全事实；确认游标持续推进，限制原文、无原症状关键词的撤销原文移出近期窗口后仍按时间保留，花生过敏未被行动限制修订删除，模型窗口有界。
- 初跑该新测试失败 1 项，原因是既有 test resource taxonomy 覆盖主资源但未含新信号，已同步既有 fixture，未削弱原文/顺序断言；原失败日志 `D:/scenic-guide/chat-backend-regression.log` 保留。
- 复用原 11 项恢复安全用例覆盖多次压缩失败、字符预算、连续游标、原始硬约束保留；原 67 项调整测试通过，其中包括旧提案版本/过期、晚间时段、预览不变更与确认后 +1。
- 全量 Java：1187 执行，1173 通过、14 跳过、0 失败、0 错误，BUILD SUCCESS。日志 `D:/scenic-guide/chat-backend-full.log`。14 项跳过包括此前需要真实隔离基础设施的测试，不能计为通过。
- 最终打包 BUILD SUCCESS（21:42:50），日志 `D:/scenic-guide/chat-backend-package.log`；JAR SHA256 `E9681CB671AB3BB2F3403F7FE6E1D231F7F44045A49B2AFCCF9A868439E5A7B9`。打包时跳过测试是复用紧前成功的完整测试，不算新的测试运行。

## 边界

- 外置修订信号是可维护的语言覆盖策略，不能保证任意隐喻/改写都被识别；不宣称自然语言 100% 理解或“0 硬编码”。
- mock 60 轮验证的是本地上下文、顺序和压缩生命周期，不能替代真实模型对全部自然语言含义的验收。真实浏览器语义由独立验收智能体执行。
- 游客先聊后登录仍存在 `guest:` 到账号历史命名空间转换，本报告不将它算作已登录保存/open 连续性通过的范围。
- “清空页面记录”不等同模型忘记。现 Java 删除接口依赖账号所有权且存储异常被吞，异步历史/摘要迟到写回也尚无 epoch 隔离；本轮不接入误导性的全模型 reset。
- PostgreSQL/真实 Redis 多实例隔离与进程崩溃验收不是此离线测试结论。
- 行程上下文恢复抛异常分支已显式 fail-closed 503；目前 HTTP 测试覆盖恢复空结果/越权的 404，没有人为注入存储抛异常来验证该 503，不能称这一故障注入已完成。

## 真实浏览器反馈后追加的契约修复

真实浏览器首次行程聊天能复述专用代号，但说第一站未知。断点确认为 BFF `planChatContext` 只发偏好字段，没有 Java 已支持的 `currentStops`。不是站点数据或模型词语识别问题。

- BFF 从已授权的 `session.trip.days` 顺序投影站点白名单（name/address/day/date/time/duration/summary/aiGuide），无 constraints 时仍提供站点；每字段和总字符预算有界，最多 12 站、8000 字符。先裁剪值/整行再序列化，去掉原 `JSON.stringify(...).slice(0,1800)` 的无效 JSON 风险。
- 发送 `totalStops/omittedByBudget`，Java assembler 合并上游与本地裁剪总数，避免传入 12 站却误报“遗漏 0”。站点保持 USER 数据，不升格成 SYSTEM 指令。
- Java client 原为 GET 查询参数，中文消息和行程上下文 URI 编码后易超过请求行限制。改用 Java 已存在的 POST `/ai/chat/stream` JSON DTO，保留认证、取消和 SSE 生命周期；没有增加 Tomcat 请求行上限。
- 新本地真实 HTTP transport 测试检查长中文及完整 DTO 使用 POST JSON、URL 无 query、认证头存在。BFF 转发测试检查首站来自服务端而非客户端伪造，专项纯函数检查顺序/day/date/time/无偏好/大资料 JSON 可解析及遗漏总数。
- 最新 Node server 回归 28 通过、0 失败、0 跳过，日志 `D:/loop-p0-functional-inventory-1-home/chat-bff-itinerary-context-final.log`。
- 新 Java assembler 专项与聊天原回归 21 通过、0 失败、0 跳过，日志 `D:/scenic-guide/chat-context-forwarding-regression.log`。前面 1187 全量属于追加修复前阶段，后续全量结果以最终日志为准。
- 独立真实浏览器定向复测使用新包，不覆盖、抹去之前的首站未知失败证据。

## 同会话城市更正与检索的一致性

独立审查另确认：模型对话虽记得杭州，检索协调器仅看当轮及偏好 JSON，普通“那里有哪些景点”会落回 taxonomy 默认重庆。该断点已修复，且同一次解析城市共用于 RAG、附近 POI 和按名称查询地点详情。

- 从现会话 `sessionKey` 读取最近 50 条有界原始历史，不另建城市缓存/状态。优先当前明确的正向唯一城市，其次已授权行程结构化城市，再逆序读取同会话 USER 的最近明确城市，最后读取选中站点名称中的唯一城市。ASSISTANT 推荐中的城市不改变用户目的地。
- 使用现 taxonomy 城市别名和 `IntentLanguagePolicy.positiveText`，没有添加手写城市或更正短语规则。当前/最近 USER 提及但被否定或多城歧义时返回未知，不回退旧城市，不按字典序/最后位置臆断。
- 未明确目的地时 RAG 返回需补充城市的原因，不发全国/默认重庆检索；附近 POI 与无 ID 的名称查询跳过。确切 AMap POI ID 可直接详情查询。
- `RuntimeAttractionDetailService.answerReference(reference, city)` 将城市传至高德；原单参 API 保留兼容，但聊天真实路径使用双参。
- 新实际 `ChatApplicationService` 测试贯通 RAG/高德/地点服务，验证杭州更正后指代、其他 session 武汉隔离、当前明确城市优先于结构城市、结构城市优先于旧历史、助手北京文本不劫持、未知/否定/歧义无检索。另验证名称查询实际向 GeoPort 传杭州。
- 城市与上下文专项 26 通过、0 失败、0 跳过，日志 `D:/scenic-guide/chat-city-followup-regression.log`；BFF 最新 28 通过，日志 `D:/loop-p0-functional-inventory-1-home/chat-bff-final.log`。
- 最终冻结包 BUILD SUCCESS（21:57:05），日志 `D:/scenic-guide/chat-city-final-package.log`，SHA256 `DE1D638B0FBD2A09E7A6132A4EBD87D5807BFCD47E80B08FEB0D35EC444A0FA8`。此前两次包为阶段证据，不代替本次最终包。
- 城市别名覆盖及最近 50 条历史是明确边界；历史更久远的普通目的地不能凭此测试声称始终永久保留。多城歧义需用户明确，而非假装理解。

## 最终冻结版本的全量回归

2026-10-12 21:59:24 完成最终源码全量 Maven 测试（命令 `./mvnw.cmd -pl ai test`），日志 `D:/scenic-guide/chat-final-full-regression.log`：1191 项执行，1177 项通过，14 项跳过，0 failures，0 errors，BUILD SUCCESS。此统计覆盖上述站点上下文、POST DTO 与城市连续性最终修复；14 项跳过不计作通过，也不推断为真实基础设施验收通过。

全量测试之后未修改 Java/server 生产源文件；独立真实浏览器验收使用 21:57:05 冻结包，结果由独立验收记录提供。本报告不预先声明真实 6+3 轮通过。

## 真实回复暴露的 SSE 空白保真补修

独立真实回复中 action_chip 属性之间空格丢失。根因是 Java 将模型 token 原样写入 SSE `data:`，内容首空格被标准 SSE 解析作为协议可选空格去掉；独立单空格 token 因而变空，英文词与标签属性拼接。本次只在 `ChatController.toSse` 对 `text` 事件进行 JSON 字符串编码，meta/progress 等已有 JSON 数据维持原契约，浏览器标准解析器不改。

新增 MockMvc POST HTTP 序列化回归，对 `<action_chip`、独立空格、中文带引号属性、英文词、LF/CRLF 分块按标准 SSE 规则解析，再 JSON 解码，精确拼回原文；并核验 meta/progress 仍是对象 JSON。初次 standalone MockMvc 使用默认 ISO String converter，中文被写成问号导致失败；测试显式配置与 Boot 默认一致的 UTF8 converter 后原中文断言通过，初次失败日志 `D:/scenic-guide/chat-sse-contract-regression.log` 保留。

- 专项 21 项通过、0 失败、0 跳过，日志 `D:/scenic-guide/chat-sse-contract-regression-final.log`，22:06:29 BUILD SUCCESS。
- 最新最终包日志 `D:/scenic-guide/chat-sse-final-package.log`，22:06:47 BUILD SUCCESS；SHA256 `84064CCF1ED97927A46D4A4D31FA88953532B3E939A536AE733D51CE3A466A7E`。
- 独立真实 6+3 轮使用前一 DE1D 包，其行为证据不能冒称由本次新包实测；本次只补传输保真契约，不再重复供应商计费多轮。
- 上节 1191 全量属于 SSE 补修前阶段；补修后最终全量结果见后续记录。

SSE 补修后最终全量于 2026-10-12 22:08:40 BUILD SUCCESS：1192 项执行，1178 项通过，14 项跳过，0 failures，0 errors。日志 `D:/scenic-guide/chat-sse-final-full-regression.log`。最终包仍为 22:06:47 的 `84064CCF1ED97927A46D4A4D31FA88953532B3E939A536AE733D51CE3A466A7E`；全量后没有 Java/server 生产源改动。

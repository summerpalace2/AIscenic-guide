# CP6 配置可移植性与单一配置入口

日期：2026-10-13。状态：实施、自查、独立验收和主线程复核完成；仅本地审阅交付。

## 目标与确认缺口

BFF、知识导入、向量检查、检索 benchmark 和 PowerShell 启动/初始化脚本仍固定开发机 D 盘。既有配置检查反而要求这些固定字面量。Java 的唯一示例模板在磁盘、Git 当前及历史中均未找到；两份 BFF 旧模板明确指向该缺失模板。不能通过跳过检查或复制真实配置制造通过。

start-local 按模板清空进程环境后再读文件，与 Java/BFF 进程优先不一致；vector-check 和 benchmark 部分配置在文件加载前求值，导致配置覆盖无效。README 及启动指引还描述旧 Java 绝对路径和“内置可用 key”。

## 实施合同

- 唯一共享示例为 Java 模块 `.env.example`，仅按公开源码配置契约填写空凭据、说明及当前默认值；不读取或写入用户真实 `.env`。
- BFF/工具以进程 `SCENIC_GUIDE_JAVA_DIR` 明确指定 Java 模块目录，相对目录基于 BFF 项目根。无目录时仅使用进程环境，不猜工作区或其他 `.env`。
- `YUYOUZHICE_ENV_FILE_ENABLED=0` 优先禁止文件读取；显式选择的目录缺 `.env` 时明确失败，不静默换源。
- Node 共用纯配置解析/定位模块；BFF 保持字段白名单及服务端凭据隔离，工具在加载后求配置值。进程已定义值始终优先。
- PowerShell `-JavaRoot` 优先于对应进程变量；都未配置时给明确指导。启动器不以模板清空进程配置，子 BFF 环境仍限白名单。
- Java 保持现有 cwd/parent 查找和进程/JVM优先，不另增覆盖链。真实 main 隔离启动验证必需凭据及可选供应商的实际行为。
- 生产模式禁止启用既有测试模拟/会话兼容开关；先确认已有保护，不重复堆叠检查。

这会改变无参数本地启动的旧机器耦合默认：使用者应显式传入 JavaRoot 或设置对应进程变量；部署可使用纯进程配置。只更新源码、示例和操作指引，不迁移用户配置或重启开发服务。

## 分工与退出条件

Java 实施者负责唯一示例、Java 文档和真实 main 启动核验；BFF 实施者负责加载、工具、PowerShell 和配置检查；独立审查者另写行为反例，主线程复读最终调用与证据。

需验证中文/空格异地路径、进程与文件优先级、禁读、显式缺失失败、BFF凭据隔离、真正工具配置覆盖、生产测试开关、PowerShell解析及隔离初始化、真实配置检查、Java隔离main健康及缺必需值失败。上一板块业务源不随配置修复改动；按受影响范围测试，不重复无关全量。最终本地审阅导出，不提交、推送或部署。

不得操作用户8080/3000/5173、真实数据库、共享Redis/Qdrant和付费供应商。测试只用临时目录、SQLite、随机端口和本地/关闭的外部接口；不输出真实凭据。

经验任务：20261013-4a48c26ea0c1。Wiki关键词查询没有匹配主题；上一板块原始经验只用来核对隔离边界，不作为新配置规则的权威。

## 最终复核与交付

- Node 全部实际匹配测试文件：87 run / 87 passed / 0 failed / 0 skipped（portable-config-node-all-final.log）。其中配置专项20项，包含独立反例9项；不重复相加。配置契约检查退出0，126个活动键、35个注释兼容别名，浏览器密钥边界通过（portable-config-contract-root-final.log）。
- Java实际main七个隔离场景：缺JWT、空模型key各按预期拒绝；本目录文件、进程覆盖、JVM覆盖、父目录文件和纯进程配置各readiness 200。五个成功启动场景整体health仍503，因为Redis刻意不可达；没有真实基础设施通过结论。来源java-config-main-seven-final.log及CP6_PORTABLE_CONFIG_STARTUP_RESULT_20261013.json。
- 实际BFF入口在中文空格临时目录和随机端口启动；进程PORT覆盖文件PORT、指定文件公共地图key生效、响应不包含私有模型/地图key。未访问外部上游。来源portable-config-bff-startup-final.log。
- Java生产源码、包未改变，SHA256仍61CF86F0905BE968FD97C85AFD8284573B6DA3D6DDED0C33D84E3A81D055700B。本板块没有重新执行Maven业务全量或前端构建，上一板块结果不计成本轮新测试。
- 旧本地导出遗漏Git忽略的工具/报告，证据CP6_CONFIG_EXPORT_GAP_BEFORE_20261013.json。本轮导出器支持逐文件显式选择并校验路径及哈希，4项临时Git回归通过（portable-config-export-test-final.log）。新候选同时补入上一板块生命周期工具/报告；不把旧候选改称完整。路径排除不是全量密钥扫描。

实施报告：CP6_CONFIGURATION_PORTABILITY_BACKEND_20261013.md；BFF docs/portable-configuration-acceptance-20261013.md。独立报告：CONFIGURATION_PORTABILITY_INDEPENDENT_ACCEPTANCE_20261013.md。独审另外编写并运行9项测试，主线程执行全Node、配置检查、真实BFF入口和导出测试，避免把实施者自述当作独立证据。

本地候选目录：C:/Users/summer/Documents/Codex Deliveries/CP6-20261013-portable-config-review-final。候选包括三个仓库既有未提交改动，不是本轮独占改动清单；manifest记录HEAD、文件哈希、显式选择和排除。未提交、推送、部署或重启用户服务。

启动变化：先运行init-local-config.ps1 -JavaRoot <Java模块目录>，自行补入必需模型key，再运行start-local.ps1 -JavaRoot <同目录>；已有配置可直接start。Node工具通过进程SCENIC_GUIDE_JAVA_DIR选择目录，部署可只使用进程变量。缺配置不会偷偷初始化或换源。

真实PostgreSQL/专用Redis/Qdrant、多实例与崩溃恢复、外部付费供应商及全国经核验知识资产仍未验收；配置可移植不等于这些板块已完成。首次Java验证误以整体health200为启动门槛，改为分别核验readiness与记录health，保留初次日志；既有env隔离测试的旧路径预期也保留失败证据后按新合同更新。

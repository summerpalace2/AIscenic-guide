# CP6 真实基础设施与多实例验收

日期：2026-10-13。状态：本轮Windows隔离环境内实施、自查、独立审查与根线程验收完成；不是Linux生产环境或完整BFF多实例验收。

## 范围与环境事实

本轮承接配置板块，完成现有真实 PostgreSQL/Redis 探针，并补正式行程并发业务验证。独审确认现有四项测试确实调用真实初始化/Redis仓储，但双JVM只覆盖DDL，崩溃只覆盖自建标记与proposal，不能代表正式行程事务或两个HTTP后端实例。

用户指定 ai/.env 后，只核验结构：实际数据库为SQLite，未配置CP6_IT_*；未输出值或将其转为可写入测试目标。用户随后明确允许搭建本地隔离PostgreSQL、Redis。便携运行只用独立目录、测试账号、loopback端口，不改系统服务、全局PATH、用户env或8080/3000/5173。

## 工作与退出条件

1. 实施者修正探针连接超时、worker环境隔离与恢复断言；增加真实TripService/TripRepository事务并发验证，保留修复前失败。
2. 根线程增加显式专用测试文件入口，配置只接受CP6_IT白名单并按数据读取，拒绝执行/展开、业务配置和静默换源，结束恢复进程变量。
3. 单独智能体搭建真实便携服务，记录发行来源、版本、启动/停止方法及专用配置路径。无法获得目标版本应明示限制，不能用协议模拟冒充Redis。
4. 执行真实数据库迁移、双JVM并发、RedisL1撤销及延迟发布、提交后Java进程kill恢复、正式行程版本冲突与首次幂等保存。发现真实生产缺陷再最小修复并回归。
5. 实施自查后，独立审查者检查最终源码与真实证据；根线程核对测试统计、隔离边界、进程停止及本地候选。

仓储层通过不等于HTTP多实例完整通过；数据库/缓存服务自身重启持久恢复需另行验证，现tmpfs/Redis关闭持久化Compose不能证明。外部供应商保持禁用，城市资产与远程交付不纳入本轮。任何skip不能记作真实基础设施通过。

## 当前证据

- 独审范围：REAL_INFRASTRUCTURE_INDEPENDENT_SCOPE_20261013.md。
- 显式测试配置解析9个隔离用例通过：infra-config-parser-final.log；尚未作为真实服务通过结果。
- 经验检索未命中Wiki主题；读取旧PG初始化/schema隔离记录仅确定待验证边界，不能用其mock/SQLite证据替代本轮真PG。使用记录20261013-0d39527565b3。

## 确认缺陷与最小修复

首次真实运行10项中9通过、1失败、0跳过，保留infra-real-before.log及CP6_REAL_INFRA_BEFORE_20261013.xml。两个JVM都读到未占用的幂等键后再同步保存，落败进程在TripRepository普通INSERT遭遇唯一键冲突；TripService捕获异常后同事务replay查询，真实PostgreSQL报SQLSTATE 25P02（current transaction is aborted）。首worker日志在C:/Users/summer/AppData/Local/Temp/cp6-formal-7994770643265336961/first.log。

仅修改TripRepository.reserveIdempotency：精确幂等联合键ON CONFLICT DO NOTHING，未插入时在Java层发出原DuplicateKeyException信号，继续既有领域回放；不让SQL唯一冲突破坏事务，不吞其他约束错误、不新增重试框架。新增SQLite兼容测试验证原占位不覆盖、owner/operation作用域、非目标约束仍传播。

测试工具补强：admin连接也加超时、worker只继承OS/专用测试变量、恢复对比完整proposal；双JVM测试在首次真实读取后设屏障，拒绝用串行回放冒充并发。HTTP工具拒绝Python优化模式以防断言被关闭，统一由PowerShell解析配置，SQL失败保留局部错误日志。独审提出的这三项工具问题已修。

## 最终执行证据

| 范围 | 结果 | 证据 |
| --- | --- | --- |
| 真实PG/Redis仓储及目标守卫 | 10 run / 10 passed / 0 skipped / 0 failures/errors；含6真实服务探针与4目标守卫 | infra-real-after.log、CP6_REAL_INFRA_AFTER_20261013.xml |
| 行程领域/应用/适配器/SQLite兼容9类 | 54 run / 54 passed / 0 skipped / 0 failures/errors | infra-trip-regression-final.log |
| 显式配置parser | 9 cases passed；3个PS文件AST通过 | infra-config-parser-final.log |
| 实际HTTP双实例 | 首次运行4项检查通过：共享用户鉴权、同键一次创建、同版本编辑一成功一409、peer强杀后另一实例读取v2；SQL核验行程及版本数量 | infra-http-first.log、CP6_REAL_INFRA_HTTP_20261013.json |
| PG/Redis服务自身强杀恢复 | 首次运行通过；PG WAL自动恢复已提交marker；Redis同连接SET→WAITAOF确认本地AOFfsync后强杀恢复marker；各自原data目录重启，随机资源清理、fsync策略还原 | CP6_REAL_INFRA_SERVICE_CRASH_20261013.json；原runtime service-crash-attempt-1.log及postgres-crash-restart.log |
| 最终打包 | BUILD SUCCESS，SHA256 4C1156FF566483DA997A789D619100601C46FEBBA8465B55AE541BDD61D81205 | infra-package-final.log |

本轮按相关变化运行测试，没有重新声明1230项旧全量或前端build为当前新成绩。根线程串行执行Maven/HTTP，服务强杀在所有其他测试结束后的独占窗口进行；实施者自查与独审裁决分开。最终独审见REAL_INFRASTRUCTURE_INDEPENDENT_SCOPE_20261013.md。

## 环境、清理和交付边界

PostgreSQL16.15来自官方Windows下载链路的EDB ZIP；其SHA为本地观测值，没有独立发布者校验声明。Redis7.4.3为社区Cygwin真实移植，ZIP哈希与发布页一致，不能代表官方LinuxRedis兼容验收。来源/版本/哈希见CP6_REAL_INFRA_RUNTIME_20261013.json，连接烟测见CP6_REAL_INFRA_PROVISION_SMOKE_20261013.json。

环境位于C:/Users/summer/AppData/Local/Temp/cp6-infra-25a17aeef88e，仅loopback随机端口，独立账号与数据。未改项目真实env/系统PATH/服务注册/用户开发端口。配置和密码留在该隔离runtime，不加入交付。首次PG控制进程管道等待及纠正过程在runtime README中保留，不冒充首次搭建即全绿。最终runtime-control.py stop成功，本次PG/Redis监听已消失；没有删除数据证据。

本地审阅候选：C:/Users/summer/Documents/Codex Deliveries/CP6-20261013-real-infrastructure-review-final。manifest包括三个仓库既有未提交变化，不全归属本板块；未提交、推送或部署。

仍待完成：真实Linux/部署拓扑、真实Qdrant、BFF双实例完整用户链（proposal exactly-once/open丢回执/聊天跨实例等）、全国经核验知识资产及外部供应商完整验收。本轮证明了具体真实PG缺陷已修、上述迁移/并发/恢复范围通过，不宣称所有系统逻辑已无问题。

# 配置可移植性：Java 侧实施与核验

## 源码事实与最小改动

只读检查没有发现 Java 仓库已有的 env 示例模板；`git ls-files`、当前状态与 `git log --all` 均未发现 `ai/.env.example` 的 tracked 记录。这不是本轮删除。Web 两份旧模板已标为废弃，但仍指向不存在的 Java 模板。

新增唯一共享 `ai/.env.example`，字段来自公开 application.yml、Java @Value 与 BFF/导入工具契约。按 REQUIRED_LOCAL_CORE、OPTIONAL_FEATURE、LEGACY_ALIAS、PRODUCTION_ONLY 分类，兼容别名为注释，不激活另一套配置。没有读取、复制或输出真实 `.env` 值。

Java loader 没有修改：当前目录 `.env` 优先，然后父目录；进程环境和 JVM 参数优先于文件注入。已有开发机器绝对路径在上一板块删除。README 和 STARTUP_GUIDE 删除过时的机器路径、虚构同仓库目录与“预置可用 Key”声明，引用同一模板，并区分进程启动、供应商授权和基础设施健康。

## 真实 main 验证

工具 `tools/cp6-config/verify-java-startup.py` 使用已有 jar 的真实 main，不通过测试 Spring 配置绕过启动逻辑。每个场景使用新的临时目录和 SQLite；子进程只继承必要 OS 路径变量，所有 HTTP 供应商指向本地受控服务，Redis/Qdrant 指向关闭的 loopback 端口。没有重启用户进程，也不接触共享数据库或收费供应商。

首轮 `java-config-main-verification.log` 已保留：缺 JWT 与空模型 Key 的预期失败通过后，local-file 场景由于聚合 health=503 被工具误认为应用未启动。日志表明应用已启动，Redis 因隔离关闭而不可用；没有以禁用健康检查或放宽生产配置隐藏此事实。工具改为读取 readiness 判断应用就绪，另外记录聚合 health 状态。

六场景 `java-config-main-verification-final.log` 退出0：缺 JWT 以明确错误退出；有效 JWT 加空 DEEPSEEK_API_KEY 由 Spring AI 初始化拒绝；cwd 文件、父目录文件、进程覆盖无效文件 JWT、JVM 覆盖无效文件 JWT 均 readiness=200。四个就绪场景 aggregate health=503，符合隔离关闭 Redis 的实际情况。这不是完整基础设施健康证明。

模板因此将 DEEPSEEK_API_KEY 标为当前启动必需，而非承诺全部供应商留空仍可运行。非空假 key 仅用于本地隔离验证，不能证明聊天模型有效授权。没有为此添加模型禁用开关或额外配置框架。

增加第七场景证明没有 `.env` 时纯进程环境即可启动；最终七场景结果待运行完成追加。Java 生产源码及打包产物均未变，不把本轮文档/模板改动误报为重新执行了全部 Java 套件。上一板块同包 SHA256 为 61CF86F0905BE968FD97C85AFD8284573B6DA3D6DDED0C33D84E3A81D055700B。

最终 `java-config-main-seven-final.log` 七个场景退出0：两个预期配置缺失拒绝启动，五个合法配置就绪（包括无文件纯进程配置），聚合 health 如实为503。机器摘要为临时目录 `cp6-java-config-lbeh2ns_/result-summary.json`。工具 Python 语法编译、指定改动 diff --check 均退出0；产物 hash 再次核对不变。启动工具不读取用户 env，也不打印 fixture 密钥；日志与受控数据库留在临时目录供核验。

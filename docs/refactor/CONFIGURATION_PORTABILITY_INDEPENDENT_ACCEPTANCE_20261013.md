# 配置可移植性独立审查

日期：2026-10-13。独审新增 `D:/loop-p0-functional-inventory-1-home/scripts/portable-config-independent.test.mjs`，未修改生产实现、运行 Maven、访问真实 `.env` 值或启动用户服务。

## 独立行为验证

`portable-config-independent-final.log`：9 tests /9 passed /0 failure /0 skip。测试每项启动只继承 OS 必要变量的独立 Node 进程，使用临时中文与空格路径下的合成 `.env`；read spy 截获任何非 fixture 的配置读取，确保不会读取个人配置。

1. 没有 Java root 时纯进程配置，零文件读取。
2. 显式 root 缺文件时明确失败，不回退个人或其他目录。
3. 禁读开关先于显式 root 处理，缺失 root 不阻塞纯进程启动。
4. 中文空格路径可用，无 `.env.example` 也不改变运行加载行为；同名进程值优先，BFF allowlist 不导入 fixture 的模型凭证。
5. 相对 root 基于 BFF 项目目录，不受子进程 cwd 影响。
6. 纯进程 production 模式分别拒绝 TEST_FIXTURES、TEST_SESSION_COMPAT、JAVA_TEST_STUB。
7. production 也拒绝从显式合成文件加载的 JAVA_TEST_STUB，而非仅检查进程输入。

首跑执行前实施者已完成 shared 模块，所以 `portable-config-independent-first.log` 是8项通过的首次结果，没有伪造修前红灯。之后增加文件中测试开关反例，最终9项通过。

## 源码复核

`server/config-source.mjs` 是纯模块，导入不读取配置；一个显式 SCENIC_GUIDE_JAVA_DIR 指向唯一 `.env`，不存在时抛错。未新增文件覆盖链。BFF单独传 allowlist，工具按其职责加载，不复用 BFF 的凭证删除过程。

工具已在读取 corpus/source/manifest、benchmark timeout/SQLite 路径之前加载配置，修复原 vector-check 先取变量的问题。benchmark 的相对 SQLite 文件基于明确 Java root，非 SQLite/内存库明确不适用，不偷偷查旧 D 盘数据库。

PowerShell 启动/初始化/管理员配置脚本支持参数优先于 root 进程变量，缺 root 明确提示；相对路径基于脚本所属 BFF 根。启动脚本同名进程配置优先，不再按模板删除进程变量；子进程权限筛选不依赖模板内容。缺 `.env` 不再自动生成 secret，而是提示显式初始化同一个 root。production 测试开关在启动子进程之前拒绝。此处为源码复核，独审没有实际启动 Java/BFF；PowerShell隔离验证统计由实施者另行提供。

唯一 Java 示例由公开配置声明恢复，真实文件不参与生成；旧示例变为弃用指引。Java保留 cwd/parent 配置规则，本轮不引入额外开关；独立隔离通过临时 cwd 空文件实现，不能宣称 Java 本身新增 process-only 禁读能力。

## 候选审阅包扩展

只读复核 `tools/cp6-delivery/export_candidate.py` 新 `--include repository:single-file`：resolve后校验仓库范围，必须是单文件，保持运行数据/凭证/生成物排除与 symlink 检查；ignored 源码进入 files 和 explicitFiles，回验重新核对清单与哈希。原4个隔离 unittest 覆盖 ignored 导出、排除规则、越界/缺失拒绝及改动检测。

独审另在临时目录尝试以 Windows 大小写 `SERVER/DATA/private.json` 绕过排除，被 resolve规范化实际路径后正确拒绝，没有把该猜测写成漏洞。该工具明确不是完整 secret scan；源码文件中的嵌入秘密仍需独立检查，不能声称按扩展名排除即绝对无泄漏。

## 边界

本轮验证加载契约和路径可移植性，不证明生产基础设施连通、真实供应商鉴权或多平台PowerShell启动全部通过。未做任何真实账号或管理员配置变更。经验记录与最终整板统计由主智能体统一维护；本独审不重复建立经验来源。

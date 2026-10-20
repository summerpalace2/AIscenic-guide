# CP0 验收场景与现有测试映射

日期：2026-10-09。此文记录 CP0 静态审查及基线运行结果。覆盖层级是当前工作区测试代码的范围分类；“直接/部分”表示存在对应断言，不代表完整端到端或其他测试没运行过。

## 测试运行基线

| 范围 | 结果 | 证据 | 限制 |
| --- | --- | --- | --- |
| Java 局部规划、追问、草稿、状态和 ArchUnit | 132 项，0 失败，0 错误，0 跳过 | [Java 原始日志](C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP0-Java回归.log) | 独立临时 SQLite；含 mock/Spring 测试；非 BFF/浏览器或多实例 E2E |
| BFF server、scripts 与前端 web 源码测试 | 104 项，0 失败，0 跳过 | [Node 原始日志](C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP0-Node回归.log) | 多个 BFF 测试执行抽出的路由片段+VM 假 Java；前端 mock fetch，不代表 HTTP 或浏览器 E2E |
| BFF 语法与 Vite build | `node --check` 与 build 成功；Vite 60 modules | [Frontend 构建日志](C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP0-Frontend构建.log) | 不等于 UI 实际交互或服务端链路通过 |
| 三库工作区身份 | Java 27、BFF 11、Frontend 13 个已有改动/未跟踪条目 | [最初基线清单](CP0_BASELINE.md) | 计数是本轮启动基线；子智能体随后只新增独立审查文档。后续每个候选重新冻结身份 |

## V01–V16 覆盖映射

静态结论由 C 先独立审查实施前的测试文件得到，主智能体随后运行部分相关回归。该矩阵不是完整的测试事实数据库；每个 CP 提交前仍要对实际 diff 和测试方法重看。

| 场景 | 当前覆盖 | 主要证据/差距 | 后续门槛 |
| --- | --- | --- | --- |
| V01 预览不改持久行程/revision | 直接，Java 服务层 | `PlanAdjustmentServiceTest.test01_previewReplacementDoesNotChangeDurablePlanOrRevision`、`test24_reduceDayDensityGeneratesPreviewWithoutMutatingTripOrRevision`；不包含 BFF/UI | CP2 的新用例编排需复用并检查持久副作用 |
| V02 澄清承接与纠正 | 直接，规则层 | `ConversationContinuationRegressionTest`、`ContinuationCorrectionBoundaryTest`、`CompoundTimeAndCancellationTest`；未覆盖进程恢复 | CP2 保持槽位继承；CP1/CP3单独验状态恢复 |
| V03 甜食语义与跨城搜索 | 部分 | 有甜食措辞/无候选、城市策略用例；未找到单测同时验证实际城市参数、供应商结果与两种表达 | CP5 餐饮/搜索切片补真实网关契约；不阻塞 proposal store 切片 |
| V04 当前方案 tab 与旧 tab | 部分 | 有 planning-loop / proposal availability 状态测试；无浏览器真实点击/陈旧 revision 完整链路 | CP4/UI 切片门槛 |
| V05 幂等与同键异请求 | 部分 | 局部 apply 同键不重复 revision；正式 trip create 有同键异指纹；缺 apply 同键异 proposal/option 契约断言与重启回执 | CP3 必须明确 fingerprint 字段和重试回执 |
| V06 旧版本/跨会话/过期提案拒绝 | 直接，Java 服务层 | `PlanAdjustmentServiceTest.test03`、`test04`、`test05` 等；拒绝无 DB 副作用断言不完全一致 | CP1/CP3 的端口契约统一核验无副作用 |
| V07 真取消与否定取消 | 直接，领域/服务层替身 | `CancellationProposalLifecycleTest` 与 `CompoundTimeAndCancellationTest`；部分仓储为 Mockito mock | CP1 保持取消失效行为；CP3 加真实状态协调测试 |
| V08 cancel/apply 两种交错 | 部分，重要缺口 | 现有 `CountDownLatch` 测试覆盖 apply 已进仓储后 cancel 到达；缺 cancel 先完成、apply 后到的对称路径，且仓储被 mock | CP3 前补双向屏障场景；并检查最终 DB/revision |
| V09 取消后旧预览晚到 | 无直接场景 | generation callback 有相邻覆盖，未验证旧 preview 在 reset 后恢复并尝试重写 proposal | CP3 前补可控延迟与状态最终值测试 |
| V10 提交后回执丢失/缓存故障 | 部分 | 单进程 apply 幂等；BFF restore 缓存写失败分支用 fake Java；缺 apply commit 后回执丢失重试 | CP3 设计边界并补故障注入；与数据事务分开陈述 |
| V11 Redis 故障/新实例恢复 | 部分，重要缺口 | ProposalStore 本地测试、过期与容量；无从 Redis 新实例恢复完整 proposal+epoch 的测试 | CP1 必须验证 Redis 降级和新实例旧 epoch fail-closed；真实 Redis 执行另报 |
| V12 固定站/营业/路线/force | 部分 | 多份时间、营业、地理和固定站测试分开覆盖；无一个组合矩阵覆盖完整业务要求 | 相关 CP 逐条列不变量，不因单项通过宣称组合全通过 |
| V13 复合队列逐项确认 | 部分 | 有跨项、拒绝后不回队列等用例；缺完整序号、跳过、应用后剩余不重放闭环 | CP3 前补队列最终状态和回放次数 |
| V14 类型与 Map 往返 | 部分 | 未跟踪的 `StopPlanRoundTripTest` 验 pilot extras/null/false；Trip/session 持久化往返未覆盖 | CP2 扩展类型前确认可携带与兼容 fixture |
| V15 保存/打开/恢复/继续变更 | 部分，多层拼接 | Java 服务/JDBC、BFF fake Java 片段、Frontend mock fetch 各有测试；不是同一真实端到端 | CP4 前构建至少一条本机真实 BFF→Java→临时 DB；UI 独立标证据层级 |
| V16 切账号/工作区后旧响应 | 无直接场景 | 有后端 Trip owner 测试；没找到延迟旧响应在切换后回写 UI 的前端测试 | CP4 前补延迟响应/状态不变测试 |

## 测试资产可携带性

本轮工作区存在重要未跟踪资产：Java `StopPlanRoundTripTest.java`、`PlannerDraftRestoreTest.java`、`MemoryRepositoryPortContractTest.java`；BFF `draft-restore-route.test.mjs`；Frontend `draft-backup-restore.test.mjs`。Frontend 的 `proposal-availability.test.mjs` 已修改。它们在当前 dirty 工作区可参与测试，但从各仓库 HEAD 冷检出无法获得同一内容。

CP0 为本地重构确立 dirty baseline 并将限制记录在 [基线清单](CP0_BASELINE.md)，不把未跟踪测试称作已提交、可干净检出。用户选择是否提交这批既有改动属于后续 Git 操作，不是本轮重构默认执行。每阶段候选必须以 HEAD + tracked patch hash + 相关未跟踪文件 hash 冻结。

## CP0 裁决

**CP0：通过，可进入 CP1，附有范围门槛。**

通过的依据：三库身份与 dirty 内容已记录；调用链与状态报告完成；V01–V16 已逐项映射且缺口单列；主智能体运行了核心 Java 132 项及 Node 104 项和前端构建，均无失败。

V08、V09、V16 等缺口不阻止不改变并发生命周期的 CP1 提案存储端口切片；它们阻止依赖这些边界的 CP3/CP4 宣称通过。CP1 需要新增/复用自己的 Redis 故障、新服务实例 fail-closed 和 TTL 契约测试。新任务开始前重捕候选身份。

CP1 候选中发现的架构问题必须回到触发它的真实入口；不得以 mock 通过宣称跨层成功。状态恢复只能按单实例契约表述。跨实例续接仍在条件阶段 CP8。

以下依据不是本轮独立运行发现：`CP0_STATE_AUDIT.md` 关于 session 创建与初始 revision 缺少可见事务边界，是源码风险，需要独立故障复现后确定实际影响；跟踪并另派任务，不视作已证实的数据不一致漏洞。

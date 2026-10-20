# CP1 架构独立审查：ProposalRepositoryPort

日期：2026-10-09  
审查角色：B，独立只读架构审查  
审查对象：冻结候选 `D:/scenic-guide` 的 CP1 proposal repository 切片  
冻结清单：`C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP1候选冻结.json`  
冻结清单 SHA-256：`448361d3f3c229a068db33cac26bd8eddd35193c7d83725d82a0f7461e54caa8`

## 裁决

**CP1 的核心依赖倒置改动通过源码审查；没有发现因搬迁 ProposalStore 而引入的已证实业务回归。** 具体来说，缓存实现已从 `domain.planner.service` 移到 `infrastructure.planner.proposal`；`PlanAdjustmentService` 的提案存储依赖是 `ProposalRepositoryPort`；容器实现实现该端口；原有生产代码调用方均已迁到端口。

仍有三类需明确的边界：Redis 读写删除是 best-effort，Redis 缺失或出错时端口无法区分“未找到”和“存储不可用”；L2 异步写入与删除有竞态，但应用路径的绝对到期、revision 和 epoch 检查会拒绝旧提案；epoch 仍在 JVM 内存，所以 Redis 恢复 proposal 不代表跨重启/跨实例可以恢复完整应用能力。这些是可观察到的可用性与契约限制；其中缓存竞态是否在真实环境发生、频率如何，本次没有运行时复现。

## 冻结完整性核验

- 冻结 JSON 的 SHA-256 与请求提供值一致。
- 当前 `git diff --binary HEAD` SHA-256 与冻结清单的 `tracked_patch_sha256` 一致。
- 冻结清单记录的所有 untracked 文件 hash 均与当前一致；CP1 切片文件 hashes 全部一致。
- `git status --porcelain=v1 -uall` 与冻结状态完全一致。常规 `git status` 会将若干 untracked 目录折叠显示，这是展示粒度差异，不是工作区变化。
- 本审查报告是本次新增的独立文件，不属于冻结的 CP1 业务切片；没有改动任何源代码、既有报告或用户原有工作区内容。

## 逐项核对

### 1. 缓存实现是否从 domain 移出：通过

- 旧文件 `ai/src/main/java/com/ai/guide/domain/planner/service/ProposalStore.java` 在冻结状态为删除。
- 新实现位于 `ai/src/main/java/com/ai/guide/infrastructure/planner/proposal/CaffeineRedisProposalRepository.java:1-21`，类实现 `ProposalRepositoryPort`。
- 新端口位于 `ai/src/main/java/com/ai/guide/domain/planner/gateway/ProposalRepositoryPort.java:1-13`，仅暴露 `put/get/remove` 和领域提案模型。
- `PlanAdjustmentProposal.java:1-18` 不再导入 service/store 类型，TTL 常量归模型自身所有。

### 2. PlanAdjustmentService 是否经端口访问：通过

- `PlanAdjustmentService.java:17` 导入 `ProposalRepositoryPort`；`:76` 字段为 `ProposalRepositoryPort`；主要构造器与兼容测试构造器 `:111-164` 均接收端口。
- 对生产源码执行全量文本检索，未发现 `ProposalStore` 旧类型或 `CaffeineRedisProposalRepository` 在 production domain 中被引用。Service 仍使用字段名 `proposalStore`，但字段静态类型是端口，不影响依赖方向。

### 3. Spring 端口装配：源码符合，运行装配未实测

- `CaffeineRedisProposalRepository.java:19-20` 使用 `@Component` 并实现端口。
- 全部生产源码目前只找到这一个 `ProposalRepositoryPort` 实现；Spring 应用从根包扫描时能发现它，未看到限定到其他实现的 `@Qualifier`。
- 本次不运行 Maven，也未启动完整 Spring 上下文，因此这里是静态装配判断，不是运行时 Bean 装配验收。

### 4. TTL 与绝对过期：业务有效期由绝对时间把关

- `PlanAdjustmentProposal.java:19` 定义 15 分钟默认 TTL；`:56-58` 以 `expiresAt` 判过期。
- `CaffeineRedisProposalRepository.java:24-27` 使用相同常量作为 L1 expire-after-write；`:38-42` 无 Redis 写入剩余时间小于等于 0 时不写 L2。
- `:63-79` 的 L1 读取校验绝对到期；`:84-96` 的 Redis 回补也校验绝对到期。
- Redis TTL 使用 `Math.max(60, remainingMs / 1000L)`（`:47-50`）。因此剩余有效期短于 60 秒时，Redis key 可能保留超过业务有效期；剩余较长时整数秒取整可能令 key 稍早消失。二者都不改变逻辑有效期，因为读取时还有 `expiresAt` 校验。前者可能多占用 Redis 一小段时间，后者可能让可恢复窗口略短于绝对到期时间。

### 5. Redis 降级与错误语义

- `put` 的 Redis 序列化/写入在异步任务中捕获异常并 debug 记录（`:51-60`），调用方不获知 L2 写失败；L1 仍保留。
- `get` 捕获 Redis 读/反序列化异常（`:80-99`），最终返回 `null`；若 L1 miss 且 Redis 短暂不可用，服务层看到的也是“没有 proposal”。
- `remove` 删除 L1 后尝试同步删除 Redis，删除异常被忽略（`:102-116`）。若删除失败，陈旧 Redis key 可以存留至 TTL；`remove` 的返回值只来自 L1 命中，Redis-only 的 proposal 即便被成功删除，返回值仍可能是 null。当前调用方忽略 `remove` 返回值，因此没有发现现有调用行为受此影响。
- `PlanAdjustmentService.java:726-736` 在 proposal 返回 null 时用 400 表示“提案不存在或已过期”。在 Redis-only 状态发生 L2 故障时，前端无法区分丢失、过期与存储故障。这是 fail-closed 的结果，但错误语义可能让用户重复生成预览。

### 6. 异步写入和删除竞态

- `put` 先同步写 L1，再提交 `CompletableFuture.runAsync` 写 Redis（`:38-60`）；`remove` 立即删 L1 并同步请求 Redis delete（`:102-116`）。没有同 key 的版本号或任务串行机制。
- 触发条件：Redis 写任务排队期间，apply 成功或过期路径调用 remove；若 delete 先完成、旧 put 后完成，旧快照可能重新出现在 Redis。
- 这是由操作顺序能推导出的竞态，不是已实测发生。对 apply 路径，`PlanAdjustmentService.java:725-746` 仍校验 session base revision、proposal expiry 和 epoch；对成功应用的当前实例，随后 epoch 也会推进。因此旧 key 即使残留，也不能仅凭缓存命中绕过这些应用门禁。
- 影响主要为陈旧 key 暂留及额外 Redis 流量/空间。建议后续若提升多实例恢复能力，再用 proposal 状态版本/条件删除或串行写队列解决，当前不作为 CP1 切片阻塞项。

### 7. Architecture guard：方向规则通过，规则本身覆盖有限

- `ColaArchitectureGuardTest.java:156-164` 禁止 planner domain 依赖 Caffeine 与 Spring Redis 类；`:166-170` 要求 `infrastructure.planner.proposal` 包内类可赋值给 `ProposalRepositoryPort`。
- 该规则与当前结构一致，能防止直接依赖回流；但它只检查列出的两个缓存客户端，不验证 Redis 错误语义、TTL 等行为，也不是对整个 domain 技术依赖的完整约束。
- 新增 `ProposalRepositoryPortContractTest.java:44-74` 覆盖基本 put/get/remove、过期、模拟 Redis 失败下的本地行为；`:78-129` 覆盖重建 service/repository 后 epoch 不匹配时 fail-closed、session revision 不变。
- 实现报告提供了隔离副本 83 项定向测试通过的日志。按本次要求未运行 Maven；我只读了代码和既有报告，因此不把该测试结果当作本轮独立复跑证据。真实 Redis、多进程和并发竞态也未被这些 mock 测试证明。

## 问题与建议清单

| 严重度 | 性质 | 发现 | 建议 |
|---|---|---|---|
| P2，条件性可用性 | 已证实的错误语义限制 | L1 miss 时 Redis 读取失败被折叠为 null，service 返回 400“提案不存在或过期”。当 Redis 是跨实例恢复所需的唯一副本时，瞬时故障会呈现为用户可见的提案丢失。 | 后续可让端口返回区分命中/未命中/后端不可用的结果；针对后两者返回可重试状态。若始终接受 fail-closed，可记录该行为为 API 契约。 |
| P2，建议项 | 源码可证实、运行时未复现 | 异步 Redis put 与 remove 无序列化，同 key 可能删除后复活；当前 epoch/revision/expiry 门禁限制应用。 | 如未来依赖共享 Redis 恢复状态，再为缓存条目增加状态版本/条件写删，并加并发故障注入测试。 |
| P3，契约清晰度 | 已证实 | `remove` 返回值只反映 L1 项，Redis-only 条目的删除仍返回 null；当前调用方不使用该返回值。 | 在端口注释中明确“返回本地旧值”或统一为无返回值，避免将来调用者误以为返回被全局移除项。 |
| CP1 范围之外 | 已知架构限制 | proposal 虽可从 Redis 回补，epoch 在 `PendingIntentContinuations` 本地内存；重启或切实例后匹配不了旧 epoch，proposal 会被安全拒绝。 | 这符合当前 fail-closed 设计。若目标要求重启/多实例无感恢复，应把 epoch 与状态版本移至共享权威层；该工作不属于本次 port 搬迁。 |

## 验收建议

CP1 的核心验收条件已经由静态源码满足：domain 只依赖 proposal port，技术实现归 infrastructure，既有调用点已迁移，architecture guard 有对应边界规则。建议记录为**架构切片源码审查通过，运行集成装配与真实 Redis 故障边界待后续验证**。不应据此推断 proposal 在多节点或服务重启后能够无缝恢复。

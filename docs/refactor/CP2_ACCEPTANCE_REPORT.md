# CP2 验收报告：预览用例切片

日期：2026-10-09  
裁决：**已验收（有限 Preview 用例切片）**

## 验收对象

本次验收针对冻结候选 `outputs/20261009-CP2-candidate.json`，SHA-256：

`a427e5c1c3cf14ed5d002f89c164746ef1ccdec679af8fb673ff5b70ba051ffa`

清单中的 tracked patch SHA-256 为 `5bfe8d15042827e6b67264e07008fe61dfb53ca37a3bd8e3b0b49a72886484e4`。主智能体独立核对候选文件哈希、补丁哈希及 57 个源码/报告/日志条目，未发现不匹配；B、C 的只读审查也核对了相同冻结身份。

## 实际交付

`/sessions/{id}/conversation` 与 `/sessions/{id}/adjust/preview` 现在经过 application 层的 Preview 用例。应用层接收 adapter 提供的 `PlanningActor`，验证用户归属或匿名会话 capability、检查基准 revision，调用领域预览规则，并通过 `ProposalRepositoryPort` 写入提案，最后组装不含数值 HTTP 状态的 typed outcome。Controller 再映射 HTTP 状态并原样返回响应 body。

领域继续拥有意图解析、追问续接、范围保护、候选校验和提案业务规则；本次没有迁移 Apply 提交协议，也没有改动取消/reset 的规则。此切片建立了实际用例边界，但不代表追问状态机或规划领域已整体迁出 `PlanAdjustmentService`。

## 验收证据

主智能体在共享工作区执行：

```powershell
.\mvnw.cmd -f .\ai\pom.xml test "-Dtest=PlannerPreviewUseCaseTest,PlannerControllerTest,PlanAdjustmentServiceTest,ColaArchitectureGuardTest"
```

结果：**95 项测试，0 失败，0 错误，0 跳过；BUILD SUCCESS**。隔离副本也记录了相同定向套件结果。启动时外部 Qdrant 不可用，测试使用项目配置的本地降级；没有据此声称真实 Qdrant 或 Redis 验证通过。

- Preview 后持久行程快照、revision 和 revision 记录数保持不变。
- 短答继续原有第二天目标；完整新命令覆盖旧待处理目标由既有 `ContinuationCorrectionBoundaryTest` 覆盖。
- 无候选/不可应用方案保留 `canApply:false`；强制应用也会被领域门禁拒绝且不推进 revision。
- Controller 保持状态码映射、响应 body、请求 DTO 和 session-token header fallback。no-candidate 仍为 HTTP 200，但不能应用。
- 未授权匿名 capability 被拒绝；提案经应用层注入的 repository port 持久化。
- Apply 与 cancel/reset 的提交路径未被本切片改写。

## 独立审查与限制

- [CP2_ARCHITECTURE_REVIEW.md](CP2_ARCHITECTURE_REVIEW.md)：确认应用层承担 actor/session 授权、revision 门禁、提案写入能力及用例结果组装；依赖方向符合 application → domain/port。领域内部仍将旧 `ServiceResult.status()` 转换为 typed outcome，枚举名称仍带 HTTP 语义；追问状态机仍在领域服务中。另有读取 session 后至预览生成间的并发窗口，可能生成陈旧预览；apply 的 revision 校验仍是提交安全门，本次没有并发测试该窗口。
- [CP2_REDTEAM_REVIEW.md](CP2_REDTEAM_REVIEW.md)：未发现阻断兼容缺陷。确认预览不改行程、追问目标保留、完整命令替换旧目标、不可应用预览被拒绝、响应字段不被转换层过滤。
- 未执行 MockMvc JSON-to-HTTP 缺失/null/false/空值/未知字段全矩阵，也未执行浏览器/BFF 端到端。红队将其列为后续 wire-contract 改动前应补的非阻断测试缺口。
- 本次未运行完整 Java 测试套件、真实 Redis、多实例或供应商集成测试。

## 最终裁决

CP2 退出条件在此有限范围内满足：application 层实际负责预览入口编排；新预览结果类型不含 HTTP 数值状态；预览不修改行程；短答与完整新命令的续接规则有回归证据；不可应用提案不能通过确认。

因此 **CP2 已验收**，但范围应称为“Preview 用例切片”。后续 CP3 继续迁移 Apply/Cancel 生命周期协调并建立共享状态协调点；不要把 CP2 描述为完整局部规划闭环或整个后端架构重构完成。

## 经验使用记录

本切片沿用“冻结候选、按可观察行为分类、独立审查固定身份”的流程。项目编程经验记录见 [20261009-44a3d2ac5398](C:/Users/summer/Documents/ggy的自动化流程/wiki/tasks/20261009-44a3d2ac5398.json)；该记录需在本阶段工作完成后写入结论。

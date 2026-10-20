# CP4.2 Planner Open 用例验收

日期：2026-10-09  
裁决：**通过，限定于 Java Planner V1 Open 应用用例迁移**。CP4 总体仍在实施中。

## 冻结身份与复核

- 候选清单 SHA-256：`cc12654db2d49efea316efcf485acec3924796a6dc561be4504ba51a798c8553`
- tracked patch SHA-256：`fdde058fd1fa24a5290a095d238ad9fa941c48a15e35a81d5834715d84f4e053`
- 测试日志 SHA-256：`48807d892cc1112bec5041baedcef1341298a96de063ce6353f41606e1a96985`
- 架构审查：`CP4_2_ARCHITECTURE_REVIEW.md`，通过，无阻断项。
- 红队审查：`CP4_2_REDTEAM_REVIEW.md`，通过，无阻断项。
- 清单中的 12 个源文件及实现报告哈希均经主智能体核对匹配。

## 验收结论

主智能体在共享工作区复跑冻结候选的 8 个 Java 测试类：**61 项通过、0 失败、0 错误、0 跳过，BUILD SUCCESS**。实施者另在隔离副本执行 clean test，结果相同。测试覆盖应用用例授权拒绝与无副作用、formal Trip 单次 owner-scoped 读取、恢复快照及版本元数据、HTTP Controller 结果映射、旧 facade 委托、SQLite Trip 流程和架构依赖规则。

本切片将正式行程打开编排移入 `PlannerOpenApplicationService`，Controller 传入显式 actor 并映射原有状态码/body；旧 `PlannerService` 入口保留为兼容委托。恢复计划、正式行程 ID、标题和版本均来自同一次 owner-scoped Trip 读取。无 BFF、前端、Restore 流程或 schema 变化。依赖方向、归属校验、同一快照一致性与既有 wire 行为经 B/C 独立审查未发现阻断缺陷。

## 单独登记的既有问题与限制

架构审查确认一项迁移前已存在的 P2 诊断缺陷：当约束反序列化失败且应用偏好同时不可用时，后写入的 `APPLIED_PREFERENCES_UNAVAILABLE` 会覆盖 `CONSTRAINTS_UNAVAILABLE`，无法同时观察两种降级原因。本轮保留旧行为，没有把它作为架构迁移的一部分修复。实现报告中“两个标记均保留”的宽泛描述在该组合条件下不准确；本验收以源码与独立审查为准，并将该缺陷留给独立修复任务。

Open 请求仍以 UUID 生成 restore key，每次调用创建独立 session；API 当前没有稳定请求幂等键。Java 测试是应用/Controller 层及 SQLite 流程，不覆盖 BFF、真实浏览器往返、真实 PostgreSQL/Redis、跨进程 session 或真实网络丢包。

## 回切与下一步

本切片未提交。回切时仅撤销 CP4.2 新增的 Open 用例及其装配/测试，不回退已验收 CP4.1 或用户改动。下一步进入 CP4.3：迁移 Restore/草稿恢复编排并验证恢复后的 session、快照、revision、owner/capability 与 formal version 契约；随后继续 BFF/前端契约与实际交互验收。CP4 未完成前不进入 CP5。

# CP3 确认、取消与追问续接验收报告

日期：2026-10-09  
裁决：**CP3 已验收（单 JVM / SQLite 语义）**。首条局部调整生命周期重构闭环完成；不代表整个后端重构完成，也不声明跨实例/重启连续性。

## 最终冻结候选

- 候选清单：`outputs/20261009-CP3-r7-candidate.json`（位于工作区外）
- Manifest SHA-256：`e79499fa2d0f9d64dd791751b336f778ed61f3a77a605f5599674f3bf793295a`
- Tracked patch SHA-256：`96f04a05e1dead4e9962aa405fbb12b76ff678de1fcd1ae4ba3879a3627dc3c1`
- 定向测试日志 SHA-256：`7f35ed26b7c9d2048aabc1951096fa2bd7a7e2355bdb329fcea96ae24bb45bd1`
- 实施记录：[CP3_IMPLEMENTATION_REPORT.md](CP3_IMPLEMENTATION_REPORT.md)
- 独立架构复审：[CP3_R7_ARCHITECTURE_REVIEW.md](CP3_R7_ARCHITECTURE_REVIEW.md)
- 独立红队复审：[CP3_R7_REDTEAM_REVIEW.md](CP3_R7_REDTEAM_REVIEW.md)

## 验收证据

最终候选已由主智能体核验 manifest、patch、测试日志及 CP3 切片文件 hash。R7 只增加测试断言和实施报告；应用层、领域服务生产源码与前一候选一致。

主智能体在 `D:/scenic-guide` 独立执行 CP3 十个定向测试类：**146 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS**。SQLite 使用单独临时数据库；Qdrant 指向不可用的本地端口并按配置走降级。B 架构审查与 C 红队均复核 R7 候选，未发现阻断项。

验证覆盖：

- Application 排列 Preview admission、Apply、Cancel/Reset 步骤；复用同一 session stripe、continuation/generation 和 replay authority，没有增加第二套锁或 epoch Map。
- Preview-first 与 cancel-first 均用 latch 控制共享 monitor 竞争。旧预览在取消后不能发布提案；取消先行后新预览成功且可应用，revision 只增加一次。
- 同一幂等键的正常重试、Controller 完成后 servlet 层丢弃响应再重试、CAS 提交后注入异常，以及 proposal remove 失败均有可观察结果和 SQLite 版本/写入次数断言。
- remove 失败后的复合动作验证了回执重放、首项持久删除、第二项未提前落库、剩余动作文本可由现有分类器解析为指定日/地点，并通过 pending ID 续接后写入存储快照。
- 第一项/第二项各自的存储快照与 revision 增量均有断言；无重复写入。

## 已知保证边界

- Apply 顺序中，CAS 与内存 continuation/replay 及 proposal cache 清理不是一个全局事务。proposal remove 失败会被捕获并继续完成后处理；同进程相同幂等键可回放回执。
- 若数据库 CAS 已提交，但进程/调用在 completion 与 replay 记录前异常，重试会因 revision 过期而拒绝二次写入；**不保证恢复原成功回执**。未增加持久幂等账本。
- 响应丢失测试使用真实 Controller/Application/SQLite 与 MockMvc servlet filter，证明 servlet 链完成后丢弃响应的同进程行为；不等同于真实 TCP 断连或进程崩溃。
- `pendingIntent` 当前通过 `clarificationQuestion` 文案携带剩余动作。现有 classifier 可解析本测试中的日期和地点，服务端实际续接仍由 pending ID/内存状态驱动；这不是稳定的机器可读复合动作 wire schema。若客户端需要独立机器处理，应另行演进 API 契约。
- 未验证真实 Redis、PostgreSQL、进程重启、多 JVM/多节点、socket 级网络丢包及跨节点幂等；这些不属于当前单实例 CP3 声明。

## 回切与范围

CP3 未改数据库 schema、BFF 或前端。CP3 回切按实施报告列出的 CP3 专属文件/差异执行；保留 CP0–CP2 与用户既有改动，不使用 `git reset` / `git clean`。

本结论仅表示计划 §3.4 的单实例检查点通过。下一检查点为 CP4 保存、打开、恢复与 BFF 契约；整体后端架构重构仍在进行。

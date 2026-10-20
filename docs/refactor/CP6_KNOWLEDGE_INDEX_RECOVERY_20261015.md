# 知识向量同步持久恢复合同（2026-10-15）

`kb_document` 是同步请求的权威工作记录。创建、正文编辑、适用城市变更、手工刷新和归档在同一数据库事务中记录 `index_request_mode`（`TEXT`、`FILE`、`DELETE`）及 `index_revision`。执行器提交回调捕获文档 ID 和提交版本，仅用于跳过陈旧队列项；真正的输入始终从数据库重读。即使任务排队失败、进程退出，数据库请求仍在。旧版种子文档的 mode 为空，不会因为开启扫描而批量调用 embedding。

领取时写入随机 `index_lease_token`、租约期限和尝试次数。准备文本或文件在数据库锁外进行；发布或删除索引时再次持有文档行锁，只允许当前 token 与 revision 写入最终状态。旧工作者的成功或失败都不能覆盖新版本、归档或新领取者。租约过期但尚未被接手时，旧工作者仍可完成；真正接手后 token 被替换。供应商调用属于 **at-least-once**，租约到期重领可能重复产生 embedding 成本，不能声称精确一次。

失败保留 mode，按 1 秒起、最多 1024 秒（约 17 分钟）的指数退避设置 `index_next_attempt_at`。成功状态为 `vector_status=synced` 且 lease 已清空；mode 保留最近一次权威输入类型，保证“上传文件后编辑正文，再迁城”继续索引编辑后的正文。`DELETE` 同时用于归档和活动文档清空正文；归档在 SQL 中先隐藏旧向量，再异步删 Qdrant。受控上传文件的物理删除仍是 best-effort，不属于持久恢复保证。

默认 `knowledge.index.recovery.enabled=false`。需要启用时由运维显式传入 `java -jar <当前应用包> --knowledge.index.recovery.enabled=true`，不修改业务 env。启用后定时器只将有界扫描投到已有 `knowledgeIndexExecutor`；队列满时等待下一轮，不阻塞共享调度线程。参数：`knowledge.index.recovery.batch-size` 默认 8、上限 20；`knowledge.index.recovery.interval-ms`/`initial-delay-ms` 默认 60000；`knowledge.index.recovery.lease-ms` 默认 300000。关闭运行时向量同步时，扫描只执行 `DELETE`，不触发 `TEXT`/`FILE` embedding。可用 `KnowledgeDocumentService.recoverDue(int limit)` 显式、同步地进行隔离验收；此方法无需开启定时器。

跨实例文件恢复的前提是各实例都能读取同一受控上传路径。旧库已同步文件若 mode 为空，无法推断其是否曾由管理员正文覆盖；需显式刷新或编辑，建立新的权威输入类型。旧版进程如果仍运行旧同步协议，不受本次 token 门禁约束；切换版本前应先停止旧工作者。本轮不重刷真实向量库，也不对 222 条历史种子进行隐式索引。

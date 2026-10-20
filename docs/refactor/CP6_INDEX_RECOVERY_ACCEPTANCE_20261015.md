# CP6 知识索引持久恢复验收

2026-10-15，本板块在隔离 Windows PostgreSQL/Qdrant 与独立 JVM 范围内通过。A 实施生产协议与单测，B 实施真实进程探针，C 只读独立复核，主线程统一运行、保存原始失败、清理环境并交付本地候选。未提交、推送或部署。

## 最终行为

复用 kb_document 持久记录输入模式、尝试次数、下次执行时间、租约 token 与期限。创建的单条 INSERT 包含意图；编辑/迁城/归档/手动刷新在文档锁事务内记意图。内存执行器只负责通知，队列拒绝或进程退出不会删除持久请求；无需独立消息中间件。

普通异步、手工刷新和恢复统一领取协议。prepare 在锁外；Qdrant publish/DELETE 与 SQL 终态写入在锁内核对 token+revision。旧 worker 不能覆盖新修订或新领取者；仅租约过期但未被接手时仍可完成，避免慢任务必然失败。失败保留意图并退避，最长1024秒。

成功保留最近输入模式，synced 状态不再领取。上传原文件 A 后编辑正文 B，再迁城仍按 TEXT/B 建索引；手工刷新原文件明确切到 FILE。归档及清空正文走 DELETE，先以 SQL 权威隐藏旧代再幂等清理；关闭运行时向量化仍允许 DELETE，不触发付费 TEXT/FILE。

恢复调度默认关闭，开启后只向既有有界执行器提交批次，不占用共享调度线程做长 embedding；历史 seed 的 mode 为空，不隐式批量收费索引。参数与开启命令见 `CP6_KNOWLEDGE_INDEX_RECOVERY_20261015.md`，未改用户业务 env。

## 实际验证与统计

- 默认 Maven package：1310 run /1269 passed /41 skipped /0 failures /0 errors，217份XML逐 testcase计数一致。JAR SHA256：`c9b4ae9f83315600e9bb36308312da54f0e6bac9627c32e8464ef98a6021e514`。
- 定向43/43：29项服务/SQLite回归，10项既有真实 PostgreSQL/Qdrant，以及4项新独立进程恢复集成。
- 随后仅新增测试，生产代码未变：恢复集成类补跑5/5（前4项重复，不与43直接相加）。新增精确窗口为真实Qdrant publish成功后、SQL finish/commit前gate强杀；中断时SQL syncing且RAG不可见，租约自然到期后第二JVM恢复，最终仅剩正确一代及对应revision。
- 其余进程场景：领取后强杀、自然租约到期接手；双JVM只有一个有效领取进入embedding；持久失败退避、立即重试不执行、到期成功；新正文与归档撤销旧lease，迟到任务不能覆盖/复活。
- 文件空正文迁城使用 FILE，以及 A→编辑B→迁城保留TEXT，均有专项。调用供应商为本地确定性embedding/rerank；PG与Qdrant是真实服务，未调用付费供应商。
- 默认package在最后一个补充opt-in测试添加前完成；不将新增用例虚计入默认1310或跳过数，不重复跑无生产变化的全量。未改前端，不重复计入前端构建/浏览器验收。

原始证据在 `evidence/cp6-index-recovery-20261015/`。规划硬编码规则扫描无命中；全后端仍单独记录五处既有Windows PDF字体路径，不声称全项目零字面量。

## 失败与修正留痕

首轮服务回归25项有1项旧断言失败：update返回提交时pending快照，afterCommit完成后fresh查询才是synced；现同时断言两阶段、0 chunks及实际DELETE调用。首轮真实43项补充前的42项有2项旧worker文案断言失败，旧worker只认先前的失效原因字符串；现只接受两条具体业务失效原因，未知异常仍失败，父报告嵌入worker日志防TempDir清理后丢证据。最初两份旧child日志已被JUnit清理，保留父日志与缺失限制，未伪造它们。

独审还发现并推动修复scope-only空正文文件误选DELETE、编辑正文后迁城误回原文件、租约到期必然丢弃慢任务、共享调度线程被embedding阻塞等边界。所有最终生产代码与已打包源码一致。

## 清理及边界

随机PG schema与集成Qdrant collection剩余0；只删除本轮启动脚本创建的明确探针collection，保留其他历史测试存储。专用PG/Qdrant已停止，子worker统一回收，业务环境未改。

不承诺外部调用精确一次，长任务被接手时可能重复embedding；默认关闭的定时器未做长期运行验收。跨机FILE恢复需要共享可读上传目录，此前提未在多主机实测；物理上传文件删除仍best-effort，不具有持久清理保证。旧同步程序不遵守新门禁，升级前须停旧worker。旧库已同步但无mode的文件历史无法可靠反推来源，需显式建立新模式；不宣称自动还原。未验证真实embedding供应商故障、Linux生产拓扑或全国完整资产。

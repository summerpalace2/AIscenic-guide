# BFF/Java 多实例业务链：后端实施记录

## 实际确认的缺陷

1. 提案在 Redis 共享，但 PendingIntentContinuations 的 epoch、generation、追问槽位和复合操作队列仅在单机 Map。A 预览后 B 应用会被判为“已结束对话”；B 取消也不能使 A 的原提案失效。
2. applyReplays 仅在单机 Map。A 已提交但成功回执丢失，B 同键重试因版本已变化返回409，无法恢复原成功回执。CAS只能防重复写，不能替代幂等回放。
3. PlannerSessionRepository.create 唯一键碰撞后在已失败的 PG 事务里继续查询。真实双 JVM Open 屏障复现 transaction aborted，机制与上一板块正式 Trip 幂等占位缺陷相同。
4. 修复 SQL 冲突后，同版本正式行程仍可能因嵌套 Map 的 JVM 迭代顺序不同而产生不同 Open 指纹，错误返回409。
5. ChatStreamOutputAdapter 结束时只把历史写入本机异步队列。SSE结束不代表另一个实例能读取历史；清空后旧队列、已受理流与压缩任务还可恢复旧内容。

## 共享规划状态与事务

迁移 v10 增加 planner_session.continuation_state_json 与 planner_apply_receipt 表。PlannerSessionRepository 同时实现小型 PlannerConversationStatePort，使用同一数据库及事务锁维护 session 状态。生产没有本机 Map 与数据库双重权威；InMemoryPlannerConversationState 仅供显式独立构造的领域兼容测试使用，不注册 Spring，不在数据库失败时回退。

PendingIntentContinuations 每次加载并写回对应 session 的状态。ThreadLocal 只保存同一调用栈中的临时 State，防止嵌套 commit/remember 回调覆盖彼此，不作为跨请求缓存。缺失/旧状态不会放宽 epoch 校验，旧提案需要重新预览。

预览保留短 admission/commit 边界，模型生成在锁外。应用的 revision、epoch旋转、完整回执在同一 session 数据库事务提交。确认后的 Redis提案清理由 afterCommit 回调执行，避免 receipt写失败时数据库回滚了、提案却已删除。既有领域兼容入口与实际应用入口使用同一权威。

Open create 使用精确目标 ON CONFLICT DO NOTHING，row0由 Java主动报告 DuplicateKeyException，让既有查询回放保持可用，而不是使 PG事务失败。Open 与规划 create 指纹使用 ORDER_MAP_ENTRIES_BY_KEYS，保留 List 顺序与严格指纹比较。Open有真实跨 JVM失败与修后验证；规划 create 为同机制静态确认及回归，不冒充已独立完成所有create场景真并发验收。旧部署生成的非规范指纹可能要求新操作键；已有业务快照未删除或改写。

## 聊天持久化边界

当前轮次先捕获 Redis writeEpoch，实际 USER/ASSISTANT使用 appendConfirmed 原子提交，持久化完成后流才能结束；流处理写入在 boundedElastic执行，避免阻塞供应商I/O线程。兼容 appendAsync 在入队前捕获 epoch。

clear在同一 Lua中递增 epoch并清 history、摘要、安全限制、计数和用户索引。旧队列、旧轮次、安全约束及旧压缩写入均需捕获 epoch匹配，否则不能复活清空前的数据。没有新增本地状态 Map，也没有改摘要算法、语言词表或城市字典。

真实 Redis 额外暴露 Lua cjson 将空数组转为空对象的问题：criticalConstraints={} 导致 List反序列化失败。读取边界仅将该字段的空对象规范为空数组；非空非法对象仍拒绝，不吞掉整个安全快照。请求组装后清除原工作线程 UserContext，索引更新必须匹配 scoped session所属用户，防止调度换线程后错归属。

## 失败记录与阶段证据

- planner-multi-instance-before.log：三个实际应用服务入口反例全部失败（3run3fail），验证跨服务应用、回放与取消缺陷。此测试是两个独立服务对象共享真实临时SQLite，不称为两个HTTP实例或真实Redis。
- planner-receipt-rollback-before.log：1run1fail，真实数据库回滚到v1后提案已消失，证明缓存清理需要延后到提交之后。
- planner-open-pg-before.log：真PG屏障1run1fail，子 JVM日志在临时目录 cp6-formal-923406414844309804，唯一冲突后的事务失败已实证。
- planner-shared-state-targeted-final.log：182run181pass1fail，PG唯一冲突已修，随后暴露不同JVM同义指纹不一致；Open子日志 cp6-formal-15778988497400910435，分别200/409，原证据保留。
- planner-shared-state-verified.log：12:16:36 BUILD SUCCESS，184run184pass0skip，包括7项真实PG/Redis基础设施测试、应用层多实例与迁移/原回归/架构检查。此后还有聊天与create指纹改动，因此不是最终源码全量成绩。
- 聊天首轮日志包含缺失import与bareObjectMapper不匹配Spring策略的测试搭建错误，未误报为生产缺陷。chat-shared-history-before-confirmed.log 实际3fail1error：三个历史时序失败及Lua空数组互操作失败。
- chat-shared-history-first-regression.log：12:29:17 BUILD SUCCESS，39run39pass0skip，含6项真实Redis受控写入/压缩/清空测试。所有模型回复为本地mock，没有收费供应商调用。

旧回放测试的 Java Map.equals 对 Integer/Long 与 JSON回放误判，现按完整JSON结构比较，未删除业务字段断言；旧测试原本允许“CAS已提交但回执未记录”的限制，现加强为同时回滚并可重试；并发顺序测试改为 Future有界等待，而非要求实现必须使用Java监视器。迁移数量从9明确更新为10。

最终统一专项、默认全量与根线程真实BFF/双HTTP实例结果仍待完成追加，不能以阶段成绩替代。专用PG/Redis由根线程管理；执行只读取其明确授权的临时CP6配置，使用随机schema/key，未读取业务env或触碰用户开发服务。

## 默认全量发现的持久化内部标记遗漏

multi-instance-final-targeted.log 于12:34:00记录223/223通过，随后首次默认全量 multi-instance-final-full.log 实际1251run、2fail、3error、23skip，原日志保留。

四处是旧测试契约/fixture：断言对象引用相同、反射已删除的本机锁字段、两项手工删除schema未建receipt表。分别改为完整业务字段等值、真实共享DB锁的并发顺序与补齐测试schema，保留原安全断言，没有让缺表错误在生产中被吞掉。

另一处是真实 codec遗漏：timeScopeResolved 是禁止外部DTO输入的 @JsonIgnore字段，持久化 PendingIntentContinuations 时也被忽略。已纠正时钟的下一次追问会再次解析原错误时钟。内部 State 现在单独保存 pending/remaining 的该标记并在加载时恢复，外部 DTO 仍不暴露或接受它。新增实际SQLite共享store跨两个continuation对象的两次补答回归，同时检查时钟正确与外部 DTO不可注入。修后需要重新专项及默认全量，不沿用223阶段成绩。

multi-instance-final-targeted-v2.log 于 **12:41:36 BUILD SUCCESS：249run / 249passed / 0skipped / 0failures / 0errors**，包括补获codec反例、旧fixture升级以及最终源码下7项真实基础设施和6项真实Redis聊天探针。默认全量重跑记录为 multi-instance-final-full-v2.log，明确 CP6_IT_ENABLE=false，正在执行；真实探针成绩引用上述单独专项，不将默认跳过视为基础设施通过。

最终默认全量已于 **12:44:05 BUILD SUCCESS：1252run / 1229passed / 23skipped / 0failures / 0errors**（multi-instance-final-full-v2.log）。跳过包含默认禁用的真实探针，未计为通过；同源码真实13项已在249专项实际执行通过。指定改动diff --check退出0，Java源码与测试冻结并向根线程释放Maven。最终新jar及BFF/双Java HTTP端到端验收由根线程执行，本实施报告不提前写入其结果或沿用上一板块旧包指纹。

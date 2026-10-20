# BFF 双实例用户链独立验收

2026-10-13。独立角色只读审查源码与原始日志，没有运行Maven、重启服务、读取业务.env或调用收费供应商。统一经验记录由主智能体维护。

## 最终裁决

本次定义的15阶段完整链路通过。亲读 `bff-cluster-final-third.log` 与 `C:/Users/summer/AppData/Local/Temp/cp6-bff-cluster-pMagxb/result-summary.json`、`proxy-calls.json`、`provider-calls.json`。实际两Java JVM、两BFF进程，共享专用真实PostgreSQL/Redis；最终jar SHA256为 `fa7f6e9721878b957a0c73e68196059bdff33d9fc0fe0c42508aac71c15af050`。不是只看SSE200，也没有用上一板块四项HTTP检查替代本轮。

| 范围 | 实证与oracle |
| --- | --- |
| 认证与隔离 | 原cookie在另一BFF认证；匿名、其他owner不能读取规划会话；BFF1登录的账号在仍存活的BFF0退出后，BFF1拒绝原cookie |
| Open故障 | 正式读取失败时只补偿本次pending会话；ACK已成功但两次回执丢失时不盲删；另一实例恢复；Open成功回执丢失后同key恢复同一Java会话，持久化后ACTIVE |
| 聊天历史 | 另一Java实际stream请求的USER历史含先前唯一代号和不能走台阶限制；客户端没有重新提供该历史 |
| 预览与应用 | 预览前后权威trip完全一致，候选仅移除甲；成功应用回执丢失后另一实例恢复v2，数据库revision仅2条；同key异指纹409 |
| 权威上下文 | 预热BFF随后聊天的实际stream当前USER站点资料有乙且无已删除甲，不以全部历史任意位置包含作为通过 |
| 并发与取消 | 同key双实例确认回同一v3快照，站点为丙/丁、数据库revision仅3条；跨实例取消后的旧proposal409且版本不增 |
| 实例丢失与恢复 | 强杀自己启动的BFF0后BFF1读取、保存、重新打开；Java会话新建，恢复版本1、正式版本正确，丙/丁保留；模型USER历史继续含代号和限制 |

共15阶段来自同一次最终third运行。原始proxy确有Java200后receiptDroppedAfterSuccess事件，Open、ACK分别丢掉同实例重试回执，Apply成功回执跨实例恢复。供应商fixture只提供模型答复与地图路线，不预制proposal、版本或业务保存结果；断言使用实际HTTP回执、权威数据库及供应商收到的请求。

## 已修缺陷的独立源码复核

Redis共享Web身份、draft映射与workspace替代本机权威；共享模式每次恢复读取Java权威trip。身份update Lua要求key仍存在并保持剩余TTL，退出DEL后迟到update不能复活身份。workspace逐字段JSON编码再Lua合并，避免空数组经cjson变对象。

Java v10把continuation状态和apply receipt存同一数据库。session行锁先于读取；实际apply入口在同事务完成revision、continuation与receipt，proposal删除afterCommit执行。模型预览生成在短admission事务之外，发布仍复核generation。Open指纹规范嵌套Map键序，保留List业务顺序。pending会话删除同时处理revision与receipt。

当前聊天轮次捕获Redis epoch，USER/ASSISTANT确认写入与clear同Lua栅栏；旧回复、取消补写、摘要、安全限制不能在清空后复活。完成流在确认写入后结束。内部pending/remainingTimeScopeResolved独立持久化恢复，外部DTO仍不接受该字段注入。

BFF getPlannerSession与dynamic-refresh现使用options.token，stub签名同步实际API。前端Open保留网络/5xx操作键，明确4xx重建；迟到回执绑定原user对象。saveTrip补原user/trip/session归属守卫，两处await后及catch/finally均复核，避免切账号后的旧回执清新草稿。

## 失败历史与统计口径

早期注册201误断200、合法同实例重试恢复单次丢包，为工具oracle错误。随后真实发现GET会话第三参数token被忽略，原proxy同会话restore200/route404，生产接线修复并补非stubHTTP鉴权反例。

最终first/second两次未整轮通过，原因是工具选中异步非stream情绪分类请求、误把安全设计中当前USER的数据期望放在SYSTEM。已亲读QUEwiC原请求证实，并核修后仍只检查stream当前USER且保留删除目标负断言；没有弱化为历史宽松匹配。最终third才是15阶段整轮通过，不把前两次失败抹掉或伪计生产失败。

后端最终同源码默认全量1252run=1229pass+23skip，0fail/error；统一专项249/249零skip含7项真实基础设施及6项真实Redis聊天。首默认全量暴露内部时段标记codec遗漏已补，日志保留。主智能体最终Node112/112零skip（含真Redis），前端实施者22专项与build/typecheck通过；此处引用实施记录，独立实际执行范围为源码及日志复核。

## 边界与清理

本轮Windows PostgreSQL16.15、社区Cygwin Redis7.4.3，不等同Linux生产、HA或网络分区验收。模型/地图为受控本地HTTP，验证上下文与协议，不验证真实模型语义质量、真实高德覆盖或浏览器交互。DELETE补偿失败仍是coordinator受控契约覆盖，没有冒称本轮实际集群故障注入通过。

summary确认自己启动的应用进程停止、随机schema已删除，明确redisFlushed=false；Redis是本轮专用一次性runtime，服务停止由主智能体另外核验，不宣称每个key均有TTL或已即时清空。未触碰用户开发端口/业务数据。本报告到此冻结，可纳入本地候选审阅包。

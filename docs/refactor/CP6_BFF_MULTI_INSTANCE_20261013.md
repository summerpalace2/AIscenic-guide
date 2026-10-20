# CP6 BFF多实例用户业务链

日期：2026-10-13。状态：本轮受控Windows范围实施、自查、独审和根线程实际集群验收完成。

承接Windows真实基础设施板块；继续使用用户已授权的专用PG16.15/Redis7.4.3社区Cygwin运行目录，不能外推Linux部署。本轮以两真实Java、两真实BFF、本地受控HTTP供应商验证用户链，不使用业务env或开发8080/3000/5173。反馈与运营指标非本轮共享状态，明确保留单实例范围。全国知识、Qdrant与真实供应商另行验收。

## 确认链路问题与实施边界

- BFF opaque登录会话仅本机Map；Redis共享模式统一web认证、planner映射、formal workspace，复用现有Redis连接实现与加密配置。默认local保持兼容；Redis失败不回退内存。不能用sticky或第二次登录冒充透明跨实例。
- BFF恢复后旧快照可能流入聊天；Java仍是行程权威，读取必须刷新版本与站点。
- Java当前epoch/pending及apply回执本机Map，跨实例需要共享权威与同事务成功回执；缺epoch不放宽成接受。
- BFF未透传open操作键、未接ack/补偿；Java open同键创建事务碰撞需真实PG反例后修正。

Java实施者负责共享continuation/receipt与持久事务及针对性红绿；BFF实施者负责共享适配器、open及前端调用契约和Node反例；独审另列矩阵并复核证据；根线程负责隔离环境、唯一配置模板、实际HTTP矩阵与最终交付。保持未知既有改动，不提交推送部署。

## 退出矩阵

同一cookie/CSRF跨BFF鉴权及跨实例退出；共享draft预热→另一实例preview/apply；并发apply只有一次PG revision变更；提交后丢回执跨实例同key恢复、不同fingerprint拒绝；旧BFF缓存刷新后聊天使用新版本站点；open回执丢失同key只建一session、成功持久映射后ack、失败补偿只处理owned未修改会话；聊天跨Java共享历史且formal重开承接；不同user/device不能访问；停止一BFF后另一实例仍能继续。持久化正确性与HTTP回执正确性分开断言，skip不算通过。

模型/高德均为本地HTTP fixture，记录实际请求用于上下文断言，不用fixture回答冒充智能模型语义质量。工具需保留首次失败、版本和最终source/package hash。

经验任务20261013-525326dd37af；关键词命中业务日主题与本范围不匹配，不采用其日期规则。读取前轮PG幂等原始证据用于识别待实证风险，不以旧通过替代本轮新测试。

## 修前与工具问题记录

- planner-multi-instance-before.log：3个实际应用入口/独立服务对象反例失败，分别跨实例apply、回执恢复、取消失效；该层为临时SQLite+共享bean，不冒称两HTTP进程。
- planner-open-pg-before.log：真实PG双JVM同key首次Open复现事务中止；之后planner-shared-state-targeted-final.log再揭示同义请求在两JVM因Map字段顺序不同而fingerprint冲突。采用精确ON CONFLICT和嵌套Map排序序列化，保留数组顺序及严格指纹比较；规划创建同根指纹算法也规范化并回归。
- 提案移除曾先于事务commit，新增回执写失败测试证明回滚后提案不可重试；调整为afterCommit清理，回执和revision原子提交。
- bff-cluster-before.log：根线程工具把注册201误写为200，属于工具合同错误；bff-cluster-baseline-second.log：只丢一次Open回执被合法同实例重试恢复，原工具要求失败不正确。两份日志均保留，不计作生产缺陷。
- bff-cluster-baseline-third.log：修正持续丢该实例回执后，跨BFF鉴权、Open同key恢复/ack通过，但planner GET返回404。实际proxy先恢复GET200后路由GET404，确认token误放第三参数；动态刷新同错且stub签名掩盖。已统一options.token并补非stub HTTP鉴权反例。
- 保存迟到回执守卫、防失败注销假成功、Open明确拒绝后结束旧key由前端deferred/操作键回归验证；不同账号/草稿不得被旧请求清空。

该阶段根线程全Node：bff-cluster-node-all-real-final.log，112 run/112 passed/0 skipped，显式启用本轮专用Redis模块5项。此前101/103项带1个真实Redis未启用skip的运行保留，不替代最终结果。Java最终全量日志multi-instance-final-full-v2.log为1252 run/0 failures/0 errors/23 skipped，真实条件测试须看独立执行证据；实际集群最终结果见下一节。

## 最终验收与交付

最终完整集群第三轮 `bff-cluster-final-third.log` 退出0，同轮15阶段通过，机器证据 `CP6_BFF_MULTI_INSTANCE_HTTP_RESULT_20261013.json`。原始证据目录 C:/Users/summer/AppData/Local/Temp/cp6-bff-cluster-pMagxb。实际2Java+2BFF、真实PG/Redis、4次流式模型HTTP请求；成功Java Open回执丢弃2次、ack回执丢弃2次、apply回执丢弃1次后分别恢复。最终包FA7F6E9721878B957A0C73E68196059BDFF33D9FC0FE0C42508AAC71C15AF050，打包日志bff-cluster-java-package-final.log。

15阶段覆盖同cookie跨BFF、formal读取失败补偿、ack丢回执不误删、Open同key恢复、聊天跨Java历史、preview不变、apply丢回执恢复、指纹冲突、预热缓存刷新、双实例并发确认一次revision、跨实例取消、匿名/异主拒绝、跨实例退出、停一BFF后保存重开且聊天承接。查库断言revision从1→2→3且仅三条，两个并发回执完整trip相同，正式保存version2且两条历史，重开签发新Java session并version1/来源正式version2、保留两站。

最终Java专项 `multi-instance-final-targeted-v2.log` 为249/249无skip，含7真实基础设施+6真实Redis聊天测试；默认全量1252=1229passed+23skipped，0failure/error。首全量1251的2fail/3error原日志保留：旧对象引用/反射本机锁/测试schema缺表等fixture升级与一项真实内部codec遗漏分开处理。timeScopeResolved仍禁止外部DTO注入，内部续问state单独保留，防持久化后重复解析旧时钟。后端实施详见CP6_MULTINSTANCE_LIFECYCLE_BACKEND_20261013.md。

根线程还逐个JUnit testcase核对全量：实际1252、23skip、0failure/error，与Maven一致。六份嵌套测试报告根tests属性为0但包含52个testcase，仅累加根属性会误算1200；两种原始统计及差异保留在CP6_BFF_MULTI_INSTANCE_FINAL_SUMMARY_20261013.json，采用实际case计数。

最终BFF/前端22项专项、真实Redis5项、build/typecheck通过；根全Node112/112已覆盖这些实际Redis测试而非skip。配置检查通过，唯一模板新增OPTIONAL_FEATURE `YUYOUZHICE_BFF_STATE_STORE=local`；多实例需显式redis并复用连接/AES保护，非法值启动拒绝。默认local只支持单实例。BFF实施报告位于另一仓库docs/bff-shared-state-open-acceptance-20261013.md。

最终完整集群前两轮停止在工具断言：最后模型调用可能是异步非流式情绪分类，且安全设计将站点资料置于当前USER而非SYSTEM。已对照原payload修为stream=true的最后USER，仍同时断言保留站点存在和已删站点不存在；没有用全部历史任意包含弱化断言。原final-first/second日志保留，生产源码未为这两个工具错误改动。

独审最终报告 `BFF_MULTI_INSTANCE_INDEPENDENT_ACCEPTANCE_20261013.md` 亲读最终summary/proxy/provider及SQL断言，确认同轮15阶段结果；没有把实施者自述当独立实跑。根已停止本轮PG/Redis、确认51732/51733无监听，机器记录CP6_BFF_MULTI_INSTANCE_CLEANUP_20261013.json。应用进程均已退出、随机schema删除；没有Redis FLUSH，Redis数据文件及各轮证据保留在专用runtime，不声称所有key即时清零。

本地审阅候选 C:/Users/summer/Documents/Codex Deliveries/CP6-20261013-bff-multi-instance-review-final，包含三个仓库既有dirty改动及显式被Git忽略的工具/报告；manifest记录HEAD、文件哈希和排除，不全归属本轮。未提交、推送或部署，未重启用户开发服务或改真实业务env。

边界：模型/地图使用本地HTTP fixture，不代表真实模型推理/供应商时效质量；Redis是Windows社区移植，Linux/HA/网络分区/断电未验；补偿DELETE失败仅coordinator测试及Java租约回归，未单独做集群DELETE故障注入。反馈/运营数据仍为单实例，不纳入三类共享状态。真实Qdrant、全国经核验知识资产、真实供应商及远程交付仍待完成。

经验：Wiki命中的业务日规则不适用；前轮原始PG幂等经验在本轮真实Open碰撞复现后作为窄修复方法复用，未据旧结果跳过当前实证。使用记录20261013-525326dd37af。

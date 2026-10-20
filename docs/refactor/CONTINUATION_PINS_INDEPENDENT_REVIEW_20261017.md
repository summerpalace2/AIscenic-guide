# 多轮续接与服务端固定站点独立代码复核

日期：2026-10-17。审查者未实施本轮生产改动；只读检查Java调用链，Maven与真实模型由root统一执行。本文件不把静态复核当作测试通过，也不对未完成的新题集给出验收结论。

**当前状态：root最终全量full-r3已实际BUILD SUCCESS：1580 run、0 failure/error、50 skipped，即1530 passed。此前full-r2的1 failure、4 errors及静态漏审记录完整保留。独立holdout最终五模式三轮尚在运行，质量验收待最终原始结果，不能把全量测试通过等同质量达标。**

## 状态与信任边界

- 固定站点在`trip.pinnedStopIds`中，随规划会话快照及版本保存。`updatePins`以显式增减集合、owner/token与expectedVersion校验，在既有apply锁内CAS写入；空列表不表示清空。变更后重置旧追问，旧提案仍受版本约束。
- 普通preview只把服务端pins与legacy入参做union，不把空入参解释为取消，也不在preview写入规划快照。`optionStatus`检查保留的站点身份并继承服务端pin集合；force不能越过不可应用的保护。
- session direct mutate/replan、草稿restore均校验固定目标不能消失或换地点，且保留服务器pin集。正式TripReplanEngine也有目标提前拒绝与最终retained检查。
- 直接正式full snapshot update不再允许省略pins或删固定站点绕过保护；初次比对与CAS rebase最新快照都校验retained/preserve。只有现有授权planner-session保存桥接调用private trusted update，允许已在session显式unpin的结果保存；信任位不从浏览器DTO或snapshot metadata读取。create不引用不存在的当前快照。

## 本轮发现及修正

| 发现 | 修正与复核 |
|---|---|
| 正式replan绕过session保护 | engine目标和最终快照双重保护，补正式保存版本不增长回归。 |
| 直接正式full update可清pin | 增加非受信update守卫，并为受信session显式unpin保存保留独立通道；rebase后再次检查最新pin。 |
| 首次trust守卫误插create，引用未定义变量 | 已移至updateInternal首次snapshot；create错误块删除。 |
| 数量matcher.find误吸同行人数或负数绝对值 | 数量仅接受删减动作直接辖数或整句短数量补答；负数、人数与日期不作删减数，多数量歧义不选其一。 |
| 已补数量被旧raw再次解析覆盖 | REDUCE_COUNT以本轮已验证补答作为数量证据，结构化原日期及保护继续继承；补“一个”后normalize不再清掉结果。 |
| 含“心情”的地点名被截成情绪消息 | 已有站点/目录精确名优先；仅明确自述句式进入旁话。合成名称回归不依赖城市或真实地点名单。 |

## 产品契约与保留范围

缺少删减数量不默认删1处；数量上限根据当天可删站点推导。保留最终站数不冒充删除站数，能力不足时明确说明、零变更并请用户改述删除数量。取消继续走既有生命周期清pending。情绪旁话返回DIALOGUE/QA、modified=false，原待办同ID挂起，不刷新TTL；明确地点和修改动作不走情绪分支。

新增回归覆盖`PlannerPinsApplicationServiceTest`、`PlannerPinsTest`、`TripServiceTest`、`PlannerQuantityPolicyTest`、`ScopedReplanAndMultiSlotRegressionTest`及restore相关用例。审查未自行运行Maven；root需报告实际执行结果。真实数据库重载、浏览器刷新、双实例、供应商故障和新独立题集结果须分别有证据，不由本静态结论代替。

## 独立性说明

新30题作者在冻结前未读实现与旧题。运行器转译者（本审查者）在获得root授权后读过冻结题与实现；本轮代码反馈来自上述通用输入边界和调用顺序复核，没有通过新题跑后成绩追加城市/实体/措辞特例。题源保持原SHA，转译及不可观测通道另有journal；不得把所有维护参与者都描述成完全未接触新题。

## 前端与BFF追加独立复核

只读范围：pins-service、chat-action-handler、chat-service、navigation/auth、user-store备份导出及BFF pins代理。没有编辑这些实现；使用实际函数源码、隔离的依赖与内存localStorage做小探针，没有操作真实用户数据或浏览器。

已确认的主体设计：preview请求不再传权威pins，固定/取消固定走显式增减API；Java回执的版本与pin集合在BFF/前端校验；owner/session/baseTrip检查拦截陈旧回执；manual mutation、apply、pin互锁，刷新先备份差异草稿才接管权威trip；备份失败保留原trip并阻止修改；legacy缺pin字段规范化为空。

发现与当前状态：

1. **恢复期间清聊天导致悬挂锁：已修并隔离复核通过。** 原owner含chatMessages数组，reset-chat可替换数组导致回执提前返回且RecoveryPending遗留。当前新增恢复期间reset-chat guard；实际函数探针输出`pending=false, blocked=false`。
2. **无session草稿导航恢复悬挂锁：已修并隔离复核通过。** navigation无条件设RecoveryPending，再进入recover的无session提前返回；现有404分支会合法保存sessionId:null草稿。修前探针输出`pending=true, blocked=true`，连重新生成也被挡住；修后无session入口清pending并标记recoveryFailed（禁止无权威会话的直接变更），当前plan入口允许重新生成。实际输出`pending=false, recoveryFailed=true`。
3. **同session重复恢复覆盖未导出备份：已修并隔离复核通过。** 实际backup helper连续存A、B，输出`sameKey=true, retainedDraft=B, originalDraftSurvives=false`。首次本地编辑A备份后接管服务器B，后续远端更新C再恢复会覆盖A。现改为同key下扁平snapshots档案，单次setItem原子提交，旧单快照兼容迁移，导出入口导出全部snapshots。修后探针输出`snapshotEdits=[A,B], originalDraftSurvives=true`。存储满时返回失败、旧档案和当前草稿保留，不谎报成功。

本追加段的安全结论仅限已检查链路。网络回执丢失仍可能导致服务端变更成功而客户端未知，须再取权威快照；本次未验证供应商、浏览器真实刷新、多实例与网络丢包。本复核确认上述源码及隔离函数层闭合；root相应回归、浏览器与独立测量完成前，不单凭本报告宣布整体验收通过。

### 前端最终冻结范围

三项保全/锁问题已按实际函数复核闭合，最终检查文件与SHA-256见`evidence/continuation-holdout-20261017/frontend-independent-source-identity.json`（包含pins、chat动作/服务、navigation/auth、user-store、展示与BFF代理）。root转达前端作者最终Node 109/109与构建通过；本审查者没有重跑这些套件，不能把作者结果冒充自己的运行证据。至此停止前端扩审。

## 日期、取消与剩余任务队列的冻结复核

只读检查新增`PlannerCalendarDateResolver`及其preview/resume调用、取消分句、`deferAfterApply`和既有`afterApply`状态转移；没有修改运行器或新增评测题。

日历日期仅在当前权威trip的已知日期中映射，不依赖系统当前年份；非法日期、范围外、多个日期及跨年不唯一均要求明确日期。日期更正清除跨日旧stopId并保留原操作/其他缺失槽位。零数量同时明确拒绝继续删减走取消；自然语言取消固定不当作取消整个任务，也不私自调用pins写接口。

复核曾发现“完整新命令被错误当作旧任务日期补答”回归：仅因消息含日期和修改动词就把新命令延期。冻结修正现要求存在**独立日期补答clause**且该段不含明确目录/行程目标，才将另一个动作排入remaining；单句日期+完整新动作走原来的新任务替代规则。新增真实preview回归用于验证该边界，执行结果由root报告。

剩余操作复用现有conversation state，并由generation和版本约束；补答只生成首项预览，回执显式列出尚未执行的动作；只有确认apply后才将remaining转为下一项pending，取消会清队列。此处依据root提供的CH12已确认产品口径，不把原独立题源状态改写成人工标签。新回归最初名称与现有fixture目录不一致产生无候选澄清，实施方仅修该新测试输入并增加响应断言，未补写不存在的目录数据。

当前未见新的确定阻断；不以该静态结论代替root专项、全量及冻结三轮成绩，不再追加问题或题集。日期与队列已经根据诊断后修复，后续冻结题结果应称回归/稳定性验证，不能营销为完全未见的首测泛化。

### 全量回归发现后的最终限定复核

仅检查最后两项回归修补：共享数量parser恢复既有“少推荐一个”等兼容语法，保留动作辖数/整句短答、负数/人数拒绝及缺数量追问，classifier的extractCount复用同一parser，不再与normalize使用两套数量解释。修改动作判定统一经`isModificationOperation`与既有classifier，日期补答/剩余任务识别沿同一语义分类，避免各处分散的动词正则漏掉REDUCE。

新增`offlineAfterFirst`仅作用于一条新回归：首轮用受控模型建立缺日期的旧REMOVE，续轮模型实际失败，从而检查deterministic fallback能接收完整的新ADD，而不是被一直返回旧REMOVE的fixture掩盖；测试还显式检查fallback source与PROVIDER_ERROR。这是修正测试供应商行为与测试目标不一致，不是改独立题gold或补夹具字段刷分。

本限定复核未发现新的确定阻断。最终源码SHA另存`evidence/continuation-holdout-20261017/java-independent-source-identity-final.json`，原identity保留。root正在执行全量full-r2；本审查者不运行Maven、不改评测、不新增题目，至此停止扩审。

### 实测纠正：full-r2未通过，撤回最终通过推断

root回报全量`full-r2`实际为**1 failure、4 errors**。上一段仅代表当时静态复核没有发现阻断，不能表述为实现已通过验证；该静态结论漏掉了“分类类别”和“用户明确动作”的区别。

已确认根因：共享`isModificationOperation`只根据classifier输出enum判断新修改命令；裸日期或时钟补答可能被classifier默认归为REPLAN_DAY澄清，于是被误当明确新命令，丢弃旧pending并使resume返回null。实施方正在增加**原文动作证据与合法修改enum双条件**，而非用enum代替原文授权。此前静态source identity与full-r2真实失败均保留，不改旧测试、不改独立题gold、不重写历史结果。

本审查者未运行Maven，未修改生产、运行器或计分。当前只记录实际失败与待修状态；待root定向回归及full-r3真实结果后再决定是否收尾，不能保持“最终通过”表述。

### root最终全量实测回报与质量验收边界

root回报最终`full-r3`实际 **BUILD SUCCESS**：1580项运行、0失败、0错误、50跳过，实际通过1530项。该结果覆盖修正后的原文动作证据与操作类别边界；本审查者没有运行Maven，以上为root提供的实际执行结果。full-r2失败和本审查静态漏看classifier默认值的记录不删除、不改为“此前一直通过”。

最终独立题测量`frozen-final-5modes-3runs-r2`正在执行五模式三轮；期间生产和运行器均冻结，本审查者只更新文档，不改源码、题集或计分。**代码回归通过，质量验收仍待最终raw结果及人工不可观测项复核**。至此停止扩审，不能预报holdout达标。


## Root最终实测收尾（独立原审查不覆盖改写）

2026-10-17：full-r3旧版本1580run/1530pass/50skip已通过；其后独立冻结测量有效发现ReplaceStopAdjustmentStrategy候选Map.of可空元数据NPE，且新增别名回归发现同class List.of可空displayName。两处已窄修，未补假数据/改语义或金标。最终组合Maven1581run/1532pass/49skip/零失败错误；五模式各三轮sourceStable且场景异常0，严格匹配均22/28，低于预定90%，所以整板块质量仍NOT_ACCEPTED。先前20/28有效失败、评分器无效试跑、full-r1/r2及定向失败全部保留。当前源码最终身份以交付manifest与post-nullguard运行freeze为准，原审查identity不重写。Root还在CH05观察到日期更正被额外列为待办，自动原子评分未覆盖此项，另记已知限制，不将自动22匹配称为人工认定22完整正确。详见CONTINUATION_AUTHORITY_CLOSEOUT_20261017.md。

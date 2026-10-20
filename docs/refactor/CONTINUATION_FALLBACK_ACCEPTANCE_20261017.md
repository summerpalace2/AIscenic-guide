# 多轮续接回退：三项修复验收

状态：三项修复及受控故障验证通过；不宣称整个AI聊天或全部回退已完成。门禁已按用户人工裁决收尾，本文件是后续独立板块。

## 实际修复

- 缺地点时用户只更正日期，不再清空原任务；保留操作、缺项、pins、条件和偏好，改日清除旧站点实例ID。完整新命令、取消、错误pending ID、过期、版本校验不被绕过。
- 删除/替换先限定已确认日期，再解析站点名称。同日多个同名目标需明确选择；具体实例ID可消歧，错日选中卡片和旧目标ID不可覆盖日期。ADD再次访问和日级重排不受单站消歧检查误伤。
- 自然时段、复合操作及完整修正复用分类器持有的同一个taxonomy，不新增时段词表。
- 新回归发现目录可选displayName缺省会在List.of构造时NPE。改成已有空值安全matchesText短路判断，不补齐fixture掩盖错误。

独立审查确认并促成了ADD/REPLAN过度追问边界修正；当前未扩展CF007/023初轮操作识别，也未改前端。

## 测量纠错与当前基线

旧历史1/5仅核日期，有一次删除变成REPLAN仍被记匹配，不能沿用为当前真实续接成功率。新脚手架真实preview，30场景69轮，不apply或写正式行程。

v1原baseline-r1保留：7/22字段匹配，但完整原名Contains与公开intent pin字段断言不符合实际契约。新v2只改CF008/022/024实体ID检查、pin字段作诊断且保留proposed保护断言，原query/fixture时间/站点不变，journal=revision-v1-v2.json。标签修订带来的7到11不是生产提升。

修正基线baseline-v2-r1：22暂定可评分、8 PENDING_LABEL；11/22 intentContractMatch、初轮operation20/22、初轮已识别后续接11/20，0异常。资源SHA a596a3fdb9730f5d8764ea7a17f9f14aa14dd93b2c2d8bdd08239ada461fb713。业务执行成功率不计，因为没有apply；合法NO_CHANGE预览与实际变动分开。

## 修后验证口径

同一v2资源，默认DISABLED必须真实modelCalls=0；THROW/INVALID/TIMEOUT经真实LlmIntentExtractor和mock模型注入，记录实际调用与PROVIDER_ERROR/INVALID_OUTPUT/TIMEOUT。没有实际触发则脚手架失败，不能用mode标签假装故障已测。TIMEOUT使用现有最小500ms预算、可释放latch、有界测试池并逐case清理；不证明真实供应商取消或计费停止。

定向r1：110run，0 failure、1 error（旧可选展示名NPE已定位并修复），原失败保留。后续结果见修后实际结果；不把JUnit执行成功当准确率。

## 留存边界

八条待产品标注不得计作通过；agent暂定金标未全部人工确认。关闭模型与受控故障不是真实供应商稳定性；不覆盖浏览器/Redis/多实例。未明确日期但提名异天站点，以及前端跨天多选可能产生activeDay/selectedStopId不一致，保留后续口径；安全追问不等于UI体验已经优化。

## 修后实际结果

定向r2为111 run、0 failure/error/skipped。四种模式使用同一v2资源，均sourceStable、modeExercised=true、0场景异常：

| 模式 | intent契约匹配 | 初轮operation匹配 | 识别初轮后续接 | 实际mock调用 | 注入回退原因 |
|---|---:|---:|---:|---:|---|
| DISABLED | 20/22 | 20/22 | 20/20 | 0 | FEATURE_DISABLED |
| THROW | 20/22 | 20/22 | 20/20 | 43 | PROVIDER_ERROR 43次 |
| INVALID | 20/22 | 20/22 | 20/20 | 43 | INVALID_OUTPUT 43次 |
| TIMEOUT | 20/22 | 20/22 | 20/20 | 29 | TIMEOUT 43次 |

超时触发次数不等于实际mock执行次数：部分任务在有界队列等待时已超时，报告分别保留，不伪造43次供应商执行。每fixture executor已清理。该受控回退不是真实网络/SLA或供应商取消实测。

22条暂定金标下，修正基线11/22到修后20/22，初轮已识别的续接11/20到20/20。资源金标不改；CF007/023初轮UNKNOWN仍未修复，不能说全部自然语言已理解。8条PENDING_LABEL不算通过，未全部人审；合法NO_CHANGE不算业务执行成功。

所有给定测试的原trip不变、repository mutation=0。**不声称pin安全检查全部通过**：CF012第2轮在无pending时主动发送activeDay1及空pins，预览确实移除d1-s3，修前后都存在。只读核对真实同页面入口每次发送当前pins，且预览不清pins，所以此API探针不等价于同页固定站点丢失；也不能证明跨刷新恢复（目前未持久化pins）。保留原失败及独立审查结论，不因PENDING_LABEL掩盖。无pending时对已有预览补数量/日期如何承接，属于后续产品上下文范围。

最终全量后端回归：1536 run，0 failures、0 errors、48 skipped，即1488通过；BUILD SUCCESS。证据：v6-continuation-backend-final.log。跳过项不算通过，受控故障评测另行显式执行。没有启动/重启实际服务、apply、提交、推送或部署。

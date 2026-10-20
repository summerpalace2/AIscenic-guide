# 多轮续接确定性回退：下一板块

V6门禁三轮后转入本板块，不再继续补门禁提示或开V7。V6人审待办独立保留，不阻断此处非依赖工作。

先重新跑当前基线：ContinuationFallbackEvaluationTest，关闭LLM，真实PlanAdjustmentService.preview，30场景69轮；操作、日期、missing fields、路由、保护条件、原草稿及repo写入全部记录。22暂定标签与8待产品标注分别报告，不能将待标注算通过；旧1/5只核日期，有错操作仍匹配的盲点，不直接当当前失败率。

修复候选必须经过本轮复现后落实：
- 在缺TARGET_STOP时用户只更正日期，不应清空原任务或把日期当地点；保留操作、未补字段和保护条件，更新日期后继续追问。
- TIME_RANGE续答应使用已配置的时段政策，不能因为resolver收到null而与初轮表现不同；不添加地名/美食/时间关键词名单。
- 取消、新完整命令、错误pending ID、版本变化、TTL过期继续走原安全分支，不能为了续接而复活旧任务。

分类器初轮没识别与续接丢意图分开统计。测试验证operation是必选金标；NO_INTENT/UNKNOWN/REPLAN不可冒充REMOVE成功。模型关闭的基线与模型超时/非法响应受控回退分别验证，实际供应商故障不在本地脚手架中伪造为实测。

本板块不允许apply/提交正式行程/真实持久化；preview不得改原trip或写repository。后续修复由实施智能体完成，另一智能体只读复核，主智能体串行运行Maven，避免共享target编译冲突。

## 当前基线已执行

原baseline-r1按v1检查为7/22字段匹配，但名称规范化和公开pin字段检查错误，不作生产故障率；原始资源/源码/结果保留。修订journal=revision-v1-v2.json；v2仅改CF008/022/024实体ID断言与pin检查口径，原输入、时钟、站点时间都不变。

baseline-v2-r1已执行：sourceStable，30场景/69轮，22暂定可评分、8 PENDING_LABEL，0异常；intentContractMatches=11/22，初轮operation=20/22，初轮识别后continuation=11/20，actualChangePreviewCount=10、noChangePreviewCount=4。没有apply，不能称业务执行成功率。名称/pin测量修正带来的7到11不算生产质量改善。

本次只修已复现三项生产错误：跨槽日期丢失、同名站点跨日误选、自然时段配置未传；CF007/023初轮UNKNOWN单独保留，暂不扩展关键词规则。修复后使用同一v2资源新run比较，不重标题。


收尾：三项修复完成并经独立审查；四类受控模式均20/22暂定契约匹配，识别初轮后的续接20/20。最终后端1536 run、1488 passed、48 skipped、零失败/错误。剩余初轮识别、8条待标注、无pending预览续接及CF012边界详见CONTINUATION_FALLBACK_ACCEPTANCE_20261017.md，不宣称全功能完成。

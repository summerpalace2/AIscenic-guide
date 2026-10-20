# V6证据门禁收尾

最终状态：ACCEPTED_WITH_KNOWN_LIMITATIONS。用户已核对并确认十二项裁决及错误轮次；达到事先约定的有限收尾标准，门禁线关闭。原始自动分数不改，最终人工裁决单独记录。

V5原始未通过结论保持不变；本聊天用户已明确逐条确认八项裁决，记录在V5_HUMAN_REVIEW_20261017.md及v5-human-adjudication.json。没有把AI复核追认成人工。

本轮交付：完整名称解析后把同城已核验身份传给assess；IDENTITY单独表示名称关系，catalog-identity-资料不能用于政策、个人资格或补充条件追问；证据状态拆成SUPPORTED_VALUE/STATED_NEGATIVE/NOT_STATED，后者Java保留UNKNOWN；ALTERNATIVES/UNKNOWN资格结构不裁个人资格，前端固定答案说明能力边界，不谎称只缺用户资料。OR分支尚不支持，此限制在V6命题前声明，不改V5 gold。

未增加新的地名/食物/时效词表，也未建立新语义评分模型；删除了初稿里多余且有子串混淆风险的目录预扫描，复用现有resolver完整名称优先与同城歧义检查。独立审查发现目录身份可能被用于missingConditions的问题，已修复并加受控回归。

## 评价口径

V6固定50事实题+10对话题，独立agent拟定（非人审），SHA256 df3b324392d60b78ce8a53b518a11c85b29b5a0dea5a104634f982502f064dd0。实施者在冻结前未读题集内容。三轮用同一源码和评分器，temperature0、显式关闭供应商thinking；真实模型+预选合成证据，不是检索召回评测或浏览器首字延迟。

事先声明：每轮真实误放/误拒案例各≤2，歧义全部经真实人工裁决。原自动标记保留；无歧义自动计分，其余进入review-queue.json/.md。用户未裁决前PENDING_HUMAN；无论是否完美，本轮后三轮不再继续门禁质量迭代，剩余限制公开，转多轮追问回退。未见题开始测量后转为已见诊断题，不复用其成绩宣传下一轮泛化。

工程回归：专项r1为82 run、0 failure、1 fixture error（Mockito restub触发旧answer）；修正测试设置后r2为82 run、0 failure/error/skipped。后端全量1523 run、0 failure/error、47 skipped，即1476实际通过。没有删题或跳过失败。已知供应商参数HTTP测试仍在全量中覆盖。

V6模型结果待收集。生成的一页说明：CLAIM_GATE_ONE_PAGE_20261017.md。

## 下一板块

多轮回退先重新测量当前preview完整链路，不沿用旧1/5。旧评测只核日期/clarification，有一次删除变REPLAN仍被误计匹配；新30题必须标操作、日期、缺失槽和保护条件，确认没有apply/持久化。新资产仅在V6实测完成后运行，生产源码在三轮冻结期间不改动。

## 首次运行失效与重新冻结记录

第一次输出three-runs被标INVALIDATED_FIXTURE：评测器构造catalog时用题目city覆盖显式item.city，把外城条目变成本城，跨城样本失真。等run-1完整journal落盘后停止，原输出及partial run-2不删除；它不是有效三轮验收，也不能当真实跨城漏洞。

修复夹具只尊重item.city（缺失才回退题city），加入真实resolver跨城/共享别名回归，不改gold。失效运行还发现合法policyShape附在RULE上会让解析器拒绝整问。重新冻结前只修此新引入的辅助字段耦合：严格验证枚举后，非ELIGIBILITY的shape归一UNKNOWN；ELIGIBILITY原值保留，OR仍拒绝。不放宽conditions、conditionsComplete、缺字段或引文校验，不改提示词。

重新有效运行另存three-runs-valid-r1，必须报告新源码SHA；不把两次输出拼成连续三轮。本题集已有失效试跑反馈，后续成绩属于修正夹具后的回归/稳定性证据，不能再次宣传为完全未见泛化。按用户要求本轮后结束门禁，不开V7补题刷分。

## 最终三轮与人工裁决

| 轮次 | 原始自动FA/FR | 人工结合无歧义项后的真实FA/FR | p50秒 | p95秒 | 对话意图错误 |
|---|---:|---:|---:|---:|---:|
| 1 | 10/10 | 0/0 | 3.146 | 4.010 | 0/10 |
| 2 | 10/11 | 0/1 | 3.012 | 3.633 | 0/10 |
| 3 | 9/11 | 0/2 | 2.988 | 3.776 | 0/10 |

原始review-queue及summary的PENDING_HUMAN保持为测量时历史状态，最终退出依据是three-runs-valid-r1/human-adjudication.json。用户明确回复“已核对，同意这12条裁决及错误轮次”。十二题联合全部经过人类确认，不是AI自审冒充人工；不等于全部gold或整个产品都经人工验收。

已知真误拒：004第2/3轮合成主体不在原文连续span；008第3轮公告引文没包含所填日期，整份verdict拒绝。这些安全校验保留，不再为分数放宽。三轮合计316次逻辑模型调用，377350 SDK total token，平均每题次1.756次调用、2096.4 token。0供应商errorClass，3次INVALID_OUTPUT均有返回token；不叫供应商零失败SLA，不称验证了live retry恢复。费用未估算。

修订后的后端全量：1526 run、1478 pass、48 skipped、0 failure/error。三轮执行JUnit成功只表示测量和冻结检查成功，质量退出依据另有人审记录。最终独立复核：evidence/claim-gate-v6-20261017/independent-review.md。

OR仍保守UNKNOWN；类型/属性对齐仍需人工；目录身份不支撑政策/现场；没有检验真实检索召回、最终生成faithfulness或浏览器首字延迟。V6部分题已在失效试跑暴露，不能宣传完全未见泛化。剩余限制公开，本轮不再做V7。

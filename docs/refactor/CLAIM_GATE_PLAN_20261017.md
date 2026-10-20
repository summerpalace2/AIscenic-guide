# 结论级门禁实施与验收约定

本轮用户授权：按结论分别判断；规则不等于个人资格、公告不等于现场或未来保证；非事实对话正常回应；门禁温度0；有界短重试；供应商用量和延迟测量；独立v5固定题集连续3次，保留每题稳定性。未通过不得宣称完成门禁，不修改旧gold。

## 契约

Analysis保留旧构造器，新增intent（FACTUAL/NON_FACTUAL）、userFacts（id/quote，必须锚定原始用户文字）和measurements。Requirement保留id/subject/attribute/timeScope/conclusionType；conclusionType扩为REGULAR/RULE/ELIGIBILITY/ANNOUNCEMENT/ACTUAL/GUARANTEE。

- RULE：介绍规则，不裁决个人满足条件。
- ELIGIBILITY：个人资格，必须有完整必要条件清单与用户事实逐项对应。只支持可明确表达为共同必要条件的规则；复杂替代条件不能完整表达时UNKNOWN。
- ANNOUNCEMENT：只能说明原文日期对应的公布安排，需真实日期引文；不推断实际发生。
- ACTUAL/GUARANTEE：静态资料不支持现场状态或未来保证，单项UNKNOWN，不牵连其他支持项。
- REGULAR：文档可说明的其他常规事实，包括历史事实和有据时间计算。不能冒充个人资格或现场核验。

Support保留引用字段，扩announcementDateQuote（非公告为null）、conditionsComplete、conditions。Condition包含id/evidenceId/ruleQuote/state（SATISFIED/CONTRADICTED/UNKNOWN）/userFactId。用户事实id必须存在；ruleQuote必须来自证据；缺条件不得默认为满足。Java验证清单完整性标记、对应存在性和原文出处，语义真实性仍依赖模型，不宣称形式化蕴含证明。

每项输出RELEVANT或UNKNOWN。汇总为RELEVANT/PARTIAL/UNKNOWN/NON_FACTUAL。PARTIAL单独保留已支持结论与仍未知的结论，聊天使用确定性模板，规则、公告均注明口径；不得由额外生成调用翻转未知。UNKNOWN继续仅提供来源链接。非事实对话调用正常生成，但提示不得新增景区事实；其语义分析失败仍UNKNOWN。两个公开聊天入口遵守同一契约。BFF不把PARTIAL当整问已核验，旧facts/enrichment只消费全部RELEVANT。

## 测量与停止条件

语义适配器按请求temperature=0，不改变普通聊天创作参数。每阶段总等待预算包含最多1次短重试；仅网络/超时/429/5xx重试，schema/证据不足不重试。取消不保证供应商停止计费，SDK内部重试若无法逐次计量须披露。

LanguageModelPort增加可选测量结果，缺失token为null；采集成功响应用量、失败/重试事件、每阶段和整请求耗时。不编造价格、失败请求token或实时生产成本。p50/p95注明测试证据为预选资料，不能冒充浏览器端到端首字延迟。

v5由独立命题者先冻结，新增结论级gold，不追改v4问题级标签。每次分别计算不支持结论的误放、应支持结论的误拒、覆盖率与非事实路由；三次记录逐题分歧。项目标准为每次两类案例错误各不超过2条（25个主要支持案例、25个主要应拒/部分案例），且不可用路径零错误放行。失败与供应商错误不剔除；人类复核未完成须明确。达到后结束门禁，转多轮续接回退，不继续无限优化。

评分前置细化：50事实题25主要可答、25主要应保留未知/部分可答；另10对话题独立统计，不混入事实误差分母。结论按type+同主体/已核验别名做一对一gold slot对齐，不把额外背景规则当个人资格；同主体同类型有多属性时此匹配不能替代语义人工审查。误放案例指任一gold UNKNOWN结论被放行；误拒案例指任一gold RELEVANT结论未获支持（包括部分题中应介绍的规则），所以实际两个分母可能重叠，不再都是25。每次两类错误案例均≤2且10对话意图全对，三次均满足才达标。缺失slot另记覆盖数，未知slot缺失不算放行；不将汇总PARTIAL当误拒。稳定性逐个gold slot及整题状态报告，不重新采样、改gold或挑最好一轮。

StageMeasurement.temperature是请求参数；v5 journal明确调用真实Spring adapter的completeMeasured并设置该参数，不靠兼容默认port推断生效。温度0仍不等于供应商固定权重/确定性承诺。逻辑模型调用事件不等于HTTP请求数，成本仅列实际获得的SDK token metadata与缺失量，不推断金额。

兼容性：Rag API空城市保留原GLOBAL资料检索（不检索其他城市）；Chat先允许非事实分类，再对未明确目的地的事实问句追问，避免新增全局知识API回归。

供应商实测前修正：官方DeepSeek思考模式默认开启，且忽略temperature；先前仅能证明请求0。本轮显式配置RAG_SEMANTIC_DISABLE_THINKING=true，仅measured门禁HTTP请求关闭thinking，普通生成不变；非兼容供应商必须false。首次调用保留完整阶段总预算，只有快速瞬时失败且预算有余时短重试，预算耗尽不再重试。原known-smoke失败保留，修正后再冻结v5。依据：https://api-docs.deepseek.com/guides/thinking_mode/ 。

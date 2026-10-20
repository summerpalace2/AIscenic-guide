# 正常模型与禁用模型配对测量

当前状态：测量完成，非最终产品验收。v2题集不改，历史8条PENDING仍按原口径排除；用户新确认口径另存human-adjudication.json，后续才按新契约验收。

两个run的productionTreeSha256相同且sourceStable。LIVE live-v2-r1与paired-after-pins-disabled-r1均20/22契约匹配，初轮识别后均20/20续接；未发现暂定可评分题中禁用正确而正常失败的案例。CF007/023仍初轮失败。原历史20/22来自不同生产版本，不能直接归因差异。

LIVE实际43逻辑adapter调用，42次成功返回；6轮LLM_STRUCTURED、39轮DETERMINISTIC_FALLBACK、23轮PENDING_INTENT、1轮未返回抽取来源。成功供应商返回不等于结构化有效采用。fallback计数中的空字符串等按原结果保留；低置信/非法输出/超时均计入链路，不剔除。

记录SDK tokens85940，1次unknown usage，不能当完整成本。69轮含无模型补答的整链p50约1.63秒、p95约2.15秒；不等于浏览器首字延迟。3500ms抽取预算，测量非thinking/temp0与线上thinking配置不必然等价。逻辑adapter调用次数不等物理HTTP数，SDK可能重试；本地中断不证明供应商停止计费。

原始run、逐字段对照、source-before/after和summary均保留于evidence/continuation-fallback-20261017。不以本组诊断旧题证明独立泛化，不宣称真实供应商故障SLA已测。

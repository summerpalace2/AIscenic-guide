# AI 效果评估入口

这一阶段建立可复查的**离线组件基线**，不修改生产规则，不调用付费模型，不把合成语料或回归测试通过率写成真实 AI 准确率。旧 `ai/src/test/resources/rag-evaluation/` 40 问保留，旧指标按预期景点实体命中计算，不能冒充本文的文档 Recall。

## 一条命令运行

在仓库根目录执行（Windows 需要 PowerShell、项目配置的 JDK 与 Maven wrapper，Node 使用项目现有运行时）：

```powershell
node tools/ai-evaluation/run.mjs --run-id baseline-20261017-01
```

默认也可省略 run-id 自动生成。每次在 `ai/target/ai-evaluation/runs/<run-id>/` 新建目录，已存在的运行不能覆盖。工具先冻结源码/构建配置/数据指纹，串行执行两个 opt-in JUnit 评测类，再检查源文件不变、结果 runId 一致、数据哈希和 case ID 全覆盖，保存原日志、逐例结果及 summary。工具运行 Maven 时不要并行启动其他 Maven 或编辑相关源码/标注。

`target/ai-evaluation/*-results.json` 是生产者的临时输出，可能被下次运行覆盖；审阅必须使用 runs/<run-id> 的原始和汇总快照。首次构建需要已安装的项目依赖，依赖下载不等于调用模型供应商。

评估器机械回归：

```powershell
node --test tools/ai-evaluation/*.test.mjs
```

JUnit 或 Node 成功只说明测量工具运行成功。每例 `QUALITY_FAILURE` 必须保留，低分不会通过修改标签、略过样本或自动修生产代码来抹掉。当前没有伪造质量阈值或合并成单个“AI总准确率”。

## 数据与实际执行范围

| 数据 | 数量 | 实际执行 |
| --- | --- | --- |
| intent-cases.json | 50条、5城、10相关family、50种完整措辞 | 45条单轮生产分类器；5条实际两轮 PlanAdjustmentService.preview（LLM关闭），分别统计；不是真实模型成绩 |
| planning-cases.json | 50条、45 PLAN/5 CLARIFICATION | 生产条件解析、目录构建、路线规划/校验；使用现有目录/静态估算，保留缺失坐标 |
| retrieval-cases.json | 100条、20family、75可答/25无答案 | 生产RAG协调器、本地知识排序、真实临时SQLite和证据门禁；语义供应商被明确设为不可用 |
| retrieval-corpus.json | 30篇：25合成、5原资源逐字复用 | 受控测试知识库，不是生产全国知识完整性或现实准确性验收 |
| answer-evidence-cases.json | 24条 | 回答证据评估器契约样本，不是实际模型回答效果 |

标签由智能体按文本和受控语料显式拟定，经独立子智能体复审，仍是 provisional；没有独立人类金标。相关family不能当作统计独立样本，不能直接给真实用户总体准确率或置信区间。运行后更正标签必须新版本、新运行并保留原结果及更正原因。

2026-10-17 的 run03 使用规划标签 v2：删除5条仅说“带父母”却被标成“必须低步行”的错误预期，其他需求不变；原标签和 run02 成绩均保留。追问协议 v2 改为实际两轮 preview，只采用服务返回的 intent，不在续接失败时回填原意图。新旧分数变化属于测量修正，不能宣称生产质量改善。

规划走目录组件路径，未执行动态起点 RuntimeLocationPlanner、实时餐饮/地图、完整首次规划HTTP、偏好授权或修改状态流程。可行性是已有条件下的静态/估算校验，`requirementsComplete=false` 表示只核对已标注需求子集。空计划不算可行，无元数据不算满足低步行要求。

## 指标含义与分母

- 意图：全部标注字段 exact match；各字段单独分母；operation只对标注operation的题计分。标注禁止修改的请求若被分类为修改，记录 inappropriateMutationIntent，这是分类输出风险，不是实际错误写入率。
- 规划：仅45条 PLAN 进入非空、静态可行、需求子集满足和三者同时成立的分母。5条应追问另算 outcome accuracy，不混入可行率。
- 检索：按文档ID计算 `Recall@K=TopK中相关文档数/标注相关文档数`，MRR为首个相关文档排名倒数；只对75条可答问题求宏平均。这里是本地降级 Top5、门禁前的排名，不能解释为dense召回率。
- 门禁：以“应拒绝无答案”为正类，分别给precision/recall；另给无答案被放行率、可答被误拒率及城市泄露。检索命中、门禁判断和最终回答真实性是三件事。
- 延迟：进程内组件每例耗时、p50/p95，非模型请求/HTTP/冷启动延迟。未调用模型，token使用量为null、明确NOT_APPLICABLE，不伪造统计。
- 未执行：真实LLM、dense、BM25、hybrid、真实世界可行性、实际回答faithfulness均NOT_RUN/NOT_SCORED。不要拿这些空项当0分或100分。

## 回答证据评审

```powershell
node tools/ai-evaluation/answer-evidence.mjs --input answer-capture.json --annotation-template --output review-template.json
node tools/ai-evaluation/answer-evidence.mjs --input reviewed-answers.json --output reviewed-results.json
```

输入每条回答全文、证据片段ID/正文及逐claim精确原文、类型和引用ID。评审者须检查claim清单完整性，为每项填写 SUPPORTS/CONTRADICTS/INSUFFICIENT 及理由；模板包含回答、证据和claim清单三重哈希，任何修改使原评审失效。工具自动核验引用存在性，但不自动理解语义、不验证评审者身份，不用词语重叠或再次询问同一个模型充当事实真值。

缺标注为NOT_SCORED，部分标注必须同时报告coverage，完整事实支持率为null。引用存在、引用支撑结论、资料符合现实三者分开，realWorldTruth固定NOT_EVALUATED。24条合成样本只测试该工具的合同，不能拿它的支持率作为项目模型指标。

## 下一轮实验

先阅读失败清单，独立确认标签和问题来源。之后固定同一语料、标签、切片与候选预算，比较dense、关键词/BM25及融合策略，再比较重排前后。真实供应商运行须另外记录供应商/模型/配置、调用预算、token、错误及端到端耗时；本工具没有静默开启这些调用。生产追踪和仓库整理是后续独立板块，不能因本工具提供eval traceId就宣称生产分布式追踪完成。

# 重庆单城检索实验：先排名、池化盲标，再评分

2026-10-17。本方案在首轮排名运行前冻结配置，按用户最新决定替代旧方案中“先穷尽标注再运行排名”的顺序，以及10+10的严格总预算主表。旧方案与题集不删除、不改成绩。本轮不做任何权重或阈值调参。

## 范围与冻结输入

实验回答“固定文档与问题是否相关”，不回答文档是否符合现实。222篇全部构建文本/向量索引；80个问题均为重庆市，排名前按城市选218篇候选。其他四城各一篇不进入问题或主指标，不外推全国。

语料、来源manifest、原始80题、模型配置及模型凭据代码均锁定SHA。runner从80题中只提取id/query/city/split/familyId，不读取旧相关文档、hard negatives或UNKNOWN标签作排名和评分。开发20题与保留60题分开，开发餐饮/保留景点的分布差异继续保留为限制。来源未核实和到期文档不阻塞文本实验，也不升级为当前事实证据。

本轮机器配置：[experiment-config.json](evidence/retrieval-pooling-20261017/experiment-config.json)。运行前配置SHA为 `965b74e270b9ee46fe8f2ac9b5c2a52ffa99e34d1e6ca29fa2841024b4be3b8c`。配置与runner不能在运行中修改；原始配置字节随运行保存，结束再次核对全部输入和代码。

## 五种固定方法

| 方法 | 固定设置 |
|---|---|
| dense | 本地BGE-small-zh-v1.5、CLS+L2、余弦内积；query加模型README推荐的中文检索前缀；文档不加前缀 |
| BM25 jieba | rank-bm25 BM25Okapi；k1=1.5,b=0.75,epsilon=0.25；jieba0.42.1默认词典锁SHA；HMM=false，不加用户词典 |
| hybrid | dense与jieba BM25等权RRF，k=60，不归一化原分数、不调参 |
| hybrid+rerank | 同一hybrid的Top20交本地BGE-reranker-base，返回logit顺序，不解释为概率 |
| BM25 bigram | 字符二元组消融；Unicode字母数字连续段内相邻双字，保留单字符段，不跨标点；其余BM25参数相同 |

两种词法均先Unicode NFKC和小写化；jieba保留含字母数字的token，中文属于字母。IDF分别在同城候选文档上构建。词法索引使用完整title+换行+content。

dense和BM25各预取Top50，RRF合并去重后取Top20，reranker只处理这20篇；各方法输出20篇，池化深度10。融合最多查看100篇候选，因此本轮并不声称相同召回工作量。BM25 bigram不参与hybrid，只作为独立消融，也进入标注池。

本轮为文档级，无chunk金标或chunk指标。模型输入上限512 tokens；dense截断文档，reranker对query/document pair采用longest_first，BM25读全文。这一可见文本差异是明确限制；保存dense逐文档/查询token数、reranker每对原始token数及截断策略，不将差异全部归因于融合算法。

固定CPU4线程、eval模式、种子和模型commit，离线加载safetensors，不执行远程代码。文档向量一次构建后供三轮复用，每轮重新执行查询embedding、词法评分和真实rerank，不能将缓存重放称为新模型调用。

## 排名、失败与时延

三轮均跑完整80题，保存每方法逐题排名、分数、分阶段时延和分支候选。失败保留ERROR；前序失败造成未执行与本方法异常用errorKind区分，不能合称供应商故障。候选不足或缺方法进入池覆盖警告，不能静默剔除。

时延为CPU预热索引后的组件计时。dense与BM25结果在同题hybrid中复用，hybrid时延为阶段之和，rerank额外加真实重排阶段；不冒充四条独立HTTP请求或浏览器端到端延迟。模型加载和建索引单列。三轮用于稳定性，不是三倍独立样本。

## 池化与人工盲标

五方法×三轮Top10按题联合去重，不根据旧正例名单筛池、不缩减深度来迁就结果或标注量。固定哈希顺序打乱问题与候选，盲标交付只显示匿名ID、题文、城市、标题与正文，不含方法、真实ID、排名、分数、split或旧标签。私有映射另存，盲标时不应查看该文件或原始排名。

等级0=不相关，1=部分相关，2=直接回答，未判=null；只评价对所问主体/属性的文本支持，不把同主体背景或“资料可信”混为相关。原先rq-057的部分支持争议会自然进入新池，不能机械继承旧标签。没有人工时不自动用LLM判断伪装人工。

离线HTML支持按题翻页、搜索、等级和理由、导出JSON、导入续标。不自动保存，页面明确提醒导出；本地导出不发到外部服务器。需要复核者姓名声明，但工具无法独立认证其人类身份。候选输入和盲正文分别以SHA绑定，正文误编辑、跨版本续标、未知/重复候选和非法等级均拒绝。

## 评分与配对

整题所有池内候选判完后才评分；未判不能当不相关。即使部分题已判，也要报告覆盖率，完整实验不得由部分结果冒充。失败或覆盖不足继续显示，不进入该题完整配对分母。

- nDCG@1/3/5/10：gain=2^grade-1，IDCG来自整个已判池，不能按方法返回数缩小理想序列。
- pooledRecall@1/3/5/10：grade>=1为相关，分母为该题已判池中的相关文档数。**不是穷尽全库Recall**；未入池文档相关性未知。
- MRR@10：首个grade>=1结果的倒数名次。没有已判相关文档的题单列，不给满分。
- 按城市、开发/保留切分分表。每题先对三轮取平均，再比较hybrid对dense、rerank对hybrid、jieba对dense、bigram对jieba的胜/负/平，保存逐题文本、差值与family，不把三轮算三倍胜负。

human annotations缺失时只交付排名、覆盖、稳定性和组件时延，质量字段必须为NOT_SCORED。人工标签冻结后才计算质量；若后续有新检索方法带来未入池候选，须补判新候选再做比较，不能沿用旧池将新文档当0。

## 命令

在 `D:/scenic-guide`，运行目录必须不存在：

```powershell
& D:/codex-runtimes/scenic-guide-retrieval-20261017/venv/Scripts/python.exe -B -u tools/ai-evaluation/run-local-retrieval.py --config docs/refactor/evidence/retrieval-pooling-20261017/experiment-config.json --runtime-root D:/codex-runtimes/scenic-guide-retrieval-20261017 --output-dir <新的运行目录>
node tools/ai-evaluation/retrieval-pool.mjs --rankings <运行目录>/rankings.json --corpus ai/src/main/resources/knowledge_documents.jsonl --output-dir <新的池目录>
node tools/ai-evaluation/retrieval-pool-score.mjs --rankings <运行目录>/rankings.json --pool <池目录>/blind-pool.json --mapping <池目录>/private-pool-map.json --output <新的评分回执.json>
```

第三条未给annotations时应返回NOT_SCORED；拿到本轮真实人工导出后加 `--annotations <标注文件>`，不要覆盖原始未评分回执。

本地实验仍不是Java→向量库→rerank生产适配器链路验收。完成人审和同题比较后，再据数据决定是否、如何接入Java。

# 重庆单城检索：真实排名已完成，等待盲标

2026-10-17。按[预注册方案](RETRIEVAL_POOLING_PROTOCOL_20261017.md)完成本地真实模型实验：80题×3轮×5方法，共1200条方法结果，0方法失败。方法为dense、jieba BM25、RRF hybrid、hybrid+rerank及字符二元组BM25消融。

**没有人工标注，不报告Recall/nDCG/MRR或“混合检索更好”。** 当前回执为NOT_SCORED，0个人工判断、0条质量评分。使用的是本地真实BGE模型和CPU文档级检索，不是mock、静态关键词降级，也不是Java/Qdrant主链验收。

## 现在可以做什么

打开[离线盲标页](evidence/retrieval-pooling-20261017/pool-01/annotation.html)，按[人工标注说明](RETRIEVAL_BLIND_REVIEW_GUIDE_20261017.md)逐题判断。页面不含方法、排名、分数、真实文档ID或旧金标。标注时不要另开私有映射或原始排名。

五方法三轮Top10联合去重得到**1671个候选判断**，每题14–30篇，中位数21篇。三轮排序一致，没有人为只取某轮缩小池。标注量从每题218篇减少到平均20.9篇，但仍需要真实人工时间；不能把未判自动填0来赶进度。

0=不相关、1=部分相关、2=直接回答，未判=null。填写实际复核者姓名并导出JSON，可分次导入续标；页面不会自动保存，刷新/关闭前请导出并确认文件下载。人工姓名只是自述，工具不会认证人类身份。有人审导出后按指南运行评分命令，必须另存新回执；不覆盖本次未评分状态。

## 本轮已测得的运行事实

- 222篇全部构建向量，所有问题均在重庆218篇中排序；其他四城不进入主指标。没有现实事实正确性或全国泛化结论。
- 五种方法全部80题的Top10在三轮中一致；这是此固定题集/运行时的排序稳定性，不是准确率或统计显著性。
- 文档embedding与4800个rerank输入对都未触发512 tokens截断。本轮没有实测到“BM25读全文、模型读截断文本”造成的窗口差异，配置仍记录这一潜在差别。
- 所有输入、模型commit/文件指纹、默认jieba词典、runner和配置哈希符合冻结身份。没有调RRF参数或用旧正例优化排名。

CPU4线程、预热索引后的组件时延如下。hybrid复用同题dense/BM25阶段，时延按组件相加；不是独立HTTP调用、Java或浏览器端到端性能。每组240个观测来自同80题三次重复，不能当240个独立样本。

| 方法 | p50 | p95 |
|---|---:|---:|
| dense | 7.33ms | 20.97ms |
| BM25 jieba | 0.83ms | 2.15ms |
| RRF hybrid | 8.38ms | 21.72ms |
| hybrid+rerank | 2149.81ms | 3158.40ms |
| BM25 bigram | 0.90ms | 1.18ms |

rerank在这个CPU配置中明显增加计算时间，但是否值得需要人审质量结果决定，不能只凭耗时或复杂程度选方案。

原始日志保留一条非致命jieba缓存写入异常：相对输出路径被其库拼接到临时目录，写缓存失败后库捕获异常并在内存成功构建词典。没有检索失败；冻结runner未在运行中被修改。未来运行可传绝对输出目录，避开该缓存路径问题；不为清理日志抹去本轮证据。

## 独立审查与验证

新增Node30项、Python4项测试通过。覆盖RRF按名次融合、稳定并列、旧标签不参与排名、池化与匿名隔离、分级指标、空相关分母、未判关闭评分、重复不扩大配对样本数、池覆盖不足、正文误编辑/跨版本续标及输出拒绝覆盖。

独立子智能体对实际产物核验：1200条方法结果均OK、各20个连续rank、有限score、同城；1671个候选title/content逐项对应原文、无重复、无漏掉的Top10结果；全部初始等级null；向量float32形状222×512；真实池覆盖完整。独立重跑评分器得到NOT_SCORED。[审查记录](evidence/retrieval-pooling-20261017/independent-review.md)保留发现与修正。

离线HTML的导入、导出、未判保留、WebCrypto正文绑定及误编辑拦截通过实际生成脚本的Node VM/DOM测试。真实浏览器工具两次报“Unable to load browser request-header policy”，因此**未完成实机浏览器交互/下载验收**，没有把仿真测试包装为真实浏览器通过。

## 证据入口

- [实际排名](evidence/retrieval-pooling-20261017/run-01/rankings.json)：含全部三轮、分支候选、实际模型回执、token长度与计时。
- [固定配置](evidence/retrieval-pooling-20261017/experiment-config.json)：RRF k60、分支50、融合/重排20、池化10、词典/模型/代码哈希。
- [运行摘要](evidence/retrieval-pooling-20261017/execution-summary.json)：计数、稳定性、截断与时延。
- [未评分回执](evidence/retrieval-pooling-20261017/unjudged-report.json)：人工判断缺失、质量指标空值、完整覆盖。
- [盲池](evidence/retrieval-pooling-20261017/pool-01/blind-pool.json)：匿名题目和候选文本，全部null等级。

下一步是人工盲标，再按开发/保留分表给出pooledRecall、nDCG、MRR及逐题配对胜负；之后才决定配置并接入Java主链。来源继续补核，但不阻塞这个文本相关性实验。

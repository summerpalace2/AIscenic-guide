# 检索语料来源恢复、冻结与本地运行时

2026-10-17。本板块已交付来源恢复、222篇文本身份冻结、独立拟题和真实本地模型冒烟。**尚未完成四组检索质量测量、人工金标和Java主链验收。** 没有修改生产行程/续接/检索代码，没有写运行库、建立第二套生产知识库、提交或部署。

## 来源恢复与事实边界

在历史工作区找回两版真实注册表（11项和18项），不是任务回执。共同11项URL一致，采用18项版本的原始字节快照作为本次实验输入，保留原路径和SHA。

- 当前47个顶层符号来源中恢复17个URL关系，涉及168篇文档；加上原有4个直接官方URL，172篇至少有一个可解析出处。另30个符号仍未解析，50篇没有已解析外部URL。
- 遍历claims后引用总数为73：4个直接URL、17个恢复注册关系、22个本地文档引用、30个未解析。22个文档引用不当作外部来源。
- 注册表的“官方”“verified”等是历史自述。两条被称为官方来源的记录实际指向本地宝域名；若无进一步证据，不能继承官方属性。主管部门主页和新闻目录也不自动支持具体预约、票价或无障碍结论。
- 222篇保持原文，65篇在冻结日已到期；124个entityId不是124个已经人工消歧的独立实体。重庆218篇，其他四城各1篇，仍不能发布全国效果结论。

在线复核登记了9个URL的观察、5个claim的初步支持判断。故宫、西湖和湖北省博物馆页面读取支持静态概述；秦陵官网由搜索索引提供支持，直接读取超时，单独标记。湖北页面显示的正文发布日期与URL年份不同，不能靠URL猜日期；页面办公时间也不能冒充游客开放时间。新重庆政府页面只作独立补充佐证，不反向伪装旧来源。

来源页面：[故宫](https://www.dpm.org.cn/about/about_view.html)、[西湖](https://sjfw.mct.gov.cn/site/dataservice/culdetails?curId=10&id=34878)、[秦陵](https://www.bmy.com.cn/about/)、[湖北省博物馆](https://wlt.hubei.gov.cn/zfxxgk/fdzdgknr/jgjj/zsdw/202008/t20200805_2740545.shtml)。这些观察是智能体复核，不是人类金标，不证明实时开放或全文转载许可。当前事实金标准入数仍为0。

## 可执行冻结工具

新增 `tools/ai-evaluation/freeze-retrieval-corpus.mjs`。同时固定语料、来源注册表、复核记录的SHA，以及逐文档正文/记录SHA。校验重新构建完整manifest后比较，输入变化立即失败；输出拒绝覆盖已有文件。引用登记只说明关系，不自动赋予事实可信度。

在 `D:/scenic-guide` 执行：

```powershell
node tools/ai-evaluation/freeze-retrieval-corpus.mjs --corpus ai/src/main/resources/knowledge_documents.jsonl --registry docs/refactor/evidence/retrieval-source-freeze-20261017/source-register.recovered.json --reviews docs/refactor/evidence/retrieval-source-freeze-20261017/source-reviews.json --verify docs/refactor/evidence/retrieval-source-freeze-20261017/corpus-manifest.json
```

[清单](evidence/retrieval-source-freeze-20261017/corpus-manifest.json)只存身份与来源元数据，正文仍以原JSONL为准。[恢复审计](evidence/retrieval-source-freeze-20261017/recovery-audit.json)和[页面复核](evidence/retrieval-source-freeze-20261017/source-reviews.json)保留来历与限制。原始语料SHA仍为 `1e191ca1a24bb1d85087833e731bbf53d60326c2d5d2dacbe36b6b9a6a0b84c6`。

## 独立题集与已知标签缺口

[80题草案](evidence/retrieval-source-freeze-20261017/provisional-queries.json)由未读取旧题、实现和模型结果的独立作者编写：开发20题、保留60题，含7题无答案；全部为PENDING。开发餐饮、保留景点存在分布差异，不能把开发结果外推保留集。正例来源符号及问题实体组隔离，但不保证真实网页来源完全独立。索引始终包含全部222篇；重庆题的同城候选为218篇。

没有可靠历史版本对，所以版本健壮性切片仍缺失。没有chunk/span金标，chunk指标不评分。80题也不是80个独立实体样本。

[盲抽复核](evidence/retrieval-source-freeze-20261017/provisional-query-independent-review.json)检查全部7个unknown和固定随机16个可答题，发现rq-057可能漏标两篇部分相关文档。原题不改、成绩不生成，留给人工裁定。下一步应先处理部分相关等级及这个遗漏，而不是把所有未列文档直接判错。

## 隔离本地模型已运行

运行时位于 `D:/codex-runtimes/scenic-guide-retrieval-20261017`，不改变项目或系统Python包。PyTorch CPU、Transformers及BM25依赖已安装，`pip check`通过；依赖版本保存于[锁定清单](evidence/retrieval-source-freeze-20261017/runtime-requirements.lock.txt)。本次使用CPU四线程，未测GPU性能。

固定模型：[BGE-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5) commit `7999e1d3359715c523056ef9478215996d62a620`；[BGE-reranker-base](https://huggingface.co/BAAI/bge-reranker-base) commit `2cfc18c9415c912f9d8155881c133215df768a70`。获取时核对Hub的commit及MIT许可证元数据，仅下载指定配置、tokenizer、safetensors和README，不执行远程模型代码。

新增 `local-retrieval-smoke.py`，下载后记录acquisition凭据。离线加载必须核对repo、commit与逐文件SHA，不能只换配置中的版本号。两个模型下载及真实CPU推理均通过；不带下载参数的离线重跑也通过。embedding形状2×512且归一化；reranker返回两个有限logit，logit不是概率。

```powershell
& D:/codex-runtimes/scenic-guide-retrieval-20261017/venv/Scripts/python.exe tools/ai-evaluation/local-retrieval-smoke.py --config docs/refactor/evidence/retrieval-source-freeze-20261017/local-models.json --runtime-root D:/codex-runtimes/scenic-guide-retrieval-20261017 --output <新的结果文件路径>
```

[联网获取后冒烟](evidence/retrieval-source-freeze-20261017/model-smoke-download.json)和[离线冒烟](evidence/retrieval-source-freeze-20261017/model-smoke-offline.json)记录实际版本、文件指纹和推理结果。两条短文本的耗时不是检索p95，不是完整语料召回、Java链路或用户端性能。

## 验证与独立审查

- Node新增测试：10/10通过；保留两项来源关系缺陷的修前8通过/2失败与修后日志。
- Python模型凭据测试：2/2通过，覆盖离线改commit、文件漂移和缺凭据。
- 实际222篇清单重建比对通过；80题结构、正负例ID、城市及声明的切分检查通过，不代表语义金标通过。
- 子智能体[独立报告](evidence/retrieval-source-freeze-20261017/independent-review.md)确认来源关系两项修复和离线版本追溯修复；未将注册关系或agent标签提升成人审。

## 下一阶段

1. 人工复核题集和rq-057部分相关争议，形成带修订记录的标签版本；来源不全的文本探索与当前事实验收分开。
2. 在固定配置和同预算下完成dense、BM25、hybrid、hybrid+rerank，保留逐题排名与失败。现阶段不报任何召回率。
3. 独立实验之外，还需将真实模型接到隔离Java检索适配器/索引后验证主链。离线CPU冒烟没有代替这项工作。

本次经验检索没有命中可采用主题；使用当前源码、历史原始资产和实际运行证据完成，不把新审计发现声称为旧经验收益。

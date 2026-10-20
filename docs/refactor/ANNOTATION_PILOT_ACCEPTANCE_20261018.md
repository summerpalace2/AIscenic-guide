# 标注流程验收与采用判定工具

2026-10-18。本轮推进可以自动完成的验收与规则执行，不填写真实候选池的人审等级。

## 已完成

- 保存/续标/导出脚本回归39项通过，涵盖两题恢复、草稿失败与旧导出兼容、开发题筛选、正文身份绑定，以及“下载发起不等于保存确认”。这是Node VM/DOM测试，不是实机浏览器验收。
- 新增 `tools/ai-evaluation/rerank-adoption.py`，按已经登记的policy执行采用判定。14项合成测试通过；合成通过数字不是检索质量。
- [独立审查](evidence/annotation-pilot-20261018/adoption-independent-review.md)核对了family聚类bootstrap、按题平均三轮、缺标注关闭统计、阶段p95和禁止生产启用等边界。
- [实际判定回执](evidence/annotation-pilot-20261018/rerank-decision.json)来自原排名与当前未评分回执：`INSUFFICIENT_NO_ENABLE`，质量UNKNOWN，质量均值及区间null；额外重排阶段p95约3149.21ms，超过1000ms预算；真实集成TTFT未测，`canEnableInteractive=false`。

本版本没有生产开关，也不接受随手输入一个TTFT数值就启用交互。它信任冻结policy和评分器质量回执的来历，不独立认证人类身份；各输入SHA留档，不能当成不可篡改签名。

## 实机验收仍未完成

为UI测试生成独立的两题、四候选虚拟样例，和真实池的ID/存储键隔离，不参与模型质量评分。浏览器工具打开该本地页时明确拒绝file协议，并要求不得绕过。因此没有改用localhost、其他浏览器表面或原生输入来绕开限制，也没有声称真实下载和重开已经通过。

真实流程仍需用户在本机手动打开[新版标注页](evidence/retrieval-annotation-20261018/pool-02/annotation.html)，按[操作说明](ANNOTATION_WORKFLOW_20261018.md)做两题“分级—导出—确认文件—重开—导入”试跑。没有向真实池填入自动化测试等级。

仅检查默认Downloads中匹配本池导出前缀的文件，没有发现匹配文件；这不代表其他路径不存在标注，也不推断用户尚未提交文件的内容。若已有导出，提供路径即可继续。

## 后续命令

拿到实际人工导出后，先用pool评分器生成新的评分回执，再执行：

```powershell
python -B tools/ai-evaluation/rerank-adoption.py --policy docs/refactor/evidence/retrieval-annotation-20261018/rerank-adoption-policy.json --rankings docs/refactor/evidence/retrieval-pooling-20261017/run-01/rankings.json --scored <新的人工评分回执.json> --output <新的采用判定回执.json>
```

开发集可以未标完而单独判断保留集，但保留集全部60题的池、重复和执行覆盖必须完整；全池无相关的题单列。缺少标签、样本或family不足时不给虚构均值/区间。当前仍不能判断hybrid或rerank的质量高低。

本次没有运行新模型实验、写生产数据库、修改冻结排名/候选或开启任何线上功能。经验查询无匹配，未采用旧经验结论。

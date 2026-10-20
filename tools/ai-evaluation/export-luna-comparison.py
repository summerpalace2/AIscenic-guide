"""Export the independent Luna pass and a small two-model review sample."""
import argparse
import csv
import json
from pathlib import Path
import random
import zipfile


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--root', type=Path, required=True)
    p.add_argument('--delivery', type=Path, required=True)
    args = p.parse_args()
    root, delivery = args.root, args.delivery
    pool = json.loads((root/'retrieval-annotation-20261018/pool-02/blind-pool.json').read_text(encoding='utf-8'))
    deepseek = json.loads((root/'ai-assisted-review-20261018/run-01/merged-prelabels.json').read_text(encoding='utf-8'))
    luna = json.loads((root/'ai-assisted-review-20261018/run-luna-01/merged-luna-prelabels.json').read_text(encoding='utf-8'))
    source = {(q['anonymousQueryId'], d['anonymousDocumentId']): (q, d)
              for q in pool['queries'] for d in q['documents']}
    dsk = {(x['anonymousQueryId'], x['anonymousDocumentId']): x for x in deepseek['items']}
    llm = {(x['anonymousQueryId'], x['anonymousDocumentId']): x for x in luna['judgments']}
    if len(source) != 1671 or set(source) != set(dsk) or set(source) != set(llm):
        raise ValueError('Pool/model pair coverage mismatch')
    rows = []
    for key, (query, doc) in source.items():
        a, b = dsk[key], llm[key]
        deep_grade, luna_grade = a['consensusGrade'], b['grade']
        rows.append(dict(queryId=key[0], documentId=key[1], query=query['query'], title=doc['title'], content=doc['content'],
                         deepseekGrade=deep_grade, deepseekPass1=a['judgments'][0]['grade'] if a['judgments'][0] else None,
                         deepseekPass2=a['judgments'][1]['grade'] if a['judgments'][1] else None,
                         lunaGrade=luna_grade, lunaReason=b['reason'], lunaQuote=b['supportingQuote'],
                         lunaConfidence=b['confidence'], comparison=('Luna与DeepSeek同分' if deep_grade == luna_grade else
                             'Luna与DeepSeek分歧' if deep_grade is not None else 'DeepSeek无双轮一致分')))
    disagreements = [r for r in rows if r['deepseekGrade'] is not None and r['deepseekGrade'] != r['lunaGrade']]
    frame = sorted((r for r in rows if r not in disagreements), key=lambda r:r['documentId'])
    rng = random.Random(20261018)
    review = [{'selection':'模型分歧随机抽样','selectionReason':'从35条可比较的跨模型分歧中固定种子随机抽取20条', **r}
              for r in rng.sample(disagreements, min(20,len(disagreements)))]
    rest = [r for r in frame if r['deepseekGrade'] is not None]
    review.extend({'selection':'同分随机抽样','selectionReason':'从两模型同分项固定种子随机抽取10条', **r}
                  for r in rng.sample(rest, min(10,len(rest))))
    delivery.mkdir(parents=True, exist_ok=True)
    fields = ['selection','selectionReason','queryId','documentId','query','title','content','deepseekGrade',
              'deepseekPass1','deepseekPass2','lunaGrade','lunaReason','lunaQuote','lunaConfidence','comparison']
    def safe(value):
        return "'"+value if isinstance(value,str) and value.lstrip().startswith(('=','+','-','@')) else value
    for filename, data in [('GPT6Luna全量预标.csv',rows),('GPT6Luna模型分歧与抽样复核30条.csv',review)]:
        with (delivery/filename).open('x',encoding='utf-8-sig',newline='') as f:
            writer=csv.DictWriter(f,fieldnames=fields,extrasaction='ignore');writer.writeheader()
            for row in data: writer.writerow({k:safe(v) for k,v in row.items()})
    counts={str(i):sum(r['lunaGrade']==i for r in rows) for i in (0,1,2)}
    meta={'lunaRows':len(rows),'lunaGrades':counts,'deepseekLunaComparable':sum(r['deepseekGrade'] is not None for r in rows),
          'crossModelDisagreements':len(disagreements),'crossModelDifferentButNotComparable':sum(r['deepseekGrade'] is None for r in rows),
          'reviewSample':len(review),'sampleDisagreements':sum(r['selection']=='模型分歧随机抽样' for r in review),
          'sampleAgreements':sum(r['selection']=='同分随机抽样' for r in review),'seed':20261018,
          'interpretation':'同分与分歧只描述模型输出，不代表人工正确率；DeepSeek为同模型两轮，Luna为单轮。'}
    (delivery/'GPT6Luna核验摘要.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(meta,ensure_ascii=False))


if __name__=='__main__':
    main()

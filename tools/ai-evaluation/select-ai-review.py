"""Select a small human review without turning AI suggestions into human gold."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import random


def write_new(path, obj):
    with path.open('x', encoding='utf-8', newline='\n') as out:
        json.dump(obj, out, ensure_ascii=False, indent=2)
        out.write('\n')


def select(pool, merged):
    expected = {(q['anonymousQueryId'], d['anonymousDocumentId']): (q, d)
                for q in pool['queries'] for d in q['documents']}
    rows = merged['items']
    keys = [(r['anonymousQueryId'], r['anonymousDocumentId']) for r in rows]
    if len(keys) != len(set(keys)) or set(keys) != set(expected):
        raise ValueError('AI labels must match the entire blind pool exactly')
    if merged['poolId'] != pool['poolId'] or any(r['humanGrade'] is not None for r in rows):
        raise ValueError('Pool identity or AI/human separation violated')
    for row in rows:
        doc = expected[(row['anonymousQueryId'], row['anonymousDocumentId'])][1]
        for judgment in row['judgments']:
            if judgment is None:
                continue
            grade, quote = judgment['grade'], judgment['quote']
            if type(grade) is not int or grade not in (0, 1, 2):
                raise ValueError('Invalid AI grade')
            if (grade > 0 and not quote.strip()) or (quote and quote not in doc['content']):
                raise ValueError('Invalid quote')
    def risk(row):
        gap = abs(row['judgments'][0]['grade'] - row['judgments'][1]['grade']) if all(row['judgments']) else 0
        low = any(j and j['confidence'] == 'LOW' for j in row['judgments'])
        return (-int(row['disagreement']), -int(row['unresolved']), -gap, -int(low),
                -int(row['uncertainty']), row['anonymousDocumentId'])
    priority = sorted(rows, key=risk)[:20]
    chosen = {r['anonymousDocumentId'] for r in priority}
    remaining = sorted((r for r in rows if r['anonymousDocumentId'] not in chosen), key=lambda r: r['anonymousDocumentId'])
    sampled = random.Random(20261018).sample(remaining, 10)
    items = []
    for index, row in enumerate(priority + sampled):
        q, d = expected[(row['anonymousQueryId'], row['anonymousDocumentId'])]
        judgments = row['judgments']
        reason = '\n'.join(f'第{n+1}轮：{j["reason"]}' if j else f'第{n+1}轮：未成功标注' for n,j in enumerate(judgments))
        confidence = '/'.join(j['confidence'] if j else 'UNRESOLVED' for j in judgments)
        items.append(dict(anonymousQueryId=q['anonymousQueryId'], anonymousDocumentId=d['anonymousDocumentId'],
                          query=q['query'], title=d['title'], content=d['content'], aiGrade=row['consensusGrade'],
                          pass1Grade=judgments[0]['grade'] if judgments[0] else None,
                          pass2Grade=judgments[1]['grade'] if judgments[1] else None,
                          confidence=confidence, reason=reason,
                          selectionReason=('随机抽样' if index >= 20 else
                                           '两轮分歧' if row['disagreement'] else
                                           '未成功标注' if row['unresolved'] else
                                           '低置信度' if row['uncertainty'] else '风险优先补足'),
                          humanGrade=None, humanNote=''))
    return dict(schemaVersion='ai-assisted-human-review-v1', status='AI_PRELABEL_NOT_HUMAN',
                poolId=pool['poolId'], blindContentSha256=merged['blindContentSha256'],
                selection=dict(totalPopulation=len(rows), riskCount=20, randomCount=10, seed=20261018,
                               randomPopulation=len(remaining),
                               limitation='20项定向风险复核；10项从剩余候选简单随机。不能代表全量准确率。'),
                summary=merged['summary'], items=items)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--pool', type=Path, required=True)
    p.add_argument('--merged', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    args = p.parse_args()
    raw = args.pool.read_bytes()
    pool, merged = json.loads(raw), json.loads(args.merged.read_text(encoding='utf-8'))
    if hashlib.sha256(raw).hexdigest() != merged['blindContentSha256']:
        raise ValueError('Blind pool bytes changed')
    result = select(pool, merged)
    write_new(args.out, result)
    csv_path = args.out.parent / 'full-ai-prelabels.csv'
    docs = {(q['anonymousQueryId'], d['anonymousDocumentId']):(q,d) for q in pool['queries'] for d in q['documents']}
    with csv_path.open('x', encoding='utf-8-sig', newline='') as out:
        writer = csv.writer(out)
        writer.writerow(['问题ID','文档ID','问题','标题','正文','AI一致等级','第一轮等级','第二轮等级','第一轮理由','第二轮理由','人工等级'])
        for row in merged['items']:
            q,d = docs[(row['anonymousQueryId'],row['anonymousDocumentId'])]
            a,b = row['judgments']
            values = [q['anonymousQueryId'],d['anonymousDocumentId'],q['query'],d['title'],d['content'],row['consensusGrade'],
                      a['grade'] if a else None,b['grade'] if b else None,a['reason'] if a else '',b['reason'] if b else '',None]
            # CSV opened in Excel must never execute corpus/model text as a formula.
            writer.writerow(["'"+v if isinstance(v,str) and v.lstrip().startswith(('=','+','-','@')) else v for v in values])
    print(json.dumps({'reviewItems':len(result['items']), 'population':result['selection']['totalPopulation']}))


if __name__ == '__main__':
    main()

"""Bind GPT-6 Luna primary labels to a separate model-assisted qrels schema."""
import argparse
import hashlib
import json
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--pool', type=Path, required=True)
    p.add_argument('--luna', type=Path, required=True)
    p.add_argument('--deepseek', type=Path, required=True)
    p.add_argument('--policy', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    pool = json.loads(a.pool.read_text(encoding='utf-8'))
    luna = json.loads(a.luna.read_text(encoding='utf-8'))
    deep = json.loads(a.deepseek.read_text(encoding='utf-8'))
    policy = json.loads(a.policy.read_text(encoding='utf-8'))
    blind_hash = hashlib.sha256(json.dumps(pool,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
    if pool['poolId'] != policy['scope']['poolId'] or luna['completedPairs'] != 1671 or len(deep['items']) != 1671:
        raise ValueError('Frozen pool, labels, or policy do not match')
    luna_rows = {(x['anonymousQueryId'],x['anonymousDocumentId']):x for x in luna['judgments']}
    deep_rows = {(x['anonymousQueryId'],x['anonymousDocumentId']):x for x in deep['items']}
    docs = {(q['anonymousQueryId'],d['anonymousDocumentId']):d for q in pool['queries'] for d in q['documents']}
    if set(luna_rows)!=set(docs) or set(deep_rows)!=set(docs):
        raise ValueError('Anonymous pair coverage mismatch')
    judgments=[]
    for key, row in luna_rows.items():
        source=deep_rows[key]; a1,a2=source['judgments']; grade=row['grade']; quote=row['supportingQuote']
        if row['status']!='AI_PRELABEL_NOT_HUMAN' or row['humanGrade'] is not None or type(grade) is not int or grade not in (0,1,2):
            raise ValueError('Invalid Luna model label')
        if grade>0 and (not quote or quote not in docs[key]['content']): raise ValueError('Luna quote does not match source')
        dsk_consensus=a1 is not None and a2 is not None and a1['grade']==a2['grade']
        if not dsk_consensus: audit='DEEPSEEK_UNRESOLVED'
        elif a1['grade']==grade: audit='CROSS_MODEL_AGREEMENT'
        else: audit='CROSS_MODEL_DISAGREEMENT'
        judgments.append({'anonymousQueryId':key[0],'anonymousDocumentId':key[1],'grade':grade,'reason':row['reason'],
                          'supportingQuote':quote,'confidence':row['confidence'],'status':'AI_PRELABEL_NOT_HUMAN','humanGrade':None,
                          'crossModelAudit':audit,'deepseekPass1':a1['grade'] if a1 else None,
                          'deepseekPass2':a2['grade'] if a2 else None})
    result={'schemaVersion':'retrieval-pool-model-annotations-v1','poolId':pool['poolId'],'blindContentSha256':blind_hash,
            'annotationStatus':'AI_PRELABEL_NOT_HUMAN','reviewer':{'type':'MODEL_ASSISTED_PRIMARY','model':'GPT-6 Luna'},
            'rubricNotes':'Primary labels are one GPT-6 Luna pass. DeepSeek two-pass results are a same-model audit, not independent raters. Cross-model disagreement flags do not overwrite Luna labels; no human labels are represented.',
            'sourceArtifacts':{'lunaSha256':sha(a.luna),'deepseekSha256':sha(a.deepseek),'policySha256':sha(a.policy)},
            'judgments':judgments}
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({'pairs':len(judgments),'deepseekAudit':{k:sum(x['crossModelAudit']==k for x in judgments) for k in ('CROSS_MODEL_AGREEMENT','CROSS_MODEL_DISAGREEMENT','DEEPSEEK_UNRESOLVED')}}))


if __name__=='__main__':
    main()

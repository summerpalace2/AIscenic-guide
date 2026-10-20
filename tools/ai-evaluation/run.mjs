import { createHash,randomUUID } from 'node:crypto';
import { spawn,execFileSync } from 'node:child_process';
import { readFile,writeFile,mkdir,readdir,copyFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join,relative } from 'node:path';
import { createWriteStream } from 'node:fs';
import { summarizeReports,validateReport } from './metrics.mjs';
import { evaluateAnswers } from './answer-evidence.mjs';

const root=fileURLToPath(new URL('../../',import.meta.url)), moduleRoot=join(root,'ai');
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const json=async path=>JSON.parse(await readFile(path,'utf8'));
async function files(directory) {
  const out=[];
  for(const e of await readdir(directory,{withFileTypes:true})) {
    const p=join(directory,e.name);
    if(e.isDirectory()) out.push(...await files(p));
    else if(e.isFile()) out.push(p);
  }
  return out;
}
async function snapshot() {
  const selected=[];
  for(const file of ['pom.xml','ai/pom.xml','ai/mvnw.cmd']) selected.push(join(root,file));
  for(const directory of ['ai/src/main','ai/src/test/java/com/ai/guide/evaluation','ai/src/test/resources/ai-evaluation','tools/ai-evaluation']) selected.push(...await files(join(root,directory)));
  const records=[];
  for(const path of selected.sort()) records.push({path:relative(root,path).replaceAll('\\','/'),sha256:hash(await readFile(path))});
  return {fingerprint:hash(JSON.stringify(records)),files:records};
}
async function main() {
  const args=process.argv.slice(2), i=args.indexOf('--run-id');
  if(!(args.length===0 || args.length===2 && args[0]==='--run-id')) throw new Error('Usage: node tools/ai-evaluation/run.mjs [--run-id SAFE_ID]');
  const runId=i<0?`${new Date().toISOString().slice(0,10)}-${randomUUID().slice(0,8)}`:args[i+1];
  if(!runId || !/^[a-zA-Z0-9_-]{1,80}$/.test(runId)) throw new Error('run-id must be 1..80 letters, digits, underscores or hyphens');
  const output=join(moduleRoot,'target','ai-evaluation','runs',runId);
  await mkdir(join(moduleRoot,'target','ai-evaluation','runs'),{recursive:true});
  await mkdir(output); // Existing run evidence is never overwritten.
  const before=await snapshot();
  let head='UNAVAILABLE';try{head=execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim();}catch{}
  await writeFile(join(output,'source-manifest-before.json'),JSON.stringify({runId,head,...before},null,2));
  const log=createWriteStream(join(output,'maven.log'));
  const mavenArgs=['test','-Dtest=OfflineAiEvaluationTest,RetrievalBaselineEvaluationTest','-Dai.evaluation.enabled=true',`-Dai.evaluation.runId=${runId}`];
  const child=process.platform==='win32'
    ?spawn('powershell.exe',['-NoProfile','-NonInteractive','-Command',`& '.\\mvnw.cmd' ${mavenArgs.map(a=>`'${a}'`).join(' ')}; exit $LASTEXITCODE`],{cwd:moduleRoot,stdio:['ignore','pipe','pipe']})
    :spawn('./mvnw',mavenArgs,{cwd:moduleRoot,stdio:['ignore','pipe','pipe']});
  child.stdout.on('data',d=>log.write(d));child.stderr.on('data',d=>log.write(d));
  const exit=await new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',resolve);});
  await new Promise(resolve=>log.end(resolve));
  await writeFile(join(output,'execution.json'),JSON.stringify({runId,mavenExitCode:exit,externalSuppliers:false,productionDataWritten:false},null,2));
  if(exit!==0) {
    for(const suite of ['intent','planning','retrieval']) {
      try {
        const raw=join(moduleRoot,'target','ai-evaluation',`${suite}-results.json`);
        if((await json(raw)).runId===runId) await copyFile(raw,join(output,`partial-raw-${suite}-results.json`));
      } catch { /* No report was produced; never import a prior run as current evidence. */ }
    }
    throw new Error(`Evaluation harness failed (${exit}); original log retained at ${output}`);
  }
  const after=await snapshot();
  await writeFile(join(output,'source-manifest-after.json'),JSON.stringify(after,null,2));
  if(before.fingerprint!==after.fingerprint) throw new Error('Sources or gold data changed while measuring; results not published');
  const reports={};
  for(const suite of ['intent','planning','retrieval']) {
    const name=`${suite}-results.json`, raw=join(moduleRoot,'target','ai-evaluation',name);
    const report=await json(raw), datasetPath=join(moduleRoot,'src','test','resources','ai-evaluation',`${suite}-cases.json`);
    if(report.runId!==runId) throw new Error('Stale report run ID');
    const data=await readFile(datasetPath);
    validateReport(report,JSON.parse(data.toString('utf8')),hash(data));
    if(suite==='retrieval' && report.corpusSha256!==hash(await readFile(join(moduleRoot,'src','test','resources','ai-evaluation','retrieval-corpus.json')))) throw new Error('Corpus fingerprint mismatch');
    for(const c of report.cases)c.traceId??=`${runId}:${c.id}`;
    reports[suite]=report;await copyFile(raw,join(output,`raw-${name}`));
    await writeFile(join(output,name),JSON.stringify(report,null,2));
  }
  const summary={...summarizeReports(reports),head,sourceFingerprint:before.fingerprint,generatedAt:new Date().toISOString()};
  const fixture=await json(join(moduleRoot,'src','test','resources','ai-evaluation','answer-evidence-cases.json'));
  const evidence=evaluateAnswers(fixture);
  await writeFile(join(output,'answer-evidence-contract-results.json'),JSON.stringify(evidence,null,2));
  summary.answerEvidence={contractFixtureCount:fixture.cases.length,purpose:fixture.purpose,realModelFaithfulness:{status:'NOT_SCORED',value:null}};
  await writeFile(join(output,'summary.json'),JSON.stringify(summary,null,2));
  console.log(JSON.stringify({runId,output,harness:'PASS',intent:summary.intent.exactAnnotatedFields,planning:summary.planning.annotatedPlanningSuccess,retrieval:summary.retrieval.documentRecallAt5,realModelFaithfulness:'NOT_SCORED'},null,2));
}
main().catch(error=>{console.error(error.message);process.exitCode=1;});

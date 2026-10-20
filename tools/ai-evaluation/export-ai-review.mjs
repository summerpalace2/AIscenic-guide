import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

const sha = value => createHash('sha256').update(value).digest('hex');
const grade = value => value === null || [0,1,2].includes(value);
const literal = value => typeof value === 'string' && /^[=+\-@]/.test(value) ? "'"+value : value;
function selectionClass(reason) {
  const s=String(reason).toUpperCase();
  if (/CONFLICT|DISAGREE|冲突|分歧/.test(s)) return 'CONFLICT';
  if (/RANDOM|随机/.test(s)) return 'RANDOM';
  if (/LOW[_ -]CONFIDENCE|低置信/.test(s)) return 'LOW_CONFIDENCE';
  return 'OTHER';
}

export function validateReview(input,pool,poolBytes=Buffer.from(JSON.stringify(pool))) {
  assert.equal(typeof input.schemaVersion,'string');
  assert.equal(input.poolId,pool.poolId,'review pool identity mismatch');
  // AI-review input binds the original pool file bytes. This differs from the
  // canonical JSON body hash used by the earlier blind-annotation contract.
  assert.equal(input.blindContentSha256,sha(poolBytes),'review pool file hash mismatch');
  assert.equal(input.items?.length,30,'review must contain exactly 30 selected pairs');
  const source = new Map(pool.queries.flatMap(q=>q.documents.map(d=>[q.anonymousQueryId+'/'+d.anonymousDocumentId,{...d,query:q.query}]))),seen=new Set();
  for(const item of input.items) {
    const key=item.anonymousQueryId+'/'+item.anonymousDocumentId,d=source.get(key);
    assert(d&&!seen.has(key),'unknown or duplicate selected pair');seen.add(key);
    for(const prop of ['query','title','content'])assert.equal(item[prop],d[prop],'selected text mismatch: '+prop);
    for(const prop of ['aiGrade','pass1Grade','pass2Grade'])assert(grade(item[prop]),'invalid '+prop);
    assert.equal(item.humanGrade,null,'initial human grade must be empty');
    assert.equal(item.humanNote??'','','initial human note must be empty');
    for(const prop of ['reason','selectionReason'])assert.equal(typeof item[prop],'string');
    assert(item.content.length<=32767,'document exceeds Excel cell length; do not truncate');
  }
  assert.equal(input.selection.totalPopulation,source.size,'selection population mismatch');
  assert.equal(input.selection.riskCount+input.selection.randomCount,input.items.length,'selection counts mismatch');
  assert.equal(input.items.filter(x=>selectionClass(x.selectionReason)==='RANDOM').length,input.selection.randomCount,'random selection count mismatch');
  return {total:source.size,selected:input.items.length};
}

export async function exportReview({inputPath,poolPath,outputDir,verificationDir,dependencyModules,discover=false}) {
  const [inputBytes,poolBytes]=await Promise.all([fs.readFile(inputPath),fs.readFile(poolPath)]);
  const input=JSON.parse(inputBytes.toString('utf8').replace(/^\uFEFF/,'')),pool=JSON.parse(poolBytes.toString('utf8').replace(/^\uFEFF/,''));
  const counts=validateReview(input,pool,poolBytes);
  const canonicalBodySha256=sha(JSON.stringify(pool));
  const temp=await fs.mkdtemp(path.join(os.tmpdir(),'ai-review-artifact-'));
  await fs.symlink(dependencyModules,path.join(temp,'node_modules'),'junction');
  const require=createRequire(path.join(temp,'entry.mjs'));
  const {Workbook,SpreadsheetFile}=await import(pathToFileURL(require.resolve('@oai/artifact-tool')).href);
  const wb=Workbook.create();
  if(discover) {console.log(wb.help('range.columnHidden',{include:'index,examples,notes',maxChars:3000}).ndjson);return;}
  await fs.mkdir(outputDir,{recursive:false});
  await fs.mkdir(verificationDir,{recursive:true});
  const summary=wb.worksheets.add('说明摘要'),detail=wb.worksheets.add('复核30条');
  for(const sheet of [summary,detail]){sheet.showGridLines=false;sheet.getRange(sheet===summary?'A1:H25':'A1:T35').format.font={name:'Arial',size:11,color:'#1F2937'};}
  summary.tabColor='#233B63';detail.tabColor='#5579A9';
  summary.getRange('A2').values=[['AI 辅助复核']];summary.getRange('A2').format.font={size:16,bold:true};
  summary.getRange('A4:B4').values=[['项目','数量']];
  summary.getRange('A5:B5').values=[['候选总数',counts.total]];
  summary.getRange('A6:A11').values=[['本次选取'],['两轮分歧选取'],['随机选取'],['已填人工等级'],['尚待复核'],['低置信选取']];
  summary.getRange('B6:B11').formulas=[
    ["=COUNT('复核30条'!A6:A35)"],["=COUNTIFS('复核30条'!S6:S35,\"CONFLICT\")"],
    ["=COUNTIFS('复核30条'!S6:S35,\"RANDOM\")"],["=COUNT('复核30条'!B6:B35)"],['=B6-B9'],
    ["=COUNTIFS('复核30条'!S6:S35,\"LOW_CONFIDENCE\")"],
  ];
  summary.getRange('A12').values=[['风险优先选取']];summary.getRange('B12').formulas=[['=B6-B8']];
  summary.getRange('A13:B13').values=[['复核者姓名',null]];summary.getRange('B13').format.fill='#FFF2CC';
  summary.getRange('A15').values=[['判定标准']];
  const notes=[
    '0 不相关：没有回答所问属性。只有名称或主题相关也判 0。',
    '1 部分相关：明确回答问题的一部分，或仅给出所问属性的部分信息。',
    '2 直接回答：明确回答所问属性，并保留适用条件。空白表示未判断，不是 0。',
    '这是 AI 辅助复核，AI 意见可见。选取的 30 条不代表独立盲标或总体准确率。',
    '在“复核30条”的浅黄色列填写人工等级和备注，保存此文件后交回。',
    '资料正文完整保留。长文本可选中单元格在编辑栏继续阅读。',
    '来源：本轮两次 AI 辅助意见及原匿名候选池。AI 自评置信度不是校准准确率。',
  ];
  summary.getRange('A16:A22').values=notes.map(x=>[x]);summary.getRange('A16:H22').format.wrapText=true;summary.getRange('A16:A22').format.rowHeight=34;
  summary.getRange('A4:B4').format={fill:'#233B63',font:{bold:true,color:'#FFFFFF'}};
  summary.getRange('A4:A13').format.columnWidth=24;summary.getRange('B4:B13').format.columnWidth=18;summary.getRange('C1:H25').format.columnWidth=10;
  // Notes span otherwise empty presentation cells without merging the working table.
  for(let row=16;row<=22;row++)summary.mergeCells(`A${row}:H${row}`);
  summary.getRange('B5:B11').setNumberFormat('#,##0');

  detail.getRange('A2').values=[['少量人工复核']];detail.getRange('A2').format.font={size:16,bold:true};
  detail.getRange('A3').values=[['先读问题与资料，再参考 AI 意见。浅黄色单元格为人工填写区。']];
  const headers=['序号','人工等级','人工备注','问题','资料标题','资料正文','AI 建议','AI 第1轮','AI 第2轮','模型自评置信度','AI 理由','选取原因','匿名问题 ID','匿名文档 ID','poolId','blindContentSha256','inputSha256','schemaVersion','selectionClass','canonicalBodySha256'];
  detail.getRange('A5:T5').values=[headers];
  const rows=input.items.map((x,i)=>[i+1,null,null,x.query,x.title,x.content,x.aiGrade,x.pass1Grade,x.pass2Grade,x.confidence??null,x.reason,x.selectionReason,x.anonymousQueryId,x.anonymousDocumentId,input.poolId,input.blindContentSha256,sha(inputBytes),input.schemaVersion,selectionClass(x.selectionReason),canonicalBodySha256].map(literal));
  detail.getRange('A6:T35').values=rows;detail.getRange('A5:L5').format={fill:'#233B63',font:{bold:true,color:'#FFFFFF'},wrapText:true,horizontalAlignment:'center',verticalAlignment:'center',rowHeight:34};
  detail.getRange('A6:L35').format.verticalAlignment='top';detail.getRange('B6:C35').format.fill='#FFF2CC';
  detail.getRange('B6:B35').dataValidation={rule:{type:'list',values:['0','1','2']}};
  detail.getRange('B6:B35').setNumberFormat('0');detail.getRange('G6:I35').setNumberFormat('0');
  const widths=[6,11,28,34,27,76,11,10,10,18,44,24];
  widths.forEach((width,i)=>detail.getRangeByIndexes(0,i,35,1).format.columnWidth=width);
  detail.getRange('C6:L35').format.wrapText=true;
  for(let i=0;i<rows.length;i++){const lines=Math.max(Math.ceil(input.items[i].content.length/38),Math.ceil(input.items[i].reason.length/22),5);detail.getRange(`A${i+6}:L${i+6}`).format.rowHeight=Math.min(400,Math.max(110,lines*15));}
  detail.freezePanes.freezeRows(5);detail.freezePanes.freezeColumns(3);
  // The public API documents column sizing, but not a columnHidden setter.
  // Collapse technical metadata without relying on an undocumented property.
  detail.getRange('M1:T35').format.columnWidth=0;
  wb.recalculate();
  assert.equal(summary.getRange('B6').values[0][0],30,'selected count formula failed');
  assert.equal(summary.getRange('B9').values[0][0],0,'blank human grades counted as reviewed');
  assert(detail.getRange('B6:B35').values.every(row=>row[0]===null),'human grades must remain genuine blanks');
  // Exercise valid zero and partial input without retaining trial judgments.
  detail.getRange('B6').values=[[0]];assert.equal(summary.getRange('B9').values[0][0],1,'zero human grade not counted');detail.getRange('B6').clear({applyTo:'contents'});
  wb.recalculate();assert.equal(summary.getRange('B9').values[0][0],0);
  const checks=await wb.inspect({kind:'table',range:'说明摘要!A4:B13',include:'values,formulas',tableMaxRows:10,tableMaxCols:2,maxChars:4000});
  const errors=await wb.inspect({kind:'match',searchTerm:'#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!',options:{useRegex:true,maxResults:50},maxChars:3000});
  await fs.writeFile(path.join(verificationDir,'inspection.json'),JSON.stringify({summary:checks.ndjson,formulaScan:errors.ndjson},null,2));
  for(const [sheetName,range,file] of [['说明摘要','A1:H23','summary.png'],['复核30条','A1:G7','review.png']]){const preview=await wb.render({sheetName,range,scale:1,format:'png'});await fs.writeFile(path.join(verificationDir,file),new Uint8Array(await preview.arrayBuffer()));}
  const outputPath=path.join(outputDir,'AI辅助复核30条.xlsx');const output=await SpreadsheetFile.exportXlsx(wb);await output.save(outputPath);
  try { await fs.rename(outputPath+'.inspect.ndjson',path.join(verificationDir,'export-inspect.ndjson')); }
  catch(error) { if(error.code!=='ENOENT')throw error; }
  await fs.writeFile(path.join(verificationDir,'export-receipt.json'),JSON.stringify({schemaVersion:'ai-assisted-review-xlsx-export-v1',inputSha256:sha(inputBytes),poolId:input.poolId,blindContentSha256:input.blindContentSha256,blindContentHashMeaning:'SHA256 of original blind-pool.json file bytes in AI-review input',canonicalBodySha256,xlsxSha256:sha(await fs.readFile(outputPath)),outputPath,counts,humanReviewed:0,classificationCounts:input.items.reduce((r,i)=>{const k=selectionClass(i.selectionReason);r[k]=(r[k]??0)+1;return r;},{})},null,2));
  console.log(JSON.stringify({outputPath,counts,humanReviewed:0}));
  return outputPath;
}

async function main(){
  const {values:v}=parseArgs({options:{input:{type:'string'},pool:{type:'string'},'output-dir':{type:'string'},'verification-dir':{type:'string'},'dependency-modules':{type:'string'},discover:{type:'boolean',default:false}}});
  for(const name of ['input','pool','output-dir','verification-dir','dependency-modules'])assert(v[name],'--'+name+' required');
  await exportReview({inputPath:v.input,poolPath:v.pool,outputDir:v['output-dir'],verificationDir:v['verification-dir'],dependencyModules:v['dependency-modules'],discover:v.discover});
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href)main().catch(e=>{console.error(e.stack);process.exitCode=1;});

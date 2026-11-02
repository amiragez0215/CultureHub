import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {label,displayResult,errorMessage,notificationText} from './src/locale.js';
for (const code of ['HERITAGE','LECTURE','ART','CULTURE','ALL','Family','WAITING','OFFERED','RESERVED','CHECKED_IN','CANCELLED','EXPIRED','PUBLISHED','DRAFT','OPEN','CLOSED']) {
  assert.match(label(code),/[\u3400-\u9fff]/,`Missing Chinese label: ${code}`);
}
assert.equal(errorMessage('SESSION_FULL',409),'本场次名额已满，可以选择加入候补');
assert.match(errorMessage('UNRECOGNIZED_ERROR',500),/[\u3400-\u9fff]/);
const result=displayResult({summary:{checkinRate:0.5},status:'CHECKED_IN',checked_at:null});
assert.match(result,/签到率/);assert.match(result,/50\.0%/);assert.match(result,/已签到/);
assert.doesNotMatch(result,/checkinRate|CHECKED_IN|checked_at/);
assert.match(notificationText(JSON.stringify({data:{title:'剪纸体验',confirmDeadline:'2026-11-01'}})),/确认截止/);
const source=readFileSync('src/main.js','utf8');
const template=readFileSync('src/app-template.html','utf8');
assert.doesNotMatch(template,/PUBLIC CULTURE|<option>HERITAGE|\{\{k\}\}|\{\{r\.status\}\}|JSON\.stringify\(result/);
assert.match(template,/公共文化 · 人人共享/);
console.log('Chinese labels, errors, notifications and report formatting passed');

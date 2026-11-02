import assert from 'node:assert/strict';
import {streamGuide} from './src/sse.js';
const original=globalThis.fetch;
function response(text,size=1){const bytes=new TextEncoder().encode(text);return{ok:true,headers:new Headers({'content-type':'text/event-stream;charset=UTF-8'}),body:new ReadableStream({start(c){for(let i=0;i<bytes.length;i+=size)c.enqueue(bytes.slice(i,i+size));c.close();}})};}
try{
  const events=[];let request;
  globalThis.fetch=async(url,options)=>{request=options;return response(': keepalive\r\nevent: delta\r\ndata: {"text":\r\ndata: "中文逐字与表情🎨"}\r\n\r\nevent: done\ndata: {"phase":"MESSAGE"}\n\n');};
  await streamGuide('/test','jwt-value','问题',new AbortController().signal,(...value)=>events.push(value));
  assert.equal(events[0][1].text,'中文逐字与表情🎨');assert.equal(events[1][0],'done');assert.equal(request.headers.Authorization,'Bearer jwt-value');
  globalThis.fetch=async()=>response('event: delta\ndata: {"text":"未完成"}\n\n');
  await assert.rejects(streamGuide('/test','t','q',null,()=>{}),/连接已中断/);
  globalThis.fetch=async()=>response('event: delta\ndata: broken\n\n');
  await assert.rejects(streamGuide('/test','t','q',null,()=>{}),SyntaxError);
  globalThis.fetch=async()=>({ok:false,status:403,text:async()=>JSON.stringify({code:'FORBIDDEN',message:'无权访问'})});
  await assert.rejects(streamGuide('/test','t','q',null,()=>{}),e=>e.status===403&&e.code==='FORBIDDEN');
  globalThis.fetch=async()=>response('event: error\ndata: {"message":"模型暂不可用"}\n\n');
  await assert.rejects(streamGuide('/test','t','q',null,(name,value)=>{if(name==='error')throw new Error(value.message);}),/模型暂不可用/);
  const abort=new AbortController();abort.abort();globalThis.fetch=async()=>response('');
  await assert.rejects(streamGuide('/test','t','q',abort.signal,()=>{}),e=>e.name==='AbortError');
  console.log('PASS: SSE UTF-8 byte boundaries, CRLF/multiline, JWT, malformed/incomplete/error response, abort');
}finally{globalThis.fetch=original;}

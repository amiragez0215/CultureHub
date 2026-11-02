/** POST + JWT SSE. Decode bytes incrementally; never assume one network chunk is one event. */
export async function streamGuide(url, token, message, signal, onEvent) {
  const response = await fetch(url, {method:"POST", headers:{"Content-Type":"application/json",Accept:"text/event-stream",Authorization:"Bearer "+token},body:JSON.stringify({message}),signal});
  if(!response.ok){let error;try{error=JSON.parse(await response.text());}catch{error={};}throw Object.assign(new Error(error.message||"导览暂不可用"),{code:error.code,status:response.status});}
  if(!response.headers?.get("content-type")?.includes("text/event-stream")||!response.body)throw new Error("导览响应格式异常，请重试");
  const reader=response.body.getReader(),decoder=new TextDecoder();
  let buffer="",event="message",data=[],finished=false;
  function line(value){
    if(value===""){
      if(data.length){const payload=JSON.parse(data.join("\n"));onEvent(event,payload);if(event==="done")finished=true;}
      event="message";data=[];return;
    }
    if(value.startsWith(":"))return;
    const separator=value.indexOf(":"),field=separator<0?value:value.slice(0,separator);
    let content=separator<0?"":value.slice(separator+1);if(content.startsWith(" "))content=content.slice(1);
    if(field==="event")event=content;if(field==="data")data.push(content);
  }
  function drain(final=false){
    let start=0;
    for(let i=0;i<buffer.length;i++)if(buffer[i]==="\n"||buffer[i]==="\r"){
      if(buffer[i]==="\r"&&i===buffer.length-1&&!final)break;
      line(buffer.slice(start,i));if(buffer[i]==="\r"&&buffer[i+1]==="\n")i++;start=i+1;
    }
    buffer=buffer.slice(start);
    if(buffer.length>256000)throw new Error("导览数据过长");
  }
  const abort=()=>reader.cancel().catch(()=>{});
  signal?.addEventListener("abort",abort,{once:true});
  try{
    while(true){if(signal?.aborted)throw new DOMException("已停止生成","AbortError");const {value,done}=await reader.read();if(done)break;buffer+=decoder.decode(value,{stream:true});drain();}
    buffer+=decoder.decode();drain(true);
    if(signal?.aborted)throw new DOMException("已停止生成","AbortError");
    if(!finished)throw new Error("导览连接已中断，请重试；未提交预约");
  }finally{signal?.removeEventListener("abort",abort);await reader.cancel().catch(()=>{});reader.releaseLock();}
}

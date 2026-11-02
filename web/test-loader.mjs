import {readFile} from 'node:fs/promises';
export async function load(url,context,nextLoad){
 if(url.endsWith('.css'))return{format:'module',source:'export default {}',shortCircuit:true};
 if(url.endsWith('?raw'))return{format:'module',source:'export default '+JSON.stringify(await readFile(new URL(url.slice(0,-4)),'utf8')),shortCircuit:true};
 return nextLoad(url,context);
}

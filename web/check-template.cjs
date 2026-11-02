const fs=require('fs');
const {compile}=require('@vue/compiler-dom');
const source=fs.readFileSync('src/main.js','utf8');
const template=fs.readFileSync('src/app-template.html','utf8');
const result=compile(template,{onError(error){throw error;}});
new Function('Vue',result.code);
console.log('Vue template compilation passed');

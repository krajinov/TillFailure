// Retained independent supervisor only: no import/reset/erase or product Functions.
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile,access} from 'node:fs/promises';
import {createServer} from 'node:net';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const repo=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../..');
if(!process.version.startsWith('v22.'))throw Error('Node 22 required');
const run=path.join(repo,'.firebase/m7-pr2a');await mkdir(run,{recursive:true});
try{await access(path.join(run,'supervisor-started.json'));throw Error('Occupied supervisor history: use explicit audit, never restart empty');}catch(e){if(e.code!=='ENOENT')throw e;}
for(const port of [50799,50780,50719,50700,50720,50731])await new Promise((ok,no)=>{const s=createServer();s.once('error',no);s.listen(port,'127.0.0.1',()=>s.close(ok));});
for(const name of ['firestore.rules','firestore.indexes.json','storage.rules'])await writeFile(path.join(run,name),await readFile(path.join(repo,'firebase',name)));
await writeFile(path.join(run,'firebase.json'),JSON.stringify({firestore:{rules:'firestore.rules',indexes:'firestore.indexes.json'},storage:{rules:'storage.rules'},emulators:{auth:{host:'127.0.0.1',port:50799},firestore:{host:'127.0.0.1',port:50780},storage:{host:'127.0.0.1',port:50719},hub:{host:'127.0.0.1',port:50700},logging:{host:'127.0.0.1',port:50720},ui:{enabled:false},singleProjectMode:true}}));
await writeFile(path.join(run,'supervisor-started.json'),JSON.stringify({startedAt:new Date().toISOString(),pid:process.pid,project:'demo-tillfailure-m3',import:false,retained:true}));
const child=spawn(process.execPath,[path.join(repo,'firebase/functions/node_modules/firebase-tools/lib/bin/firebase.js'),'emulators:start','--project','demo-tillfailure-m3','--config',path.join(run,'firebase.json'),'--only','auth,firestore,storage'],{cwd:repo,env:{...process.env,PATH:path.dirname(process.execPath)+path.delimiter+process.env.PATH},stdio:'inherit'});
process.on('SIGINT',()=>child.kill('SIGINT'));process.on('SIGTERM',()=>child.kill('SIGINT'));child.on('exit',code=>process.exit(code??1));

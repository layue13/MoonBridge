// Run after permission-services.cjs start and compiling PermissionNetworkAcceptance.
const fs = require('node:fs');
const path = require('node:path');
const {spawn,spawnSync} = require('node:child_process');
const root = path.resolve(__dirname,'..');
const mode = process.argv[2];
if (!['sql','redis'].includes(mode)) throw new Error('Usage: node smoke/permission-network.cjs sql|redis [outage]');
const outage = process.argv[3] === 'outage';
const log = fs.createWriteStream(path.join(root,'build',`permission-network-${mode}${outage?'-outage':''}.log`));
const args = ['-cp',`${path.join(root,'proxy-core/build/install/moonbridge/lib/*')}${path.delimiter}${path.join(root,'build/permission-network-classes')}`,
 'PermissionNetworkAcceptance','proxy-core/build/install/moonbridge',`build/permission-services/${mode==='sql'?'mariadb':'redis'}-base.yml`,'build/permission-network',mode];
if (outage) args.push('outage');
const child = spawn('java',args,{cwd:root,windowsHide:true,stdio:['pipe','pipe','pipe']});
let pending = '';
child.stderr.on('data',chunk=>log.write(chunk));
child.stdout.on('data',chunk=>{
 log.write(chunk); pending+=chunk.toString();
 let end;
 while ((end=pending.indexOf('\n'))>=0) {
  const line=pending.slice(0,end).trim(); pending=pending.slice(end+1);
  const request=/^REQUEST_(STOP|START)_(MARIADB|REDIS)$/.exec(line);
  if (request) {
   const [,verb,kind]=request;
   console.log(line);
   const action=`${verb==='STOP'?'pause':'resume'}-${kind.toLowerCase()}`;
   const result=spawnSync(process.execPath,[path.join(__dirname,'permission-services.cjs'),action],{encoding:'utf8',windowsHide:true});
   if (result.status!==0) { console.error(result.stderr); child.kill(); process.exitCode=1; return; }
   if (verb==='START' && kind==='REDIS') {
    const subscribers=spawnSync(process.execPath,[path.join(__dirname,'permission-services.cjs'),'wait-redis-subscribers'],{encoding:'utf8',windowsHide:true});
    if (subscribers.status!==0) { console.error(subscribers.stderr);child.kill();process.exitCode=1;return; }
    console.log(subscribers.stdout.trim());
   }
   child.stdin.write(`${kind}_${verb==='STOP'?'STOPPED':'STARTED'}\n`);
  } else if (line.includes('ACCEPTANCE_PASS')) console.log(line);
 }
});
const deadline=setTimeout(()=>{console.error('Network acceptance exceeded 10 minutes');child.kill();process.exitCode=1;},600000);
child.on('error',error=>{console.error(error.message);process.exitCode=1;});
child.on('close',(code,signal)=>{clearTimeout(deadline);log.end();console.log(`Network acceptance exit=${code} signal=${signal || 'none'}`);process.exitCode=process.exitCode||(code===0?0:1);});

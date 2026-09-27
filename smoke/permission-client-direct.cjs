// Launch the real cached Forge 1.7.10 client without reading launcher account data.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {spawn,spawnSync}=require('node:child_process');
const profile=path.resolve(process.argv[2]||'');
const port=Number(process.argv[3]);
const cache=process.argv[4]||path.join(process.env.APPDATA,'PrismLauncher');
const java=process.argv[5]||'C:/Program Files/Zulu/zulu-8/bin/javaw.exe';
if (!path.basename(profile).startsWith('MoonBridgePermission-') || !fs.existsSync(path.join(profile,'mmc-pack.json')) || !Number.isInteger(port) || port<1 || port>65535)
 throw Error('Usage: node smoke/permission-client-direct.cjs <temporary-profile> <proxy-port> [Prism-cache-root] [javaw8]');
const game=path.join(profile,'minecraft'),natives=path.join(profile,'natives');
fs.mkdirSync(game,{recursive:true});fs.mkdirSync(natives,{recursive:true});
const metas=['net.minecraft/1.7.10.json','org.lwjgl/2.9.4-nightly-20150209.json','net.minecraftforge/10.13.4.1614.json'].map(f=>JSON.parse(fs.readFileSync(path.join(cache,'meta',f),'utf8')));
function artifact(name,classifier,expectedSha) {
 const [group,id,version,originalClassifier]=name.split(':');
 const suffix=classifier||originalClassifier;
 const file=path.join(cache,'libraries',group.replaceAll('.','/'),id,version,`${id}-${version}${suffix?'-'+suffix:''}.jar`);
 if (!fs.existsSync(file)) throw Error(`Cached library missing: ${name}${classifier?':'+classifier:''}`);
 if (expectedSha && crypto.createHash('sha1').update(fs.readFileSync(file)).digest('hex')!==expectedSha) throw Error(`Cached library checksum mismatch: ${name}`);
 return file;
}
const libraries=new Map();
for(const meta of metas) for(const lib of meta.libraries) {
 if(lib.rules){let allowed=false;for(const rule of lib.rules)if(!rule.os||rule.os.name==='windows')allowed=rule.action==='allow';if(!allowed)continue;}
 if(lib.natives){
  const classifier=lib.natives.windows?.replace('${arch}','64'); if(!classifier)continue;
  const file=artifact(lib.name,classifier,lib.downloads?.classifiers?.[classifier]?.sha1);
  const result=spawnSync('jar',['-xf',file],{cwd:natives,encoding:'utf8',windowsHide:true});
  if(result.status!==0)throw Error(`Could not extract native library ${lib.name}: ${result.stderr}`);
 }else libraries.set(lib.name.split(':').slice(0,2).join(':'),lib);
}
for(const [key,lib] of libraries) libraries.set(key,artifact(lib.name,null,lib.downloads?.artifact?.sha1));
libraries.set('minecraft',artifact(metas[0].mainJar.name,null,metas[0].mainJar.downloads.artifact.sha1));
const identity=crypto.createHash('md5').update('OfflinePlayer:PrismSmoke').digest();identity[6]=(identity[6]&15)|48;identity[8]=(identity[8]&63)|128;
const args=['-Xms512m','-Xmx1024m',`-Djava.library.path=${natives}`,'-cp',[...libraries.values()].join(path.delimiter),metas[2].mainClass,
 '--tweakClass',metas[2]['+tweakers'][0],'--username','PrismSmoke','--version','1.7.10','--gameDir',game,'--assetsDir',path.join(cache,'assets'),
 '--assetIndex',metas[0].assetIndex.id,'--uuid',identity.toString('hex'),'--accessToken','0','--userProperties','{}','--userType','legacy',
 '--server','127.0.0.1','--port',String(port),'--width','854','--height','480'];
const out=fs.openSync(path.join(profile,'direct-client.stdout.log'),'w'),err=fs.openSync(path.join(profile,'direct-client.stderr.log'),'w');
const child=spawn(java,args,{cwd:game,detached:true,windowsHide:true,stdio:['ignore',out,err]});
child.on('error',error=>{console.error(error.message);process.exitCode=1;});
child.on('spawn',()=>{fs.closeSync(out);fs.closeSync(err);console.log(JSON.stringify({pid:child.pid,profile,libraryCount:libraries.size,accountDataRead:false}));child.unref();});

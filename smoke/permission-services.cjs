// Isolated, loopback-only services for PermissionNetworkAcceptance. Secrets stay in ignored build/.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const net = require('node:net');
const {spawnSync} = require('node:child_process');
const root = path.resolve(__dirname, '..');
const dir = path.join(root, 'build', 'permission-services');
const stateFile = path.join(dir, 'state.json');
const label = 'moonbridge.permission-acceptance';
let createdThisRun = false;
function docker(args) {
  const r = spawnSync('docker', args, {encoding:'utf8', windowsHide:true});
  if (r.status !== 0) throw new Error(`docker ${args[0]} failed: ${r.stderr}`);
  return r.stdout.trim();
}
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function freePort() {
  const server = net.createServer();
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
  const port = server.address().port;
  await new Promise(resolve=>server.close(resolve));
  return port;
}
function readState() { return JSON.parse(fs.readFileSync(stateFile, 'utf8')); }
function owned(id, run) {
  if (docker(['inspect','--format',`{{index .Config.Labels "${label}"}}`,id]) !== run)
    throw new Error('Container ownership mismatch');
}
function clearTestSecrets() {
  for (const file of ['mysql.env','redis.conf']) {
    const target=path.join(dir,file); if(fs.existsSync(target)) fs.unlinkSync(target);
  }
  const redact=file=>{if(fs.existsSync(file))fs.writeFileSync(file,fs.readFileSync(file,'utf8').replace(/^(\s*password:).*$/gm,'$1 "<removed after acceptance>"'));};
  for (const file of ['mysql-base.yml','redis-base.yml']) redact(path.join(dir,file));
  const evidence=path.join(root,'build','permission-network');
  if (fs.existsSync(evidence)) for (const entry of fs.readdirSync(evidence,{withFileTypes:true})) {
    if (!entry.isDirectory() || !/^permission-network-(sql|redis)-\d+$/.test(entry.name)) continue;
    for (const instance of ['instance-a','instance-b'])
      redact(path.join(evidence,entry.name,instance,'plugins','data','dev.moonbridge.luckperms.LuckPermsMoonBridgePlugin','config.yml'));
  }
}
function cleanup(state) {
  for (const kind of ['redis','mysql']) if (state[kind]) {owned(state[kind],state.run);docker(['rm','-f','-v',state[kind]]);}
  clearTestSecrets();
  fs.renameSync(stateFile,path.join(dir,`completed-${state.run}.json`));
}
async function ready(id, kind) {
  const command = kind === 'mysql'
    ? 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -e "SELECT 1"'
    : 'REDISCLI_AUTH=$(sed -n "s/^requirepass //p" /usr/local/etc/redis/redis.conf) redis-cli ping';
  for (let i=0; i<90; i++) {
    const r = spawnSync('docker',['exec',id,'sh','-c',command],{encoding:'utf8',windowsHide:true});
    if (r.status===0 && /^(1|PONG)$/.test(r.stdout.trim())) return;
    await delay(1000);
  }
  throw new Error(`${kind} readiness timeout`);
}
async function main() {
  const action = process.argv[2];
  fs.mkdirSync(dir,{recursive:true});
  if (action === 'start') {
    if (fs.existsSync(stateFile)) throw new Error('Existing fixture state; clean it before starting another run');
    const run = crypto.randomBytes(6).toString('hex');
    const mysqlPassword = crypto.randomBytes(24).toString('hex');
    const redisPassword = crypto.randomBytes(24).toString('hex');
    const env = path.join(dir,'mysql.env');
    const redisConfig = path.join(dir,'redis.conf');
    fs.writeFileSync(env,`MYSQL_ROOT_PASSWORD=${crypto.randomBytes(24).toString('hex')}\nMYSQL_DATABASE=moonbridge_permissions\nMYSQL_USER=moonbridge\nMYSQL_PASSWORD=${mysqlPassword}\n`);
    fs.writeFileSync(redisConfig,`bind 0.0.0.0\nprotected-mode yes\nrequirepass ${redisPassword}\nappendonly no\n`);
    const state = {run};
    const save = () => fs.writeFileSync(stateFile,JSON.stringify(state,null,2));
    save(); createdThisRun = true;
    // Bind an explicit free port: Docker can change an ephemeral mapping across stop/start.
    state.mysqlPort = await freePort();
    state.mysql = docker(['run','-d','--name',`moonbridge-permissions-mysql-${run}`,'--label',`${label}=${run}`,'-p',`127.0.0.1:${state.mysqlPort}:3306`,'--env-file',env,'mysql:8.4']); save();
    state.redisPort = await freePort();
    state.redis = docker(['run','-d','--name',`moonbridge-permissions-redis-${run}`,'--label',`${label}=${run}`,'-p',`127.0.0.1:${state.redisPort}:6379`,'--mount',`type=bind,source=${redisConfig},target=/usr/local/etc/redis/redis.conf,readonly`,'redis:7.4','redis-server','/usr/local/etc/redis/redis.conf']); save();
    await Promise.all([ready(state.mysql,'mysql'),ready(state.redis,'redis')]);
    state.mysqlPort = Number(docker(['port',state.mysql,'3306/tcp']).split(':').pop());
    state.redisPort = Number(docker(['port',state.redis,'6379/tcp']).split(':').pop());
    state.mysqlImage = docker(['image','inspect','mysql:8.4','--format','{{index .RepoDigests 0}}']);
    state.redisImage = docker(['image','inspect','redis:7.4','--format','{{index .RepoDigests 0}}']);
    for (const mode of ['mysql','redis']) {
      const config = `server: "@SERVER@"\nstorage-method: mysql\ndata:\n  address: "127.0.0.1:${state.mysqlPort}"\n  database: moonbridge_permissions\n  username: moonbridge\n  password: "${mysqlPassword}"\n  pool-settings:\n    maximum-pool-size: 4\n    minimum-idle: 1\n    connection-timeout: 5000\n    properties:\n      useSSL: false\n      allowPublicKeyRetrieval: true\n  table-prefix: "${mode}_lp_"\nmessaging-service: ${mode === 'mysql' ? 'auto' : 'redis'}\nsync-minutes: -1\nredis:\n  enabled: ${mode === 'redis'}\n  address: "127.0.0.1:${state.redisPort}"\n  password: "${redisPassword}"\n`;
      fs.writeFileSync(path.join(dir,`${mode}-base.yml`),config);
    }
    save();
    console.log(JSON.stringify({ready:true,mysqlPort:state.mysqlPort,redisPort:state.redisPort,mysqlImage:state.mysqlImage,redisImage:state.redisImage}));
  } else if (action === 'stop') {
    const state = readState();
    cleanup(state);
    console.log('Fixture containers removed; test credentials removed from retained configs');
  } else if (action === 'query') {
    const state = readState(); owned(state.mysql,state.run);
    const sql = "SELECT VERSION(); SHOW TABLES; SELECT COUNT(*) FROM mysql_lp_user_permissions; SELECT COUNT(*) FROM redis_lp_user_permissions; SELECT uuid,permission,value FROM mysql_lp_user_permissions WHERE permission='acceptance.network.mysql-recovered'; SELECT uuid,permission,value FROM redis_lp_user_permissions WHERE permission IN ('acceptance.network.redis-during-outage','acceptance.network.redis-after-recovery');";
    console.log(docker(['exec',state.mysql,'sh','-c',`MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -e "${sql}"`]));
    console.log(docker(['exec',state.redis,'redis-server','--version']));
  } else if (action === 'wait-redis-subscribers') {
    const state = readState(); owned(state.redis,state.run);
    for (let attempt=0; attempt<30; attempt++) {
      const output=docker(['exec',state.redis,'sh','-c','REDISCLI_AUTH=$(sed -n "s/^requirepass //p" /usr/local/etc/redis/redis.conf) redis-cli --raw PUBSUB NUMSUB luckperms:update']);
      if (Number(output.split(/\r?\n/).at(-1))===2) {console.log('Both LuckPerms Redis subscriptions restored');return;}
      await delay(1000);
    }
    throw new Error('The two LuckPerms Redis subscriptions did not reconnect within 30 seconds');
  } else if (['pause-mysql','resume-mysql','pause-redis','resume-redis'].includes(action)) {
    const state = readState(); const [verb,kind] = action.split('-'); owned(state[kind],state.run);
    docker([verb === 'pause' ? 'stop' : 'start',state[kind]]);
    if (verb === 'resume') await ready(state[kind],kind);
    console.log(`${action} completed`);
  } else throw new Error('Usage: node smoke/permission-services.cjs start|stop|query|pause-mysql|resume-mysql|pause-redis|resume-redis|wait-redis-subscribers');
}
main().catch(error => {
 console.error(error.message);process.exitCode=1;
 if (createdThisRun) try {cleanup(readState());} catch(cleanupError) {console.error(`Fixture cleanup failed: ${cleanupError.message}`);}
});

import fs from 'node:fs/promises';
import {existsSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import assert from 'node:assert/strict';

// Synthetic shell runtime exercises installation control flow, not Linux JDK compatibility.
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const temp=await fs.mkdtemp(path.join(root,'build','installer-smoke-'));
const pkg=path.join(temp,'package'),payload=path.join(pkg,'payload'),app=path.join(temp,'app-fixture'),jdk=path.join(temp,'jdk-fixture'),install=path.join(temp,'installed');
for(const dir of [payload,path.join(app,'app'),path.join(jdk,'jdk','bin')])await fs.mkdir(dir,{recursive:true});
await fs.copyFile(path.join(root,'packaging','install.sh'),path.join(pkg,'install.sh'));
await fs.writeFile(path.join(app,'app','zhihui-xinguan.jar'),'synthetic-not-a-real-jar');
await fs.writeFile(path.join(app,'VERSION'),'test-pr0\n');
await fs.copyFile(path.join(root,'start.sh'),path.join(app,'start.sh'));
await fs.writeFile(path.join(jdk,'jdk','bin','java'),'#!/usr/bin/env bash\nset -euo pipefail\nif [[ "${1:-}" == -version ]]; then echo synthetic-java >&2; exit 0; fi\nif [[ "${INSTALL_SMOKE_FAIL:-}" == yes ]]; then exit 19; fi\nwhile (( $# > 0 )); do if [[ "$1" == --data-root ]]; then data_dir="$2"; break; fi; shift; done\n[[ -n "${data_dir:-}" && -d "$data_dir" && ! -L "$data_dir" ]] || exit 20\nprintf "synthetic migration checked\\n" > "$data_dir/synthetic-migration.txt"\n');
const tar=process.platform==='win32'?'tar.exe':'tar';
function resolveBash(){
  if(process.platform!=='win32')return process.env.BASH_EXE||'bash';
  const candidates=[];
  if(process.env.BASH_EXE)candidates.push(process.env.BASH_EXE);
  const pathResult=spawnSync('where.exe',['bash.exe'],{encoding:'utf8',windowsHide:true});
  if(pathResult.status===0)candidates.push(...pathResult.stdout.split(/\r?\n/).filter(Boolean));
  const gitResult=spawnSync('where.exe',['git.exe'],{encoding:'utf8',windowsHide:true});
  if(gitResult.status===0)for(const git of gitResult.stdout.split(/\r?\n/).filter(Boolean)){
    const gitRoot=path.dirname(path.dirname(git));
    candidates.push(path.join(gitRoot,'bin','bash.exe'),path.join(gitRoot,'usr','bin','bash.exe'));
  }
  for(const programFiles of [process.env.ProgramFiles,process.env['ProgramFiles(x86)']])if(programFiles)candidates.push(path.join(programFiles,'Git','bin','bash.exe'));
  const resolved=candidates.find(candidate=>existsSync(candidate));
  if(!resolved)throw new Error('安装器模拟测试需要 Bash；请安装 Git for Windows，或通过 BASH_EXE 指定 bash.exe');
  return resolved;
}
const bash=resolveBash();
function run(cmd,args,options={}){const result=spawnSync(cmd,args,{cwd:root,encoding:'utf8',windowsHide:true,...options});if(result.error)throw result.error;return result;}
function archive(file,cwd){const r=run(tar,['-czf',file,'-C',cwd,'.']);assert.equal(r.status,0,r.stderr);}
archive(path.join(payload,'app.tar.gz'),app);
const runtimeArchive=run(tar,['-czf',path.join(payload,'microsoft-jdk-21.0.12-linux-x64.tar.gz'),'-C',jdk,'jdk']);assert.equal(runtimeArchive.status,0,runtimeArchive.stderr);
await fs.copyFile(path.join(payload,'microsoft-jdk-21.0.12-linux-x64.tar.gz'),path.join(payload,'microsoft-jdk-21.0.12-linux-aarch64.tar.gz'));
const syntheticBootstrap='auth_number=000000001\npassword=synthetic-install-fixture\n';
await fs.writeFile(path.join(payload,'bootstrap.local.properties'),syntheticBootstrap);
async function hashes(){let text='';for(const name of (await fs.readdir(payload)).sort())text+=createHash('sha256').update(await fs.readFile(path.join(payload,name))).digest('hex')+'  payload/'+name+'\n';await fs.writeFile(path.join(pkg,'SHA256SUMS'),text);}
await hashes();
const unix=p=>process.platform==='win32'?p.replaceAll('\\','/').replace(/^([A-Za-z]):/,(_,d)=>'/'+d.toLowerCase()):p;
function installRun(extra={}){return run(bash,[unix(path.join(pkg,'install.sh'))],{env:{...process.env,INSTALL_DIR:unix(install),...extra}});}
let r=installRun();assert.equal(r.status,0,r.stdout+r.stderr);
assert.equal(await fs.readFile(path.join(install,'bootstrap.local.properties'),'utf8'),syntheticBootstrap);
const changedBootstrap='auth_number=000000002\npassword=synthetic-local-change\n';
await fs.writeFile(path.join(install,'bootstrap.local.properties'),changedBootstrap);
assert.equal((await fs.readFile(path.join(install,'.zhihui_xinguan_install'),'utf8')).trim(),'zhihui-xinguan');
await fs.writeFile(path.join(install,'data','sentinel.txt'),'synthetic old data must survive');
r=installRun();assert.equal(r.status,0,r.stdout+r.stderr);
assert.equal(await fs.readFile(path.join(install,'bootstrap.local.properties'),'utf8'),changedBootstrap);
assert.equal(await fs.readFile(path.join(install,'data','sentinel.txt'),'utf8'),'synthetic old data must survive');
const backups=(await fs.readdir(temp)).filter(n=>n.startsWith('.zhihui-xinguan-backup-'));assert.equal(backups.length,1);
assert.equal(await fs.readFile(path.join(temp,backups[0],'data','sentinel.txt'),'utf8'),'synthetic old data must survive');
r=installRun({INSTALL_SMOKE_FAIL:'yes'});assert.notEqual(r.status,0);assert.equal(await fs.readFile(path.join(install,'data','sentinel.txt'),'utf8'),'synthetic old data must survive');
assert.equal((await fs.readdir(temp)).filter(n=>n.startsWith('.zhihui-xinguan-backup-')).length,1);
await fs.appendFile(path.join(payload,'app.tar.gz'),'checksum corruption test');
r=installRun();assert.notEqual(r.status,0);assert.equal(await fs.readFile(path.join(install,'data','sentinel.txt'),'utf8'),'synthetic old data must survive');
console.log('INSTALL_SMOKE_OK fresh install, private initialization, upgrade preserves local configuration and backup, migration failure preservation, checksum failure; synthetic runtime, not Kylin verification');

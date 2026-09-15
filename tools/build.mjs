import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const sourceRevision=spawnSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8',windowsHide:true});
const sourceStatus=spawnSync('git',['status','--porcelain','--untracked-files=normal'],{cwd:root,encoding:'utf8',windowsHide:true});
const sourceCommit=sourceRevision.status===0?sourceRevision.stdout.trim():null;
const sourceDirty=sourceStatus.status!==0||sourceStatus.stdout.trim().length>0;
const args=process.argv.slice(2), caches=[];
for(let i=0;i<args.length;i++)if(args[i]==='--cache')caches.push(path.resolve(args[++i]));
const hash=b=>createHash('sha256').update(b).digest('hex');
async function mkdirSafe(dir){
  const relative=path.relative(root,dir);if(relative.startsWith('..')||path.isAbsolute(relative))throw Error('Output outside project');
  let current=root;for(const part of relative.split(path.sep)){current=path.join(current,part);try{if((await fs.lstat(current)).isSymbolicLink())throw Error('Output contains a link: '+current);}catch(e){if(e.code!=='ENOENT')throw e;}}
  await fs.mkdir(dir,{recursive:true});
}
const build=path.join(root,'build');await mkdirSafe(build);
const app=await fs.mkdtemp(path.join(build,'foundation-app-')), lib=path.join(app,'app','lib');
await mkdirSafe(lib);await mkdirSafe(path.join(root,'vendor','dependencies'));
caches.push(path.join(root,'vendor','dependencies'));
const lock=JSON.parse(await fs.readFile(path.join(root,'dependencies.lock.json'),'utf8'));
for(const d of lock.dependencies){
  const name=`${d.artifact}-${d.version}.jar`;let bytes;
  for(const cache of caches){try{const candidate=await fs.readFile(path.join(cache,name));if(hash(candidate)===d.sha256){bytes=candidate;break;}}catch(e){if(e.code!=='ENOENT')throw e;}}
  if(!bytes){
    if(args.includes('--offline'))throw Error('Dependency is not cached: '+name);
    const url=`https://repo.maven.apache.org/maven2/${d.group.replaceAll('.','/')}/${d.artifact}/${d.version}/${name}`;
    const response=await fetch(url);if(!response.ok)throw Error(`Download ${name}: ${response.status}`);bytes=Buffer.from(await response.arrayBuffer());
    if(hash(bytes)!==d.sha256)throw Error('SHA256 mismatch: '+name);
  }
  await fs.writeFile(path.join(root,'vendor','dependencies',name),bytes);await fs.writeFile(path.join(lib,name),bytes);
}
async function sources(dir){const result=[];for(const e of await fs.readdir(dir,{withFileTypes:true})){if(e.isSymbolicLink())throw Error('Source link is not supported');if(e.isDirectory())result.push(...await sources(path.join(dir,e.name)));else if(e.name.endsWith('.java'))result.push(path.join(dir,e.name));}return result.sort();}
function run(command,argv){const r=spawnSync(command,argv,{cwd:root,stdio:'inherit',windowsHide:true});if(r.error)throw r.error;if(r.status!==0)throw Error(`${command} exited ${r.status}`);}
const classes=await fs.mkdtemp(path.join(build,'classes-'));const cp=path.join(lib,'*');
run('javac',['-encoding','UTF-8','--release','17','-cp',cp,'-d',classes,...await sources(path.join(root,'src'))]);
await fs.cp(path.join(root,'resources'),classes,{recursive:true});
const jar=path.join(app,'app','zhihui-xinguan.jar');run('jar',['--create','--file',jar,'-C',classes,'.']);
await fs.cp(path.join(root,'web'),path.join(app,'web'),{recursive:true});
for(const name of ['start.sh','VERSION','README_zh.md','THIRD_PARTY_NOTICES.md','bootstrap.example.properties'])await fs.copyFile(path.join(root,name),path.join(app,name));
await fs.copyFile(path.join(root,'dependencies.lock.json'),path.join(app,'dependencies.lock.json'));
const runtimeCp=[jar,cp].join(path.delimiter);
run('java',['-Dfile.encoding=UTF-8','-cp',runtimeCp,'Main','--root',app,'--write-templates',path.join(app,'templates')]);
if(args.includes('--test')){
  run(process.execPath,[path.join(root,'tests','identity-form.test.mjs')]);
  const tests=await fs.mkdtemp(path.join(build,'tests-'));
  run('javac',['-encoding','UTF-8','--release','17','-cp',runtimeCp,'-d',tests,...await sources(path.join(root,'tests'))]);
  run('java',['-Dfile.encoding=UTF-8','-cp',[tests,runtimeCp].join(path.delimiter),'FoundationTest',...args.includes('--user-template')?[path.join(root,'智慧信管表头示例.et')]:[]]);
  run('java',['-Dfile.encoding=UTF-8','-cp',[tests,runtimeCp].join(path.delimiter),'HttpSmokeTest',app]);
  run('java',['-Dfile.encoding=UTF-8','-cp',[tests,runtimeCp].join(path.delimiter),'IdentityTest']);
  run('java',['-Dfile.encoding=UTF-8','-cp',[tests,runtimeCp].join(path.delimiter),'BootstrapTest',app]);
  run('java',['-Dfile.encoding=UTF-8','-cp',[tests,runtimeCp].join(path.delimiter),'xinguan.platform.WorkflowPlatformTest']);
  run('java',['-Dfile.encoding=UTF-8','-cp',[tests,runtimeCp].join(path.delimiter),'WorkflowReadModelTest']);
  run('java',['-Dfile.encoding=UTF-8','-cp',[tests,runtimeCp].join(path.delimiter),'xinguan.platform.AccessPlatformTest']);
  run('java',['-Dfile.encoding=UTF-8','-cp',[tests,runtimeCp].join(path.delimiter),'WorkflowRoutesTest']);
}
await fs.writeFile(path.join(build,'foundation-build.json'),JSON.stringify({version:(await fs.readFile(path.join(root,'VERSION'),'utf8')).trim(),sourceCommit,sourceDirty,app,jar,classpath:runtimeCp},null,2));
console.log('BUILD_OK '+app);

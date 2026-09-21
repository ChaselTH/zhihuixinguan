import fs from 'node:fs/promises';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const legacySha='7507f9a384753baa3ebca8719583fb0cea555de8';
const temp=await fs.mkdtemp(path.join(root,'build','schema8-real-'));
function run(cmd,args,options={}){const r=spawnSync(cmd,args,{cwd:root,windowsHide:true,stdio:'inherit',...options});if(r.error)throw r.error;if(r.status!==0)throw Error(`${cmd} exited ${r.status}`);return r;}
const archive=run('git',['archive','--format=tar',legacySha,'src','resources','VERSION'],{stdio:'pipe'});
await fs.writeFile(path.join(temp,'source.tar'),archive.stdout);
run(process.platform==='win32'?'tar.exe':'tar',['-xf',path.join(temp,'source.tar'),'-C',temp]);
if((await fs.readFile(path.join(temp,'VERSION'),'utf8')).trim()!=='0.3.0-rc.10')throw Error('Unexpected legacy product version');
async function javaFiles(dir){const files=[];for(const e of await fs.readdir(dir,{withFileTypes:true}))if(e.isDirectory())files.push(...await javaFiles(path.join(dir,e.name)));else if(e.name.endsWith('.java'))files.push(path.join(dir,e.name));return files;}
const legacyClasses=path.join(temp,'classes');await fs.mkdir(legacyClasses);
const dependencies=path.join(root,'vendor','dependencies','*');
run('javac',['-encoding','UTF-8','--release','17','-cp',dependencies,'-d',legacyClasses,...await javaFiles(path.join(temp,'src')),path.join(root,'tests','Schema8ReviewFixture.java')]);
await fs.cp(path.join(temp,'resources'),legacyClasses,{recursive:true});
const database=path.join(temp,'synthetic-data');
run('java',['-Dfile.encoding=UTF-8','-cp',[legacyClasses,dependencies].join(path.delimiter),'xinguan.platform.Schema8ReviewFixture',database]);
const currentClasspath=process.argv[2];if(!currentClasspath)throw Error('Pass the compiled current tests + application classpath');
run('java',['-Dfile.encoding=UTF-8','-cp',currentClasspath,'xinguan.platform.Feedback009UpgradeReviewTest',database]);
// Independently demonstrate the explicit compatibility boundary for a test DB already on PR #12.
const oldPrSha='7c501e487c3cba485c3137393fc44e0efc8a0b36';
const priorData=path.join(temp,'original-pr-data');
run('java',['-Dfile.encoding=UTF-8','-cp',[legacyClasses,dependencies].join(path.delimiter),'xinguan.platform.Schema8ReviewFixture',priorData,'single-pending']);
const priorSource=path.join(temp,'original-pr-source');await fs.mkdir(priorSource);
await fs.writeFile(path.join(priorSource,'source.tar'),run('git',['archive','--format=tar',oldPrSha,'src','resources'],{stdio:'pipe'}).stdout);
run(process.platform==='win32'?'tar.exe':'tar',['-xf',path.join(priorSource,'source.tar'),'-C',priorSource]);
const priorClasses=path.join(priorSource,'classes');await fs.mkdir(priorClasses);
run('javac',['-encoding','UTF-8','--release','17','-cp',dependencies,'-d',priorClasses,...await javaFiles(path.join(priorSource,'src')),path.join(root,'tests','Schema8ReviewFixture.java')]);
await fs.cp(path.join(priorSource,'resources'),priorClasses,{recursive:true});
run('java',['-Dfile.encoding=UTF-8','-cp',[priorClasses,dependencies].join(path.delimiter),'xinguan.platform.Schema8ReviewFixture',priorData,'open-schema10']);
run('java',['-Dfile.encoding=UTF-8','-cp',currentClasspath,'xinguan.platform.Feedback009UpgradeReviewTest',priorData,'reject-original-pr']);
console.log('REAL_SCHEMA8_UPGRADE_OK legacy='+legacySha);

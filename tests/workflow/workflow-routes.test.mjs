import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..','..');
const build=path.join(root,'build');await fs.mkdir(build,{recursive:true});
const output=await fs.mkdtemp(path.join(build,'workflow-tests-'));
async function javaFiles(dir){const result=[];for(const entry of await fs.readdir(dir,{withFileTypes:true})){const item=path.join(dir,entry.name);if(entry.isDirectory())result.push(...await javaFiles(item));else if(entry.name.endsWith('.java'))result.push(item);}return result.sort();}
function run(command,args){const result=spawnSync(command,args,{cwd:root,stdio:'inherit',windowsHide:true});if(result.error)throw result.error;if(result.status!==0)throw Error(`${command} exited ${result.status}`);}
const classpath=path.join(root,'vendor','dependencies','*');
const fixtures=process.argv.includes('--fixtures')?path.join(build,'workflow-visual'):null;
try {
  run('javac',['-encoding','UTF-8','--release','17','-cp',classpath,'-d',output,...await javaFiles(path.join(root,'src')),path.join(root,'tests','workflow','WorkflowRoutesTest.java')]);
  await fs.cp(path.join(root,'resources'),output,{recursive:true});
  const args=['-Dfile.encoding=UTF-8','-cp',[output,classpath].join(path.delimiter),'WorkflowRoutesTest'];if(fixtures)args.push('--fixtures',fixtures);run('java',args);
  if(fixtures)console.log('WORKFLOW_VISUAL_FIXTURES '+fixtures);
} finally { await fs.rm(output,{recursive:true,force:true}); }

import fs from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const source=await fs.readFile(new URL('../web/assets/workflow.js',import.meta.url),'utf8');
let assertions=0;
function check(value,message){assertions++;assert.ok(value,message);}
function node(tag,legacy,children=[]){
  const item={tag,children,events:{},className:'',value:'',type:'',getElementsByTagName(tag){return this.children.filter(c=>c.tag===tag);},fire(name,e={}){for(const fn of this.events[name]||[])fn(e);return e;}};
  if(legacy)item.attachEvent=function(name,fn){(this.events[name.slice(2)]||=[]).push(fn);};
  else item.addEventListener=function(name,fn){(this.events[name]||=[]).push(fn);};
  return item;
}
for(const legacy of [false,true])for(const action of ['/workflow/draft/save','/update-batch','/workflow/direct/preview']){
  const input=node('textarea',legacy),select=node('select',legacy),hidden=node('input',legacy);hidden.type='hidden';
  const edit=node('form',legacy,[input,select,hidden]);edit.className='workflow-edit-form';edit.action=action;
  const query=node('input',legacy),filter=node('form',legacy,[query]),win=node('window',legacy);
  vm.runInNewContext(source,{window:win,document:{getElementsByTagName:()=>[edit,filter]}});win.fire('load');
  check(!win.fire('beforeunload').returnValue,'clean page does not warn');
  input.value='new typing or paste';check(typeof win.fire('beforeunload').returnValue==='string','typing/paste is protected without relying on change events');
  filter.fire('submit');check(typeof win.fire('beforeunload').returnValue==='string','filter/pagination/navigation cannot clear dirty editor');
  input.value='';select.value='是';check(typeof win.fire('beforeunload').returnValue==='string','select changes protected');
  select.value='';hidden.value='csrf changed';query.value='filter only';check(!win.fire('beforeunload').returnValue,'reverted edits and filter controls are not dirty');
  input.value='saved';edit.fire('submit');check(!win.fire('beforeunload').returnValue,'actual editor submission can navigate');
  win.fire('pageshow');check(typeof win.fire('beforeunload').returnValue==='string','browser back/bfcache resets submit exemption');
}
check(!/\b(const|let|Promise|fetch)\b|=>|\.classList|\.closest\(/.test(source),'ES3 syntax/API baseline');
for (const legacy of [false, true]) {
  const reason = node('input', legacy); reason.name = 'reason';
  const form = node('form', legacy, [reason]);
  const win = { prompt: () => '请补齐资料', alert: () => { throw new Error('unexpected alert'); } };
  const context = { window: win, document: { getElementsByTagName: () => [] } };
  vm.runInNewContext(source, context);
  check(context.workflowRejectReason(form) === true && reason.value === '请补齐资料', 'return popup writes to hidden reason field with modern/legacy DOM');
  win.prompt = () => null;
  check(context.workflowRejectReason(form) === false, 'cancelled return never submits');
}
console.log('WORKFLOW_INTERACTIONS_OK assertions='+assertions+' modern/attachEvent stubs; not actual IE');

import java.util.*;
import xinguan.platform.*;

final class ImportPages extends PageLayout {
  ImportPages(String version,AuthService.Session session){super(version,session);}
  String imports(AuthService.Session session,String message,boolean error){
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\">").append(notice()).append("<h1>数据更新</h1>");
    if(!message.isBlank())b.append("<div class=\"alert ").append(error?"alert-error":"alert-success").append("\">").append(e(message)).append("</div>");
    b.append("<p>每个入口只读取对应格式的子表；包含三个子表的文件也可以分别通过三个入口上传。先解析预览，确认后才写入。</p><div class=\"foundation-upload-grid clearfix\">");
    for(DatasetSchema schema:DatasetSchema.all()){
      b.append("<section class=\"admin-card foundation-upload-card\"><h2>").append(e(schema.label)).append("</h2><p>").append(schema.id.equals("negative")?"填报列：J–L":schema.id.equals("multi")?"填报列：I–P、R–V":"填报列：M–O；所属月份必填").append("</p><form method=\"post\" action=\"/imports/upload/").append(schema.id).append("\" enctype=\"multipart/form-data\">").append(hidden("csrf",session.csrf));
      b.append("<label>选择文件（支持多选）</label><input type=\"file\" name=\"files\" multiple=\"multiple\" accept=\".et,.xls,.xlsx\"><label>旧 IE 可另选第二个文件</label><input type=\"file\" name=\"files\"><label>所属月份 YYYY-MM</label><input name=\"month\" maxlength=\"7\" placeholder=\"例如 2026-09\"><label>补充期次（可选）</label><input name=\"period\" placeholder=\"例如 20260901-20260915\"><p class=\"field-note\">有“时间顺序”的行优先按表内时间分期；交叉违约不以首次违约日作为数据月份。</p><button class=\"btn btn-primary btn-block\" type=\"submit\">解析并预览</button></form><p><a href=\"/template?dataset=").append(schema.id).append("\">下载空白模板</a></p></section>");
    }
    return page("数据更新",b.append("</div></div>").toString());
  }
  String importPreview(AuthService.Session session,String token,String dataset,List<BusinessRecord> rows,List<BusinessRecord> existing,int skipped){
    Set<String> fingerprints=new HashSet<>();for(BusinessRecord r:existing)fingerprints.add(PlatformStore.fingerprint(r));int duplicate=0;for(BusinessRecord r:rows)if(!fingerprints.add(PlatformStore.fingerprint(r)))duplicate++;
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\"><h1>导入预览 · ").append(e(DatasetSchema.get(dataset).label)).append("</h1><div class=\"alert alert-success\">已读取 ").append(rows.size()).append(" 条；重复来源 ").append(duplicate).append(" 条；跳过示例／说明行 ").append(skipped).append(" 条。尚未修改正式数据。</div><p>同一来源依据期次及非黄色字段判断。其他月份、其他模块、原文件中未出现的历史记录保留。</p>");
    b.append("<form method=\"post\" action=\"/imports/confirm\">").append(hidden("csrf",session.csrf)).append(hidden("token",token)).append("<div class=\"rule-box\"><label><input type=\"radio\" name=\"mode\" value=\"preserve\" checked=\"checked\"> 保留系统已有填报内容，仅补充空白</label><br><label><input type=\"radio\" name=\"mode\" value=\"overwrite\"> 使用上传文件的填报值覆盖（空白也会清空原值）</label><br><label><input type=\"checkbox\" name=\"confirmOverwrite\" value=\"yes\"> 选择覆盖时，我确认允许清空上传为空的填报字段</label></div><button class=\"btn btn-primary\" type=\"submit\">确认导入</button> <a class=\"btn btn-light\" href=\"/imports\">取消</a></form><section class=\"panel\"><div class=\"panel-head\"><h2>本批记录（前 30 条）</h2></div><div class=\"table-scroll\"><table class=\"data-table\"><thead><tr><th>企业</th><th>支行</th><th>期次</th></tr></thead><tbody>");
    DatasetSchema s=DatasetSchema.get(dataset);for(BusinessRecord row:rows.subList(0,Math.min(rows.size(),30)))b.append("<tr><td>").append(e(s.value(row.values(),s.customerColumn))).append("</td><td>").append(e(Organizations.label(row.organizationId()))).append("</td><td>").append(e(row.period().key())).append("</td></tr>");
    b.append("</tbody></table></div></section>");
    Map<String,BusinessRecord> oldBySource=new HashMap<>();for(var old:existing)oldBySource.put(PlatformStore.fingerprint(old),old);
    int differences=0;StringBuilder diff=new StringBuilder();
    for(var row:rows){BusinessRecord old=oldBySource.get(PlatformStore.fingerprint(row));if(old==null)continue;for(int c=0;c<s.width();c++)if(s.editable(c)&&!old.values().get(c).equals(row.values().get(c))){differences++;if(differences<=100)diff.append("<tr><td>").append(e(s.value(row.values(),s.customerColumn))).append("</td><td>").append(e(row.period().key())).append("</td><td>").append(e(s.fields.get(c).title())).append("</td><td>").append(e(old.values().get(c).isBlank()?"（空白）":old.values().get(c))).append("</td><td>").append(e(row.values().get(c).isBlank()?"（空白：覆盖将清空）":row.values().get(c))).append("</td></tr>");}}
    if(differences>0)b.append("<section class=\"panel\"><div class=\"panel-head\"><h2>填报差异 ").append(differences).append(" 处（最多展示 100 处）</h2></div><div class=\"table-scroll\"><table class=\"data-table\"><thead><tr><th>企业</th><th>期次</th><th>字段</th><th>系统原值</th><th>上传值</th></tr></thead><tbody>").append(diff).append("</tbody></table></div></section>");
    return page("导入预览",b.append("</div>").toString());
  }
  String diagnostics(Map<String,Integer> counts,List<PlatformStore.AuditEvent> events){
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\"><h1>公共基础状态</h1>").append(notice()).append("<section class=\"panel\"><div class=\"panel-head\"><h2>数据状态</h2></div><div class=\"rule-box\">数据库结构：1<br>九家支行字典：已配置<br>黄色列任一非空完成规则：已启用<br>无风险按钮：未启用<br>账号申请、草稿和复核：接口及表结构预留，页面未启用<br>");
    Map<String,String> labels=Map.of("official_records","正式记录","migration_items","旧数据迁移记录","audit_events","审计事件","users","新身份系统账号","drafts","后续草稿","submissions","后续复核单","unassigned_records","待确认机构记录");
    counts.forEach((k,v)->b.append(e(labels.getOrDefault(k,k))).append("：").append(v).append("<br>"));b.append("</div></section><section class=\"panel\"><div class=\"panel-head\"><h2>最近 100 次操作事件</h2></div><div class=\"table-scroll\"><table class=\"data-table\"><thead><tr><th>时间</th><th>操作人</th><th>机构</th><th>动作</th><th>稳定记录编号</th></tr></thead><tbody>");
    for(var event:events)b.append("<tr><td>").append(e(time(event.at()))).append("</td><td>").append(e(event.actor())).append("</td><td>").append(e(Organizations.label(event.organization()))).append("</td><td>").append(e(event.action())).append("</td><td>").append(e(event.recordId())).append("</td></tr>");
    return page("公共基础状态",b.append("</tbody></table></div></section></div>").toString());
  }
}

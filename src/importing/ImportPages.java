import java.util.*;
import xinguan.platform.*;

final class ImportPages extends PageLayout {
  ImportPages(String version,AuthService.Session session){super(version,session);}
  String imports(AuthService.Session session,String message,boolean error){
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\">").append(notice()).append("<h1>数据更新</h1>");
    if(!message.isBlank())b.append("<div class=\"alert ").append(error?"alert-error":"alert-success").append("\">").append(e(message)).append("</div>");
    b.append("<p>每个入口只读取对应格式的子表；包含三个子表的文件也可以分别通过三个入口上传。先解析预览，确认后才写入。</p><p><a class=\"btn btn-light\" href=\"/imports/jobs\">我的导入任务／继续确认</a></p><p>模板版本 v1：保持现有表头及顺序。客户编码使用文本以保留前导零；支行必须属于九家固定机构。一次可上传 1～10 个同类文件，单文件最多 20 MB、整批 50 MB／20000 条，任一文件错误整批不生效。</p><div class=\"foundation-upload-grid clearfix\">");
    for(DatasetSchema schema:DatasetSchema.all()){
      b.append("<section class=\"admin-card foundation-upload-card\"><h2>").append(e(schema.label)).append("</h2><p>").append(schema.id.equals("negative")?"填报列：J–L":schema.id.equals("multi")?"填报列：I–P、R–V":"填报列：M–O；所属月份必填").append("</p><form method=\"post\" action=\"/imports/upload/").append(schema.id).append("\" enctype=\"multipart/form-data\">").append(hidden("csrf",session.csrf));
      b.append("<label>选择文件（支持多选）</label><input type=\"file\" name=\"files\" multiple=\"multiple\" accept=\".et,.xls,.xlsx\"><label>旧 IE 可另选第二个文件</label><input type=\"file\" name=\"files\"><label>所属月份 YYYY-MM</label><input name=\"month\" maxlength=\"7\" placeholder=\"例如 2026-09\"><label>补充期次（可选）</label><input name=\"period\" placeholder=\"例如 20260901-20260915\"><p class=\"field-note\">有“时间顺序”的行优先按表内时间分期；交叉违约不以首次违约日作为数据月份。</p><button class=\"btn btn-primary btn-block\" type=\"submit\">解析并预览</button></form><p><a href=\"/template?dataset=").append(schema.id).append("\">下载空白模板</a></p></section>");
    }
    return page("数据更新",b.append("</div></div>").toString());
  }
  String diagnostics(int schemaVersion,Map<String,Integer> counts,List<PlatformStore.AuditEvent> events){
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\"><h1>公共基础状态</h1>").append(notice()).append("<section class=\"panel\"><div class=\"panel-head\"><h2>数据状态</h2></div><div class=\"rule-box\">数据库结构：").append(schemaVersion).append("<br>九家支行字典：已配置<br>黄色列任一非空完成规则：已启用<br>无风险按钮：未启用<br>账号申请、私人草稿和复核：已启用<br>导入暂存及逐条覆盖选择：已启用<br>");
    Map<String,String> labels=Map.of("official_records","正式记录","migration_items","旧数据迁移记录","audit_events","审计事件","users","身份系统账号","drafts","私人草稿","submissions","提交复核单","unassigned_records","待确认机构记录");
    counts.forEach((k,v)->b.append(e(labels.getOrDefault(k,k))).append("：").append(v).append("<br>"));b.append("</div></section><section class=\"panel\"><div class=\"panel-head\"><h2>最近 100 次操作事件</h2></div><div class=\"table-scroll\"><table class=\"data-table\"><thead><tr><th>时间</th><th>操作人</th><th>机构</th><th>动作</th><th>稳定记录编号</th></tr></thead><tbody>");
    for(var event:events)b.append("<tr><td>").append(e(time(event.at()))).append("</td><td>").append(e(event.actor())).append("</td><td>").append(e(Organizations.label(event.organization()))).append("</td><td>").append(e(event.action())).append("</td><td>").append(e(event.recordId())).append("</td></tr>");
    return page("公共基础状态",b.append("</tbody></table></div></section></div>").toString());
  }
}

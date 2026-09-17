import java.util.*;
import xinguan.platform.*;

final class ImportPages extends PageLayout {
  ImportPages(String version,AuthService.Session session){super(version,session);}
  String imports(AuthService.Session session,String message,boolean error){
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\">").append(notice()).append("<h1>数据更新</h1>");
    if(!message.isBlank())b.append("<div class=\"alert ").append(error?"alert-error":"alert-success").append("\">").append(e(message)).append("</div>");
    b.append("<p>上传包含三张清单的工作簿，预览后确认导入。</p><p><a class=\"btn btn-light\" href=\"/imports/jobs\">我的导入任务</a></p><section class=\"admin-card foundation-upload-card\"><h2>上传数据</h2><form method=\"post\" action=\"/imports/upload\" enctype=\"multipart/form-data\">").append(hidden("csrf",session.csrf)).append("<label>选择工作簿（可多选）</label><input type=\"file\" name=\"files\" multiple=\"multiple\" accept=\".et,.xls,.xlsx\" required=\"required\"><p class=\"field-note\">多重预警、负面闭环按表内时间顺序分期；交叉违约按首次违约日期分月。</p><button class=\"btn btn-primary btn-block\" type=\"submit\">解析并预览</button></form><p><a href=\"/template?dataset=bundle\">下载三表空白模板</a></p></section></div>");
    return page("数据更新",b.toString());
  }
  String diagnostics(int schemaVersion,Map<String,Integer> counts,List<PlatformStore.AuditEvent> events){
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\"><h1>公共基础状态</h1>").append(notice()).append("<section class=\"panel\"><div class=\"panel-head\"><h2>数据状态</h2></div><div class=\"rule-box\">数据库结构：").append(schemaVersion).append("<br>九家支行字典：已配置<br>黄色列任一非空完成规则：已启用<br>无风险按钮：未启用<br>账号申请、私人草稿和复核：已启用<br>导入暂存及逐条覆盖选择：已启用<br>");
    Map<String,String> labels=Map.of("official_records","正式记录","migration_items","旧数据迁移记录","audit_events","审计事件","users","身份系统账号","drafts","私人草稿","submissions","提交复核单","unassigned_records","待确认机构记录");
    counts.forEach((k,v)->b.append(e(labels.getOrDefault(k,k))).append("：").append(v).append("<br>"));b.append("</div></section><section class=\"panel\"><div class=\"panel-head\"><h2>最近 100 次操作事件</h2></div><div class=\"table-scroll\"><table class=\"data-table\"><thead><tr><th>时间</th><th>操作人</th><th>机构</th><th>动作</th><th>稳定记录编号</th></tr></thead><tbody>");
    for(var event:events)b.append("<tr><td>").append(e(time(event.at()))).append("</td><td>").append(e(event.actor())).append("</td><td>").append(e(Organizations.label(event.organization()))).append("</td><td>").append(e(event.action())).append("</td><td>").append(e(event.recordId())).append("</td></tr>");
    return page("公共基础状态",b.append("</tbody></table></div></section></div>").toString());
  }
}

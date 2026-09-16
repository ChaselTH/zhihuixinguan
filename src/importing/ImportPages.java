import java.util.*;
import xinguan.platform.*;

final class ImportPages extends PageLayout {
  ImportPages(String version,AuthService.Session session){super(version,session);}
  String imports(AuthService.Session session,String message,boolean error){
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\">").append(notice()).append("<h1>数据更新</h1>");
    if(!message.isBlank())b.append("<div class=\"alert ").append(error?"alert-error":"alert-success").append("\">").append(e(message)).append("</div>");
    b.append("<p>一个统一入口处理三张数据表工作簿；先解析预览，确认后才写入，任一工作表错误则三表整批不生效。</p><p><a class=\"btn btn-light\" href=\"/imports/jobs\">我的导入任务／继续确认</a></p><section class=\"admin-card foundation-upload-card\"><h2>三表统一工作簿</h2><p>每个工作簿必须包含负面闭环清单、多重预警清单、交叉违约清单三张数据表；模板说明页不会导入。现代浏览器可一次选择多个完整工作簿，旧 IE 一次选择一个也可完成三表导入。</p><form method=\"post\" action=\"/imports/upload\" enctype=\"multipart/form-data\">").append(hidden("csrf",session.csrf)).append("<label>选择统一工作簿（可多选完整工作簿）</label><input type=\"file\" name=\"files\" multiple=\"multiple\" accept=\".et,.xls,.xlsx\" required=\"required\"><label>所属月份 YYYY-MM（交叉违约表必填）</label><input name=\"month\" maxlength=\"7\" placeholder=\"例如 2026-09\"><label>补充期次（可选）</label><input name=\"period\" placeholder=\"例如 20260901-20260915\"><p class=\"field-note\">有“时间顺序”的行优先按表内时间分期；交叉违约不以首次违约日作为数据月份。</p><button class=\"btn btn-primary btn-block\" type=\"submit\">解析三表并预览</button></form><p><a href=\"/template?dataset=bundle\">下载三表统一空白模板</a></p></section><p class=\"field-note\">旧版客户端仍可使用各表兼容入口：<a href=\"/imports/upload/negative\">负面</a>、<a href=\"/imports/upload/multi\">多重</a>、<a href=\"/imports/upload/cross\">交叉</a>；新上传请统一使用上方入口。</p></div>");
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

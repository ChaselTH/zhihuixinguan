import xinguan.platform.*;

final class CompletionRulePages extends PageLayout {
  CompletionRulePages(String version,AuthService.Session session){super(version,session);}
  String settings(CompletionRules.Setting setting,boolean saved){
    DatasetSchema schema=DatasetSchema.get(setting.dataset());boolean manager=currentSession.actor.role()==Role.DIVISION_ADMIN;
    StringBuilder b=new StringBuilder(backButton("/")).append("<div class=\"completion-settings\"><h1>填报必填设置</h1><p>按清单分别设置，对全部支行和所有期次生效，包括已导入的数据。修改规则只重新计算完成状态，不清空填写内容。</p>");
    if(saved)b.append("<p class=\"alert alert-success\">填报规则已保存，完成率已按新规则计算。</p>");
    b.append("<div class=\"completion-tabs\">");
    for(String type:java.util.List.of("multi","negative","cross"))b.append("<a class=\"btn ").append(type.equals(schema.id)?"btn-primary":"btn-light").append("\" href=\"/completion-rules?dataset=").append(type).append("\">").append(e(DatasetSchema.get(type).label)).append("</a>");
    b.append("</div><p class=\"completion-rule-note\">指定必填列后，这些列全部非空才算该行完成；必填项为空仍可保存、提交和复核。未设置任何必填列时，任意黄色格非空即完成。草稿、待复核不计正式完成。</p>");
    if(!manager)b.append("<p>当前为只读查看，仅分行管理员可以修改。</p>");
    if(manager)b.append("<form method=\"post\" action=\"/completion-rules/save\" class=\"workflow-edit-form\">").append(hidden("csrf",currentSession.csrf)).append(hidden("dataset",schema.id)).append(hidden("revision",""+setting.revision()));
    b.append("<h2>").append(e(schema.label)).append("</h2><div class=\"table-scroll\"><table class=\"data-table completion-settings-table\"><thead><tr><th>表格列</th><th>黄色填报字段</th><th>是否必填</th></tr></thead><tbody>");
    for(int i=0;i<schema.width();i++){
      var field=schema.fields.get(i);if(!field.editable())continue;boolean required=setting.requiredFields().contains(field.key());
      b.append("<tr><td>").append((char)('A'+i)).append("</td><td class=\"editable-cell\">").append(e(field.title())).append("</td><td>");
      if(manager)b.append("<select name=\"required_").append(e(field.key())).append("\" aria-label=\"").append(e(field.title())).append("是否必填\">").append(option("false","否 · 选填",""+required)).append(option("true","是 · 必填",""+required)).append("</select>");
      else b.append(required?"是 · 必填":"否 · 选填");
      b.append("</td></tr>");
    }
    b.append("</tbody></table></div>");
    if(manager)b.append("<p><button type=\"submit\" class=\"btn btn-primary\">保存本清单规则</button></p></form>");
    return new RiskPages(version,currentSession).shell("填报必填设置",b.append("</div>").toString());
  }
}

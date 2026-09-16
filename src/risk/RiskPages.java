import java.util.*;
import xinguan.platform.*;

final class RiskPages extends PageLayout {
  RiskPages(String version,AuthService.Session session){super(version,session);}
  static List<RowRef> homeRows(DashboardData d){List<RowRef> shown=new ArrayList<>();for(String type:List.of("multi","negative"))shown.addAll(d.filtered(type,"","","incomplete").stream().limit(8).toList());return shown;}
  String dashboard(DashboardData d){return dashboard(d,BusinessWorkflowState.empty());}
  String dashboard(DashboardData d,BusinessWorkflowState states){
    StringBuilder b=new StringBuilder(notice()).append("<div class=\"foundation-heading clearfix\"><h1>风险预警</h1><span>最近更新：").append(e(time(d.latestUpdate))).append("</span></div>").append(rangeForm("/",d,""));
    b.append("<div class=\"metric-grid clearfix\">").append(metric("多重预警",d.multiRows.size()+"","条正式记录")).append(metric("负面闭环",d.negativeRows.size()+"","条正式记录")).append(metric("资料补充进度",d.completionPercent()+"%",d.completedCount()+" / "+d.totalCount()+" 条，含交叉违约")).append(metric("交叉违约",d.crossRows.size()+"","行内数据")).append("</div>");
    b.append("<section class=\"panel\"><div class=\"panel-head\"><h2>机构数据与填报进度</h2></div><div class=\"foundation-branches clearfix\">");
    for(var branch:d.branches.values()){
      boolean empty=branch.total==0,done=!empty&&branch.completed==branch.total;
      b.append("<a class=\"foundation-branch").append(empty?" no-records":"").append("\" href=\"/branch?").append(e(d.range.queryString())).append("&amp;branch=").append(u(branch.name)).append("\"><strong>").append(e(branch.name)).append("</strong><span class=\"completion-icon ").append(empty?"empty":done?"done":"pending").append("\" title=\"").append(empty?"本期无记录":done?"全部已补充":"仍有未完成").append("\">").append(empty?"—":done?"✓":"!").append("</span><p>").append(empty?"本期无记录":branch.completed+" / "+branch.total+" 条已补充 · "+branch.percent()+"%").append("</p><div class=\"bar-track\"><i class=\"bar-fill bar-red\" style=\"width:").append(branch.percent()).append("%\"></i></div></a>");
    }
    b.append("</div></section>").append(legend());
    for(String dataset:List.of("multi","negative")){
      List<RowRef> all=d.rows(dataset),pending=d.filtered(dataset,"","","incomplete");
      b.append("<section class=\"panel\"><div class=\"panel-head\"><h2>").append(e(DatasetSchema.get(dataset).label)).append(" · 未完成 ").append(pending.size()).append(" 条</h2><a class=\"btn btn-light\" href=\"").append(detailUrl(d.range,dataset,"")).append("&amp;completion=all\">查看全部 ").append(all.size()).append(" 条</a></div>");
      if(pending.isEmpty())b.append("<div class=\"business-empty\">").append(all.isEmpty()?"本期暂无正式记录":"本期正式记录已全部完成").append("</div>");
      else b.append(preview(dataset,pending.subList(0,Math.min(8,pending.size())),states,d.range));
      b.append("</section>");
    }
    return shell("风险预警",b.toString());
  }
  String details(DashboardData d,String dataset,String q,String branch,int pageNo,AuthService.Session session){return details(d,new BusinessFilter(dataset,branch,q,"all"),pageNo,BusinessWorkflowState.empty());}
  String details(DashboardData d,BusinessFilter filter,int pageNo,BusinessWorkflowState states){
    DatasetSchema schema=DatasetSchema.get(filter.dataset);List<RowRef> all=filter.rows(d);int pages=Math.max(1,(all.size()+49)/50);pageNo=Math.max(1,Math.min(pages,pageNo));
    StringBuilder b=new StringBuilder(rangeForm("/details",d,filter.hidden()));
    b.append("<div class=\"details-title clearfix\"><h1>").append(e(schema.label)).append(filter.branch.isBlank()?"":" · "+e(filter.branch)).append("</h1><p>当前筛选共 ").append(all.size()).append(" 条正式记录；任意黄色填报单元格非空即完成，草稿及待复核不计入。</p><a class=\"btn btn-export\" href=\"/export?").append(e(filter.query(d.range))).append("\">导出当前筛选 Excel</a></div>");
    b.append(filterForm("/details",d,filter)).append(legend());
    int start=(pageNo-1)*50;b.append(table(filter,all.subList(start,Math.min(start+50,all.size())),d.range,pageNo,states));
    b.append("<div class=\"pagination\">");String link="/details?"+e(filter.query(d.range));
    if(pageNo>1)b.append("<a href=\"").append(link).append("&amp;page=").append(pageNo-1).append("\">上一页</a>");
    b.append("<span>第 ").append(pageNo).append(" / ").append(pages).append(" 页</span>");
    if(pageNo<pages)b.append("<a href=\"").append(link).append("&amp;page=").append(pageNo+1).append("\">下一页</a>");
    return shell(schema.label,b.append("</div>").toString());
  }
  String branch(DashboardData d,String branch){return branch(d,branch,BusinessWorkflowState.empty());}
  String branch(DashboardData d,String branch,BusinessWorkflowState states){
    StringBuilder b=new StringBuilder(rangeForm("/branch",d,hidden("branch",branch))).append("<h1>").append(e(branch)).append(" · 数据清单</h1>").append(legend());
    for(DatasetSchema schema:DatasetSchema.all()){List<RowRef> rows=d.filtered(schema.id,"",branch);long done=rows.stream().filter(r->schema.complete(r.values)).count();
      b.append("<section class=\"panel\"><div class=\"panel-head\"><h2>").append(e(schema.label)).append(" · 已完成 ").append(done).append(" / ").append(rows.size()).append("</h2><a class=\"btn btn-dark\" href=\"").append(detailUrl(d.range,schema.id,branch)).append("\">查看及填写全部</a></div>").append(preview(schema.id,rows.subList(0,Math.min(8,rows.size())),states,d.range)).append("</section>");
    }return shell(branch,b.toString());
  }
  String filterForm(String action,DashboardData d,BusinessFilter filter){
    StringBuilder b=new StringBuilder("<form class=\"business-filter\" method=\"get\" action=\"").append(e(action)).append("\">").append(rangeHidden(d.range)).append(hidden("dataset",filter.dataset));
    if(AccessPolicy.all(currentSession.actor)){b.append("<label>机构 <select name=\"branch\">").append(option("","全部支行",filter.branch));for(String name:Organizations.BRANCHES.values())b.append(option(name,name,filter.branch));b.append("</select></label>");}
    else b.append(hidden("branch",filter.branch)).append("<span class=\"business-note\">").append(e(filter.branch)).append(" · </span>");
    b.append("<label>正式完成状态 <select name=\"completion\">").append(option("all","全部",filter.completion)).append(option("incomplete","未完成",filter.completion)).append(option("complete","已完成",filter.completion)).append("</select></label><label>搜索 <input name=\"q\" maxlength=\"100\" value=\"").append(e(filter.search)).append("\" placeholder=\"企业或预警信息\"></label><button class=\"btn btn-dark\" type=\"submit\">筛选</button></form>");return b.toString();
  }
  String preview(String dataset,List<RowRef> refs,BusinessWorkflowState states,RangeSelection range){
    DatasetSchema schema=DatasetSchema.get(dataset);BusinessRowPresentation cells=new BusinessRowPresentation(currentSession);
    StringBuilder b=new StringBuilder("<div class=\"table-scroll\"><table class=\"data-table business-preview\"><thead><tr><th>企业名称</th><th>支行</th><th>来源期次</th><th>正式状态与流程</th></tr></thead><tbody>");
    for(RowRef row:refs)b.append("<tr class=\"").append(schema.complete(row.values)?"row-complete":"row-pending").append("\"><td class=\"business-customer\">").append(e(schema.value(row.values,schema.customerColumn))).append("</td><td>").append(e(schema.value(row.values,schema.branchColumn))).append("</td><td>").append(e(row.record.period)).append("</td><td class=\"business-status\">").append(cells.status(row,states)).append("<div class=\"business-actions\">").append(cells.actions(row,states,range)).append("</div></td></tr>");
    if(refs.isEmpty())b.append("<tr><td colspan=\"4\" class=\"table-empty\">当前条件下暂无正式记录</td></tr>");
    return b.append("</tbody></table></div>").toString();
  }
  private String table(BusinessFilter filter,List<RowRef> rows,RangeSelection range,int pageNo,BusinessWorkflowState states){
    DatasetSchema schema=DatasetSchema.get(filter.dataset);var session=currentSession;boolean directAllowed=AccessPolicy.can(session.actor,AccessPolicy.Action.DIRECT_EDIT,session.actor.organizationId());
    boolean singleOrganization=rows.stream().map(r->r.record.organizationId).distinct().count()<=1&&rows.stream().allMatch(r->Organizations.BRANCHES.containsKey(r.record.organizationId));
    boolean canEdit=directAllowed&&singleOrganization;
    BusinessRowPresentation cells=new BusinessRowPresentation(session);
    StringBuilder b=new StringBuilder("<form method=\"post\" action=\"/update-batch\">").append(hidden("csrf",session.csrf)).append(hidden("requestId",UUID.randomUUID().toString())).append(rangeHidden(range)).append(filter.hidden()).append(hidden("page",""+pageNo)).append(hidden("rows",""+rows.size()));
    if(!rows.isEmpty()&&canEdit)b.append("<div class=\"batch-edit-bar clearfix\"><span>统一保存当前页；先预览差异，确认后才生效</span><button class=\"btn btn-primary\" type=\"submit\">保存资料补充 · 预览确认</button></div>");
    if(directAllowed&&!singleOrganization)b.append("<p class=\"business-note\">跨支行清单请先筛选一家支行后统一填写，也可使用每条记录的修改入口。</p>");
    if(session.actor.role()==Role.OPERATOR)b.append("<p class=\"business-note\">黄色列展示正式值；点击每条记录的“填写并提交”或“继续我的草稿”进入私人填报。</p>");
    b.append("<div class=\"table-scroll\"><table class=\"data-table detail-table\"><thead><tr><th class=\"business-status\">正式状态／流程入口</th>");
    for(var field:schema.fields)b.append("<th class=\"").append(field.editable()?"editable-head ":"").append(width(field)).append("\">").append(e(field.title())).append("</th>");
    b.append("<th>期次／历史保留信息</th></tr></thead><tbody>");
    for(int i=0;i<rows.size();i++){RowRef row=rows.get(i);b.append("<tr class=\"").append(schema.complete(row.values)?"row-complete":"row-pending").append("\"><td class=\"business-status\">").append(cells.status(row,states)).append("<div class=\"business-actions\">").append(cells.actions(row,states,range)).append("</div></td>");
      for(int c=0;c<schema.width();c++){var field=schema.fields.get(c);String value=schema.value(row.values,c);b.append("<td class=\"").append(field.editable()?"editable-cell ":"").append(width(field)).append("\">");
        if(c==0)b.append(hidden("id"+i,row.record.id)).append(hidden("version"+i,""+row.record.versions.get(row.rowIndex)));
        if(field.editable()&&canEdit){String name="v"+i+"_"+c;if(field.options().isEmpty())b.append("<textarea rows=\"3\" name=\"").append(name).append("\">").append(e(value)).append("</textarea>");
          else{b.append("<select name=\"").append(name).append("\">").append(option("","请选择",value));if(!value.isBlank()&&!field.options().contains(value))b.append(option(value,value+"（历史值）",value));for(String option:field.options())b.append(option(option,option,value));b.append("</select>");}}
        else b.append(e(value));b.append("</td>");
      }
      b.append("<td class=\"col-long\">").append(e(row.record.period));for(var entry:row.record.legacyExtras.entrySet())b.append("<br>").append(e(entry.getKey())).append("：").append(e(entry.getValue()));b.append("</td></tr>");
    }
    if(rows.isEmpty())b.append("<tr><td colspan=\"").append(schema.width()+2).append("\" class=\"table-empty\">当前筛选无记录，请调整时间、机构、状态或搜索条件</td></tr>");
    return b.append("</tbody></table></div></form>").toString();
  }
  static String width(DatasetSchema.Field field){return field.key().contains("feedback")||field.key().equals("control_measures")||field.key().equals("warning_detail")?"col-feedback":field.title().length()>18?"col-long":"col-standard";}
  static String legend(){return "<p class=\"business-legend business-note\">浅绿色：正式已完成；白色：正式未完成；黄色：可填报字段。流程提示不改变正式完成率，尚无反馈截止字段，不显示超时颜色。</p>";}
  String shell(String title,String body){return page(title,header()+"<div class=\"page-shell details-shell business-shell\">"+body+"</div>").replace("</head>","<link rel=\"stylesheet\" href=\"/assets/business.css\"></head>");}
  private String metric(String title,String value,String note){return "<div class=\"metric-card\"><div class=\"metric-body\"><span class=\"metric-label\">"+e(title)+"</span><strong>"+e(value)+"</strong><small>"+e(note)+"</small></div></div>";}
}

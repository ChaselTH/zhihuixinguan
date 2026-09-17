import java.util.*;
import xinguan.platform.*;

final class RiskPages extends PageLayout {
  RiskPages(String version,AuthService.Session session){super(version,session);}
  static List<RowRef> homeRows(DashboardData d){List<RowRef> shown=new ArrayList<>();for(String type:List.of("multi","negative","cross"))shown.addAll(d.filtered(type,"","","incomplete").stream().limit(8).toList());return shown;}
  String dashboard(DashboardData d){return dashboard(d,BusinessWorkflowState.empty());}
  String dashboard(DashboardData d,BusinessWorkflowState states){
    StringBuilder b=new StringBuilder(notice()).append("<div class=\"foundation-heading clearfix\"><h1>风险预警</h1><span>最近更新：").append(e(time(d.latestUpdate))).append("</span></div>").append(rangeForm("/",d,""));
    b.append("<div class=\"metric-grid clearfix\">");
    for(String type:List.of("multi","negative","cross"))b.append(metric(DatasetSchema.get(type).label,d.rows(type).size()+"","条正式记录",detailUrl(d.range,type,"")));
    b.append(metric("资料补充进度",d.completionPercent()+"%",d.completedCount()+" / "+d.totalCount()+" 条","/progress?"+e(d.range.queryString()))).append("</div>");
    b.append(new FeedbackPages(version,currentSession).home(d));
    b.append("<section class=\"panel\"><div class=\"panel-head\"><h2>机构数据与填报进度</h2><a class=\"btn btn-light\" href=\"/progress?").append(e(d.range.queryString())).append("\">查看进度汇总</a>");
    if(AccessPolicy.all(currentSession.actor))b.append("<a class=\"btn btn-export\" href=\"/export/progress?").append(e(d.range.queryString())).append("\">批量导出填报进度</a>");
    b.append("</div><div class=\"foundation-branches clearfix\">");
    for(var branch:d.branches.values()){
      b.append("<div class=\"foundation-branch\"><a class=\"branch-heading\" href=\"/branch?").append(e(d.range.queryString())).append("&amp;branch=").append(u(branch.name)).append("\"><strong>").append(e(branch.name)).append("</strong><span>查看清单 →</span></a>");
      for(String type:List.of("multi","negative","cross")){
        int total=branch.total(type),done=branch.completed(type);boolean empty=total==0,complete=!empty&&done==total;
        b.append("<a class=\"branch-progress\" href=\"").append(detailUrl(d.range,type,branch.name)).append("\"><span class=\"completion-icon ").append(empty?"empty":complete?"done":"pending").append("\" title=\"").append(empty?"本期无记录":complete?"全部已补充":"仍有未完成").append("\">").append(empty?"—":complete?"✓":"!").append("</span>").append(e(DatasetSchema.get(type).label)).append("<small>").append(empty?"本期无记录":done+" / "+total+" · "+branch.percent(type)+"%").append("</small><div class=\"bar-track\"><i class=\"bar-fill ").append(complete?"bar-green":"bar-red").append("\" style=\"width:").append(branch.percent(type)).append("%\"></i></div></a>");
      }
      b.append("</div>");
    }
    b.append("</div></section>").append(legend());
    for(String dataset:List.of("multi","negative","cross")){
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
    DatasetSchema schema=DatasetSchema.get(filter.dataset);List<RowRef> all=filter.rows(d);int pages=Math.max(1,(all.size()+filter.pageSize-1)/filter.pageSize);pageNo=Math.max(1,Math.min(pages,pageNo));
    StringBuilder b=new StringBuilder(backButton("/?"+d.range.queryString())).append(rangeForm("/details",d,filter.hidden()));
    b.append("<div class=\"details-title clearfix\"><h1>").append(e(schema.label)).append(filter.branch.isBlank()?"":" · "+e(filter.branch)).append("</h1><p>当前筛选共 ").append(all.size()).append(" 条正式记录；任意黄色填报单元格非空即完成，草稿及待复核不计入。</p><a class=\"btn btn-export\" href=\"/export?").append(e(filter.query(d.range))).append("\">导出当前筛选 Excel</a></div>");
    b.append(filterForm("/details",d,filter)).append(legend());
    String pager=pager(d,filter,pageNo,pages);b.append(pager);
    int start=(pageNo-1)*filter.pageSize;b.append(table(filter,all.subList(start,Math.min(start+filter.pageSize,all.size())),d.range,pageNo,states)).append(pager);
    return shell(schema.label,b.toString());
  }
  private String pager(DashboardData d,BusinessFilter filter,int page,int pages){
    StringBuilder b=new StringBuilder("<div class=\"pagination\">");String link="/details?"+e(filter.query(d.range));
    if(page>1)b.append("<a href=\"").append(link).append("&amp;page=").append(page-1).append("\">上一页</a>");
    b.append("<span>第 ").append(page).append(" / ").append(pages).append(" 页 · 每页 ").append(filter.pageSize).append(" 条</span>");
    if(page<pages)b.append("<a href=\"").append(link).append("&amp;page=").append(page+1).append("\">下一页</a>");
    return b.append("</div>").toString();
  }
  String progress(DashboardData d,String selectedBranch){
    StringBuilder b=new StringBuilder(backButton("/?"+d.range.queryString())).append("<h1>资料补充进度汇总</h1>").append(rangeForm("/progress",d,hidden("branch",selectedBranch)));
    if(AccessPolicy.all(currentSession.actor)){
      b.append("<form class=\"business-filter\" method=\"get\" action=\"/progress\">").append(rangeHidden(d.range)).append("<label>机构 <select name=\"branch\">").append(option("","全部支行",selectedBranch));
      for(String name:Organizations.BRANCHES.values())b.append(option(name,name,selectedBranch));
      b.append("</select></label><button class=\"btn btn-dark\" type=\"submit\">查看</button></form><p><a class=\"btn btn-export\" href=\"/export/progress?").append(e(d.range.queryString())).append("&amp;branch=").append(u(selectedBranch)).append("\">批量导出填报进度</a></p>");
    }
    b.append("<p>仅统计正式数据；黄色填报列任意一格非空即完成，不含草稿和待复核。</p><div class=\"table-scroll\"><table class=\"data-table progress-table\"><thead><tr><th>机构</th><th>清单</th><th>总数</th><th>已完成</th><th>未完成</th><th>完成率</th><th>明细</th></tr></thead><tbody>");
    int total=0,done=0;
    for(var branch:d.branches.values())if(selectedBranch.isBlank()||selectedBranch.equals(branch.name))for(String type:List.of("multi","negative","cross")){
      int n=branch.total(type),c=branch.completed(type);total+=n;done+=c;
      b.append("<tr><td>").append(e(branch.name)).append("</td><td>").append(e(DatasetSchema.get(type).label)).append("</td><td>").append(n).append("</td><td>").append(c).append("</td><td>").append(n-c).append("</td><td>").append(n==0?"—":branch.percent(type)+"%").append("</td><td><a href=\"").append(detailUrl(d.range,type,branch.name)).append("\">查看</a></td></tr>");
    }
    b.append("<tr class=\"progress-total\"><td colspan=\"2\">合计</td><td>").append(total).append("</td><td>").append(done).append("</td><td>").append(total-done).append("</td><td>").append(total==0?"—":done*100/total+"%").append("</td><td></td></tr></tbody></table></div>");
    return shell("填报进度",b.toString());
  }

  String branch(DashboardData d,String branch){return branch(d,branch,BusinessWorkflowState.empty());}
  String branch(DashboardData d,String branch,BusinessWorkflowState states){
    StringBuilder b=new StringBuilder(backButton("/?"+d.range.queryString())).append(rangeForm("/branch",d,hidden("branch",branch))).append("<h1>").append(e(branch)).append(" · 数据清单</h1>").append(legend());
    for(DatasetSchema schema:DatasetSchema.all()){List<RowRef> rows=d.filtered(schema.id,"",branch);long done=rows.stream().filter(r->schema.complete(r.values)).count();
      b.append("<section class=\"panel\"><div class=\"panel-head\"><h2>").append(e(schema.label)).append(" · 已完成 ").append(done).append(" / ").append(rows.size()).append("</h2><a class=\"btn btn-dark\" href=\"").append(detailUrl(d.range,schema.id,branch)).append("\">查看及填写全部</a></div>").append(preview(schema.id,rows.subList(0,Math.min(8,rows.size())),states,d.range)).append("</section>");
    }return shell(branch,b.toString());
  }
  String filterForm(String action,DashboardData d,BusinessFilter filter){
    StringBuilder b=new StringBuilder("<form class=\"business-filter\" method=\"get\" action=\"").append(e(action)).append("\">").append(rangeHidden(d.range)).append(hidden("dataset",filter.dataset)).append(hidden("period",filter.period));
    if(AccessPolicy.all(currentSession.actor)){b.append("<label>机构 <select name=\"branch\">").append(option("","全部支行",filter.branch));for(String name:Organizations.BRANCHES.values())b.append(option(name,name,filter.branch));b.append("</select></label>");}
    else b.append(hidden("branch",filter.branch)).append("<span class=\"business-note\">").append(e(filter.branch)).append(" · </span>");
    if(!filter.period.isBlank())b.append("<span class=\"business-note\">期次：").append(e(filter.period)).append(" <a href=\"").append(detailUrl(d.range,filter.dataset,filter.branch)).append("\">取消期次限定</a></span>");
    b.append("<label>每页 <select name=\"pageSize\">");for(int size:List.of(10,20,50))b.append(option(""+size,size+" 条",""+filter.pageSize));b.append("</select></label>");
    b.append("<label>正式完成状态 <select name=\"completion\">").append(option("all","全部",filter.completion)).append(option("incomplete","未完成",filter.completion)).append(option("complete","已完成",filter.completion)).append(option("overdue","超期反馈",filter.completion)).append("</select></label><label>搜索 <input name=\"q\" maxlength=\"100\" value=\"").append(e(filter.search)).append("\" placeholder=\"企业或预警信息\"></label><button class=\"btn btn-dark\" type=\"submit\">筛选</button></form>");return b.toString();
  }
  String preview(String dataset,List<RowRef> refs,BusinessWorkflowState states,RangeSelection range){
    DatasetSchema schema=DatasetSchema.get(dataset);BusinessRowPresentation cells=new BusinessRowPresentation(currentSession);
    StringBuilder b=new StringBuilder("<div class=\"table-scroll\"><table class=\"data-table business-preview\"><thead><tr><th>企业名称</th><th>支行</th><th>来源期次</th><th>正式状态与流程</th></tr></thead><tbody>");
    for(RowRef row:refs)b.append("<tr class=\"").append(row.rowClass()).append("\"><td class=\"business-customer\">").append(e(schema.value(row.values,schema.customerColumn))).append("</td><td>").append(e(schema.value(row.values,schema.branchColumn))).append("</td><td>").append(e(row.record.period)).append("</td><td class=\"business-status\">").append(cells.status(row,states)).append("<div class=\"business-actions\">").append(cells.actions(row,states,range)).append("</div></td></tr>");
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
    for(int i=0;i<rows.size();i++){RowRef row=rows.get(i);b.append("<tr class=\"").append(row.rowClass()).append("\"><td class=\"business-status\">").append(cells.status(row,states)).append("<div class=\"business-actions\">").append(cells.actions(row,states,range)).append("</div></td>");
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
  static String legend(){return "<p class=\"business-legend business-note\">浅绿色：正式已完成；白色：正式未完成；黄色：可填报字段。红色：超过反馈截止日期仍未完成；黄色填报列保持原色。草稿及待复核不计正式完成。</p>";}
  String shell(String title,String body){return page(title,header()+"<div class=\"page-shell details-shell business-shell\">"+body+"</div>").replace("</head>","<link rel=\"stylesheet\" href=\"/assets/business.css\"></head>");}
  private String metric(String title,String value,String note,String href){return "<a class=\"metric-card metric-link\" href=\""+href+"\"><div class=\"metric-body\"><span class=\"metric-label\">"+e(title)+"</span><strong>"+e(value)+"</strong><small>"+e(note)+"</small></div></a>";}
}

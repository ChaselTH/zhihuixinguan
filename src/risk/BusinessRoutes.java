import com.sun.net.httpserver.HttpExchange;
import java.util.*;
import java.time.*;
import xinguan.platform.*;
import xinguan.platform.WorkflowContracts.*;

/** Authorized read-only B2 adapter. Main retains all authentication and safety gates. */
final class BusinessRoutes extends HttpSupport {
  private final DataStore store;private final String version;
  BusinessRoutes(DataStore store,String version){this.store=store;this.version=version;}
  boolean get(HttpExchange x,AuthService.Session session,Map<String,String> query)throws Exception{
    String path=x.getRequestURI().getPath();if(!Set.of("/","/details","/branch","/internal","/progress","/records/history").contains(path))return false;
    if(path.equals("/records/history")){if(query.getOrDefault("id","").isBlank())historyList(x,session,query);else history(x,session,query);return true;}
    Map<String,String> input=new HashMap<>(query);if(path.equals("/internal"))input.put("dataset","cross");
    BusinessFilter filter=BusinessFilter.from(session.actor,input);List<String> months=store.months(session.actor);RangeSelection range=RangeSelection.from(query,months);
    DashboardData data=new DashboardData(range,months,List.of(),store.readRange(range,session.actor));
    if(!AccessPolicy.all(session.actor))data.branches.entrySet().removeIf(e->!e.getKey().equals(Organizations.label(session.actor.organizationId())));
    RiskPages pages=new RiskPages(version,session);String html;
    if(path.equals("/"))html=pages.dashboard(data,states(session,RiskPages.homeRows(data)));
    else if(path.equals("/progress"))html=pages.progress(data,filter.branch);
    else if(path.equals("/branch")){
      if(filter.branch.isBlank())throw new IllegalArgumentException("请选择支行");List<RowRef> shown=new ArrayList<>();
      for(var schema:DatasetSchema.all())shown.addAll(data.filtered(schema.id,"",filter.branch).stream().limit(8).toList());
      html=pages.branch(data,filter.branch,states(session,shown));
    }else if(path.equals("/internal"))html=new InternalPages(version,session).overview(data,filter,states(session,filter.rows(data).stream().limit(8).toList()));
    else{
      List<RowRef> selected=filter.rows(data);int pagesCount=Math.max(1,(selected.size()+filter.pageSize-1)/filter.pageSize);int page=Math.max(1,Math.min(pagesCount,integer(query.get("page"),1)));int start=(page-1)*filter.pageSize;
      html=pages.details(data,filter,page,states(session,selected.subList(start,Math.min(start+filter.pageSize,selected.size()))));
    }
    sendHtml(x,200,html);return true;
  }
  private BusinessWorkflowState states(AuthService.Session s,List<RowRef> rows){return BusinessWorkflowState.load(store.platform,s.actor,rows);}
  private void history(HttpExchange x,AuthService.Session session,Map<String,String> query)throws Exception{
    BusinessRecord row=store.platform.find(session.actor,query.get("id"));int offset=integer(query.get("offset"),0);
    List<Submission> history=store.platform.workflow().recordHistory(session.actor,row.id(),offset,25);var schema=DatasetSchema.get(row.dataset());
    StringBuilder b=new StringBuilder(PageLayout.backButton("/")).append("<h1>记录追溯 · ").append(PageLayout.e(schema.value(row.values(),schema.customerColumn))).append("</h1><p>").append(PageLayout.e(Organizations.label(row.organizationId()))).append(" · ").append(PageLayout.e(row.period().key())).append(" · 正式版本 ").append(row.version()).append("</p><p class=\"business-note\">显示该记录关联的提交历史；私人草稿不公开。导入及字段修改详情沿用公共操作记录。</p><p><a class=\"btn btn-light\" href=\"/audit?category=business&amp;search=").append(PageLayout.u(row.id())).append("&amp;dataset=").append(PageLayout.u(row.dataset())).append("\">查看该记录的公共操作记录</a></p><div class=\"table-scroll\"><table class=\"data-table business-history-table\"><thead><tr><th>提交时间</th><th>提交人</th><th>处理状态</th><th>复核人</th><th>操作</th></tr></thead><tbody>");
    for(var item:history)b.append("<tr><td>").append(PageLayout.e(PageLayout.time(item.createdAt().toString()))).append("</td><td>").append(PageLayout.e(item.ownerName())).append("</td><td>").append(item.state()==State.SUBMITTED?"待复核":item.state()==State.RETURNED?"已退回":item.mode()==Mode.DIRECT?"直接生效":"已通过").append("</td><td>").append(PageLayout.e(item.reviewerName())).append("</td><td><a href=\"/workflow/submission?id=").append(PageLayout.u(item.id())).append("\">提交详情</a> · <a href=\"/audit?submissionId=").append(PageLayout.u(item.id())).append("\">公共审计</a></td></tr>");
    if(history.isEmpty())b.append("<tr><td colspan=\"5\">暂无工作流提交，导入历史请查看公共操作记录</td></tr>");
    b.append("</tbody></table></div>").append(AccessPages.pager("/records/history?id="+PageLayout.u(row.id()),offset,history.size()));
    sendHtml(x,200,new RiskPages(version,session).shell("记录追溯",b.toString()));
  }
  private record HistoryLine(BusinessRecord record,Submission submission,SnapshotRow row) {}
  private void historyList(HttpExchange x,AuthService.Session session,Map<String,String> query)throws Exception {
    ActorContext actor=session.actor;String org=query.getOrDefault("organization","").strip();
    if(!org.isEmpty()&&!Organizations.BRANCHES.containsKey(org))throw new IllegalArgumentException("查询机构无效");
    if(!org.isEmpty())AccessPolicy.require(actor,AccessPolicy.Action.VIEW,org);
    if(org.isEmpty()&&!AccessPolicy.all(actor))org=actor.organizationId();
    String dataset=query.getOrDefault("dataset","").strip();if(!dataset.isEmpty())DatasetSchema.get(dataset);
    LocalDate from=parseDate(query.get("from"),"开始日期"),through=parseDate(query.get("through"),"结束日期");
    if(from!=null&&through!=null&&from.isAfter(through))throw new IllegalArgumentException("开始日期不能晚于结束日期");
    String search=limit(query.get("search"),100);List<HistoryLine> lines=new ArrayList<>();
    for(BusinessRecord record:store.platform.list(actor,dataset,from,through)) {
      if(!org.isEmpty()&&!org.equals(record.organizationId()))continue;
      String customer=DatasetSchema.get(record.dataset()).value(record.values(),DatasetSchema.get(record.dataset()).customerColumn);
      for(Submission submission:store.platform.workflow().recordHistory(actor,record.id(),0,100)) {
        if(from!=null&&submission.createdAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().isBefore(from))continue;
        if(through!=null&&submission.createdAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().isAfter(through))continue;
        if(!search.isBlank()&&!submission.ownerName().contains(search)&&!submission.ownerId().contains(search)&&!customer.contains(search))continue;
        for(SnapshotRow row:submission.rows())if(row.before().id().equals(record.id()))lines.add(new HistoryLine(record,submission,row));
      }
    }
    lines.sort(Comparator.comparing((HistoryLine line)->line.submission.createdAt()).reversed().thenComparing(line->line.submission.id()));
    int offset=Math.max(0,HttpSupport.integer(query.get("offset"),0));int pageSize=25;List<HistoryLine> shown=offset>=lines.size()?List.of():lines.subList(offset,Math.min(offset+pageSize,lines.size()));
    StringBuilder b=new StringBuilder(PageLayout.backButton("/"));
    b.append("<div class=\"details-title clearfix\"><h1>支行修改记录</h1><p>只显示已提交的正式修改；私人草稿和草稿保存事件不进入共享追溯。</p></div>");
    b.append("<form method=\"get\" action=\"/records/history\" class=\"business-filter history-filter\"><label>支行 <select name=\"organization\">").append(PageLayout.option("","全部授权支行",org));
    if(AccessPolicy.all(actor))for(var branch:Organizations.BRANCHES.entrySet())b.append(PageLayout.option(branch.getKey(),branch.getValue(),org));
    b.append("</select></label><label>清单 <select name=\"dataset\">").append(PageLayout.option("","全部清单",dataset));for(var schema:DatasetSchema.all())b.append(PageLayout.option(schema.id,schema.label,dataset));
    b.append("</select></label><label>开始日期<input name=\"from\" value=\"").append(PageLayout.e(query.get("from"))).append("\" placeholder=\"YYYY-MM-DD\"></label><label>结束日期<input name=\"through\" value=\"").append(PageLayout.e(query.get("through"))).append("\" placeholder=\"YYYY-MM-DD\"></label><label>操作人／企业<input name=\"search\" maxlength=\"100\" value=\"").append(PageLayout.e(search)).append("\"></label><button class=\"btn btn-dark\" type=\"submit\">筛选</button></form>");
    b.append("<div class=\"table-scroll\"><table class=\"data-table business-history-table\"><thead><tr><th>时间</th><th>机构</th><th>操作人</th><th>企业／清单</th><th>修改字段及前后值</th><th>状态</th><th>详情</th></tr></thead><tbody>");
    for(HistoryLine line:shown){DatasetSchema schema=DatasetSchema.get(line.record.dataset());StringBuilder diff=new StringBuilder();for(FieldDiff field:line.row.fields())diff.append("<div><strong>").append(PageLayout.e(field.title())).append("</strong>：").append(PageLayout.e(blank(field.before()))).append(" → ").append(PageLayout.e(blank(field.after()))).append("</div>");
      b.append("<tr><td>").append(PageLayout.e(PageLayout.time(line.submission.createdAt().toString()))).append("</td><td>").append(PageLayout.e(Organizations.label(line.record.organizationId()))).append("</td><td>").append(PageLayout.e(line.submission.ownerName())).append("</td><td>").append(PageLayout.e(schema.value(line.record.values(),schema.customerColumn))).append("<br><small>").append(PageLayout.e(schema.label)).append(" · ").append(PageLayout.e(line.record.period().key())).append("</small></td><td class=\"access-pre\">").append(diff).append("</td><td>").append(line.submission.state()==State.SUBMITTED?"待复核":line.submission.state()==State.RETURNED?"已退回":line.submission.mode()==Mode.DIRECT?"直接生效":"已通过").append("</td><td><a href=\"/workflow/submission?id=").append(PageLayout.u(line.submission.id())).append("\">提交详情</a> · <a href=\"/audit?submissionId=").append(PageLayout.u(line.submission.id())).append("\">公共审计</a></td></tr>");
    }
    if(shown.isEmpty())b.append("<tr><td colspan=\"7\" class=\"table-empty\">当前条件下暂无正式修改记录</td></tr>");
    b.append("</tbody></table></div>");if(offset>0||offset+shown.size()<lines.size())b.append(AccessPages.pager("/records/history?organization="+PageLayout.u(org)+"&amp;dataset="+PageLayout.u(dataset)+"&amp;from="+PageLayout.u(query.get("from"))+"&amp;through="+PageLayout.u(query.get("through"))+"&amp;search="+PageLayout.u(search),offset,shown.size()));
    sendHtml(x,200,new RiskPages(version,session).shell("支行修改记录",b.toString()));
  }
  private static LocalDate parseDate(String value,String label){if(value==null||value.isBlank())return null;try{return LocalDate.parse(value);}catch(Exception e){throw new IllegalArgumentException(label+"格式应为 YYYY-MM-DD");}}
  private static String blank(String value){return value==null||value.isBlank()?"（空白）":value;}
}

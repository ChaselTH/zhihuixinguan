import com.sun.net.httpserver.HttpExchange;
import java.util.*;
import xinguan.platform.*;
import xinguan.platform.WorkflowContracts.*;

/** Authorized read-only B2 adapter. Main retains all authentication and safety gates. */
final class BusinessRoutes extends HttpSupport {
  private final DataStore store;private final String version;
  BusinessRoutes(DataStore store,String version){this.store=store;this.version=version;}
  boolean get(HttpExchange x,AuthService.Session session,Map<String,String> query)throws Exception{
    String path=x.getRequestURI().getPath();if(!Set.of("/","/details","/branch","/internal","/records/history").contains(path))return false;
    if(path.equals("/records/history")){history(x,session,query);return true;}
    Map<String,String> input=new HashMap<>(query);if(path.equals("/internal"))input.put("dataset","cross");
    BusinessFilter filter=BusinessFilter.from(session.actor,input);List<String> months=store.months(session.actor);RangeSelection range=RangeSelection.from(query,months);
    DashboardData data=new DashboardData(range,months,List.of(),store.readRange(range,session.actor));
    if(!AccessPolicy.all(session.actor))data.branches.entrySet().removeIf(e->!e.getKey().equals(Organizations.label(session.actor.organizationId())));
    RiskPages pages=new RiskPages(version,session);String html;
    if(path.equals("/"))html=pages.dashboard(data,states(session,RiskPages.homeRows(data)));
    else if(path.equals("/branch")){
      if(filter.branch.isBlank())throw new IllegalArgumentException("请选择支行");List<RowRef> shown=new ArrayList<>();
      for(var schema:DatasetSchema.all())shown.addAll(data.filtered(schema.id,"",filter.branch).stream().limit(8).toList());
      html=pages.branch(data,filter.branch,states(session,shown));
    }else if(path.equals("/internal"))html=new InternalPages(version,session).overview(data,filter,states(session,filter.rows(data).stream().limit(8).toList()));
    else{
      List<RowRef> selected=filter.rows(data);int pagesCount=Math.max(1,(selected.size()+49)/50);int page=Math.max(1,Math.min(pagesCount,integer(query.get("page"),1)));int start=(page-1)*50;
      html=pages.details(data,filter,page,states(session,selected.subList(start,Math.min(start+50,selected.size()))));
    }
    sendHtml(x,200,html);return true;
  }
  private BusinessWorkflowState states(AuthService.Session s,List<RowRef> rows){return BusinessWorkflowState.load(store.platform,s.actor,rows);}
  private void history(HttpExchange x,AuthService.Session session,Map<String,String> query)throws Exception{
    BusinessRecord row=store.platform.find(session.actor,query.get("id"));int offset=integer(query.get("offset"),0);
    List<Submission> history=store.platform.workflow().recordHistory(session.actor,row.id(),offset,25);var schema=DatasetSchema.get(row.dataset());
    StringBuilder b=new StringBuilder("<h1>记录追溯 · ").append(PageLayout.e(schema.value(row.values(),schema.customerColumn))).append("</h1><p>").append(PageLayout.e(Organizations.label(row.organizationId()))).append(" · ").append(PageLayout.e(row.period().key())).append(" · 正式版本 ").append(row.version()).append("</p><p class=\"business-note\">显示该记录关联的提交历史；私人草稿不公开。导入及字段修改详情沿用公共操作记录。</p><p><a class=\"btn btn-light\" href=\"/audit?category=business&amp;search=").append(PageLayout.u(row.id())).append("&amp;dataset=").append(PageLayout.u(row.dataset())).append("\">查看该记录的公共操作记录</a></p><div class=\"table-scroll\"><table class=\"data-table business-history-table\"><thead><tr><th>提交时间</th><th>提交人</th><th>处理状态</th><th>复核人</th><th>操作</th></tr></thead><tbody>");
    for(var item:history)b.append("<tr><td>").append(PageLayout.e(PageLayout.time(item.createdAt().toString()))).append("</td><td>").append(PageLayout.e(item.ownerName())).append("</td><td>").append(item.state()==State.SUBMITTED?"待复核":item.state()==State.RETURNED?"已退回":item.mode()==Mode.DIRECT?"直接生效":"已通过").append("</td><td>").append(PageLayout.e(item.reviewerName())).append("</td><td><a href=\"/workflow/submission?id=").append(PageLayout.u(item.id())).append("\">提交详情</a> · <a href=\"/audit?submissionId=").append(PageLayout.u(item.id())).append("\">公共审计</a></td></tr>");
    if(history.isEmpty())b.append("<tr><td colspan=\"5\">暂无工作流提交，导入历史请查看公共操作记录</td></tr>");
    b.append("</tbody></table></div>").append(AccessPages.pager("/records/history?id="+PageLayout.u(row.id()),offset,history.size()));
    sendHtml(x,200,new RiskPages(version,session).shell("记录追溯",b.toString()));
  }
}

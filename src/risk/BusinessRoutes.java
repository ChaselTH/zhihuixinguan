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
    if(path.equals("/records/history")){history(x,session,query);return true;}
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
      Draft active=null;boolean choose=false;
      if(session.actor.role()==Role.OPERATOR){
        var workflow=store.platform.workflow();
        if(!filter.draft.isEmpty()&&!filter.draft.equals("new")){
          active=workflow.editableDraft(session.actor,filter.draft);
          if(!active.dataset().equals(filter.dataset))throw new IllegalArgumentException("草稿与当前表种不一致");
        }else if(filter.draft.isEmpty()){
          // Resolve from the whole dataset, never just the visible page's records.
          var candidates=workflow.drafts(session.actor,filter.dataset,0,2);
          if(candidates.size()==1)active=candidates.get(0);
          else choose=candidates.size()>1;
        }
        if(active!=null)filter=filter.withDraft(active.id());
      }
      List<RowRef> selected=filter.rows(data);int pagesCount=Math.max(1,(selected.size()+filter.pageSize-1)/filter.pageSize);int page=Math.max(1,Math.min(pagesCount,integer(query.get("page"),1)));int start=(page-1)*filter.pageSize;
      html=pages.details(data,filter,page,states(session,selected.subList(start,Math.min(start+filter.pageSize,selected.size()))).editing(active,choose));
    }
    sendHtml(x,200,html);return true;
  }
  private BusinessWorkflowState states(AuthService.Session s,List<RowRef> rows){return BusinessWorkflowState.load(store.platform,s.actor,rows);}
  private void history(HttpExchange x,AuthService.Session session,Map<String,String> query)throws Exception{
    // Query the shared audit authority; never reconstruct purged audit details from snapshots.
    Map<String,String> q=new HashMap<>(query);q.put("category","business");q.put("history","1");
    ActorContext actor=session.actor;String org=q.getOrDefault("organization","").strip();
    if(!org.isEmpty())org=Organizations.resolve(org);
    if(!org.isEmpty())AccessPolicy.require(actor,AccessPolicy.Action.VIEW,org);
    if(!AccessPolicy.all(actor))org=actor.organizationId();q.put("organization",org);
    if(!q.getOrDefault("id","").isBlank()){
      BusinessRecord row=store.platform.find(actor,q.get("id"));q.put("search",row.id());q.put("dataset",row.dataset());
    }
    String dataset=q.getOrDefault("dataset","").strip();if(!dataset.isEmpty())DatasetSchema.get(dataset);
    LocalDate from=parseDate(q.get("from"),"开始日期"),through=parseDate(q.get("through"),"结束日期");
    if(from!=null&&through!=null&&from.isAfter(through))throw new IllegalArgumentException("开始日期不能晚于结束日期");
    int offset=Math.max(0,integer(q.get("offset"),0));
    var filter=new AccessPlatform.AuditFilter("business",org,dataset,limit(q.get("search"),100),from,through);
    var entries=store.platform.access().audit(actor,filter,offset,25);
    sendHtml(x,200,new AuditPages(version,session).audit(entries,q,offset));
  }
  private static LocalDate parseDate(String value,String label){if(value==null||value.isBlank())return null;try{return LocalDate.parse(value);}catch(Exception e){throw new IllegalArgumentException(label+"格式应为 YYYY-MM-DD");}}
}

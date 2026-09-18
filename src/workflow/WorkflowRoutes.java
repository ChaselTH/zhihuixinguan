import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import xinguan.platform.*;
import xinguan.platform.WorkflowContracts.*;

/**
 * B1 workflow HTTP adapter. Main owns authentication, CSRF and request-size checks;
 * this class only accepts the verified session supplied by Main and never trusts
 * actor, role or organization fields from the browser.
 */
final class WorkflowRoutes {
  private static final int EDIT_PAGE_SIZE=20;
  private static final int LIST_PAGE_SIZE=30;
  private final PlatformStore store;
  private final WorkflowService workflow;
  private final String version;

  WorkflowRoutes(PlatformStore store,String version) {
    this.store=Objects.requireNonNull(store);
    this.workflow=store.workflow();
    this.version=version==null?"":version;
  }

  boolean get(HttpExchange x,AuthService.Session session,Map<String,String> query)throws Exception {
    String path=x.getRequestURI().getPath();
    if(!Set.of("/workflow","/workflow/drafts","/workflow/edit","/workflow/preview","/workflow/submissions","/workflow/reviews","/workflow/submission").contains(path))return false;
    WorkflowPages pages=pages(session);
    try {
      switch(path) {
        case "/workflow" -> send(x,200,home(pages,session,query));
        case "/workflow/drafts" -> {
          if(session.actor.role()!=Role.OPERATOR)throw new SecurityException("只有操作员可以查看本人私人草稿");
          String dataset=clean(query.get("dataset"));if(!dataset.isEmpty())DatasetSchema.get(dataset);
          int page=positivePage(query.get("page"));
          send(x,200,pages.drafts(workflow.drafts(session.actor,empty(dataset),(page-1)*LIST_PAGE_SIZE,LIST_PAGE_SIZE),dataset,page));
        }
        case "/workflow/edit" -> send(x,200,editor(pages,session,query));
        case "/workflow/preview" -> send(x,200,pages.preview(workflow.preview(session.actor,required(query,"id")),notice(query)));
        case "/workflow/submissions" -> send(x,200,submissions(pages,session,query,false));
        case "/workflow/reviews" -> send(x,200,submissions(pages,session,query,true));
        case "/workflow/submission" -> send(x,200,pages.submission(workflow.submission(session.actor,required(query,"id")),notice(query)));
        default -> { return false; }
      }
      return true;
    } catch(WorkflowException e) { return workflowError(x,pages,e); }
      catch(SecurityException e) { send(x,403,pages.problem(403,e.getMessage(),List.of()));return true; }
      catch(IllegalArgumentException e) { send(x,400,pages.problem(400,e.getMessage(),List.of()));return true; }
  }

  boolean post(HttpExchange x,AuthService.Session session,Map<String,String> form)throws Exception {
    String path=x.getRequestURI().getPath();
    if(!Set.of("/workflow/draft/save","/workflow/direct/preview","/workflow/confirm","/workflow/review/approve","/workflow/review/reject").contains(path))return false;
    WorkflowPages pages=pages(session);
    try {
      switch(path) {
        case "/workflow/draft/save" -> saveDraft(x,pages,session,form);
        case "/workflow/direct/preview" -> directPreview(x,pages,session,form);
        case "/workflow/confirm" -> {
          Submission result=workflow.confirm(session.actor,required(form,"previewId"),required(form,"requestId"));
          send(x,200,pages.submission(result,"提交已确认；请以当前单据状态为准。"));
        }
        case "/workflow/review/approve" -> {
          Submission result=workflow.approve(session.actor,required(form,"submissionId"),required(form,"requestId"));
          send(x,200,pages.submission(result,"复核已通过，正式值、审计和通知已在同一事务中完成。"));
        }
        case "/workflow/review/reject" -> {
          Submission result=workflow.reject(session.actor,required(form,"submissionId"),form.get("reason"),required(form,"requestId"));
          send(x,200,pages.submission(result,"已退回且未修改正式值；提交人可以恢复草稿后重新提交。"));
        }
        default -> { return false; }
      }
      return true;
    } catch(WorkflowException e) { return workflowError(x,pages,e); }
      catch(SecurityException e) { send(x,403,pages.problem(403,e.getMessage(),List.of()));return true; }
      catch(IllegalArgumentException e) { send(x,400,pages.problem(400,e.getMessage(),List.of()));return true; }
  }

  private WorkflowPages pages(AuthService.Session session) {
    if(session==null)throw new SecurityException("请先登录");
    return new WorkflowPages(version,session);
  }

  private String home(WorkflowPages pages,AuthService.Session session,Map<String,String> query) {
    ActorContext actor=session.actor;
    List<Draft> drafts=actor.role()==Role.OPERATOR?workflow.drafts(actor,null,0,10):List.of();
    boolean mine=actor.role()==Role.OPERATOR||!AccessPolicy.all(actor);
    List<Submission> submissions=workflow.submissions(actor,new Query(null,null,null,null,null,mine,0,10));
    List<Submission> pending=actor.role()==Role.REVIEWER?workflow.pendingReviews(actor,Query.firstPage()):List.of();
    return pages.home(drafts,submissions,pending,notice(query));
  }

  private String editor(WorkflowPages pages,AuthService.Session session,Map<String,String> query) {
    ActorContext actor=session.actor;
    boolean draftMode=actor.role()==Role.OPERATOR;
    if(!draftMode&&!canDirect(actor))throw new SecurityException("当前角色不能填写工作流数据");
    String dataset=clean(query.getOrDefault("dataset","multi"));DatasetSchema.get(dataset);
    String organization=editableOrganization(actor,query.get("organization"));
    LocalDate from=date(query.get("from"),"开始日期"),through=date(query.get("through"),"结束日期");
    if(from!=null&&through!=null&&from.isAfter(through))throw new IllegalArgumentException("开始日期不能晚于结束日期");
    int page=positivePage(query.get("page"));
    Draft draft=null;
    String draftId=clean(query.get("draft"));
    if(!draftId.isEmpty()) {
      if(!draftMode)throw new SecurityException("只有操作员可以恢复私人草稿");
      draft=workflow.draft(actor,draftId);
      if(!draft.dataset().equals(dataset))throw new IllegalArgumentException("草稿与当前表种不一致");
      Draft requestedDraft=draft;boolean active=workflow.drafts(actor,dataset,0,100).stream().anyMatch(candidate->candidate.id().equals(requestedDraft.id())&&candidate.version()==requestedDraft.version());
      if(!active)throw new IllegalArgumentException("该草稿版本已提交并冻结，请从提交记录查看；退回后可恢复新的草稿");
    }
    String prior=clean(query.get("prior"));
    if(draft!=null&&prior.isEmpty())prior=draft.priorSubmissionId();
    verifyPrior(actor,prior,dataset);
    List<BusinessRecord> rows=organization.isEmpty()?List.of():new ArrayList<>(store.list(actor,dataset,from,through));
    if(!organization.isEmpty())rows.removeIf(row->!organization.equals(row.organizationId()));
    // Restoring a private draft is a focused view: show only rows with actual
    // saved differences. New rows are added from the unified business table.
    if(draft!=null) {
      List<BusinessRecord> draftRows=new ArrayList<>();
      for(SnapshotRow savedRow:draft.rows()) {
        BusinessRecord current=store.find(actor,savedRow.before().id());
        if(current.dataset().equals(dataset)&&current.organizationId().equals(organization))draftRows.add(current);
      }
      rows=draftRows;
    }
    String focus=clean(query.get("record"));
    if(!focus.isEmpty()){
      BusinessRecord target=store.find(actor,focus);
      if(!target.dataset().equals(dataset)||!target.organizationId().equals(organization))throw new SecurityException("记录不属于当前填报范围");
      rows.removeIf(row->!row.id().equals(focus));
      if(rows.isEmpty())throw new IllegalArgumentException("记录不在当前期次范围，请返回清单刷新");
    }
    int total=rows.size(),pagesCount=Math.max(1,(total+EDIT_PAGE_SIZE-1)/EDIT_PAGE_SIZE);
    page=Math.min(page,pagesCount);int start=(page-1)*EDIT_PAGE_SIZE;
    List<BusinessRecord> shown=rows.subList(start,Math.min(start+EDIT_PAGE_SIZE,total));
    return pages.editor(dataset,organization,from,through,page,pagesCount,total,shown,draft,prior,notice(query),focus);
  }

  private void saveDraft(HttpExchange x,WorkflowPages pages,AuthService.Session session,Map<String,String> form)throws Exception {
    if(session.actor.role()!=Role.OPERATOR)throw new SecurityException("只有操作员可以保存并提交私人草稿");
    String dataset=required(form,"dataset");DatasetSchema.get(dataset);
    Draft old=null;String id=clean(form.get("draftId"));long version=number(form.get("draftVersion"),"草稿版本");
    if(!id.isEmpty())old=workflow.draft(session.actor,id);
    List<RecordChange> pageChanges=pageChanges(session.actor,dataset,form);
    LinkedHashMap<String,RecordChange> complete=new LinkedHashMap<>();
    if(old!=null)for(SnapshotRow row:old.rows())complete.put(row.before().id(),row.change());
    for(RecordChange change:pageChanges)complete.put(change.recordId(),change);
    String prior=clean(form.get("priorSubmissionId"));if(prior.isEmpty()&&old!=null)prior=old.priorSubmissionId();
    verifyPrior(session.actor,prior,dataset);
    Draft saved=workflow.saveDraft(session.actor,id,version,dataset,List.copyOf(complete.values()),prior,required(form,"requestId"));
    if("preview".equals(form.get("intent"))) {
      Preview preview=workflow.previewDraft(session.actor,saved.id(),saved.version());
      send(x,200,pages.preview(preview,"草稿已保存，以下差异来自服务端；预览本身尚未创建待办。"));
      return;
    }
    HttpSupport.redirect(x,editUrl(form,saved.id(),"草稿已保存，版本 "+saved.version()+"；其他分页中的已保存修改仍保留。"));
  }

  private void directPreview(HttpExchange x,WorkflowPages pages,AuthService.Session session,Map<String,String> form)throws Exception {
    if(!canDirect(session.actor))throw new SecurityException("当前角色不能直接修改正式数据");
    String dataset=required(form,"dataset");DatasetSchema.get(dataset);
    Preview preview=workflow.previewDirect(session.actor,dataset,pageChanges(session.actor,dataset,form));
    send(x,200,pages.preview(preview,"以下差异来自服务端；确认前正式数据没有变化。"));
  }

  private List<RecordChange> pageChanges(ActorContext actor,String dataset,Map<String,String> form) {
    int count=bounded(form.get("rows"),0,Math.max(EDIT_PAGE_SIZE,50),"页面记录数");
    if(count<1)throw new IllegalArgumentException("当前页没有可提交的记录");
    DatasetSchema schema=DatasetSchema.get(dataset);List<RecordChange> result=new ArrayList<>();Set<String> seen=new HashSet<>();
    for(int i=0;i<count;i++) {
      String id=required(form,"id"+i);if(!seen.add(id))throw new IllegalArgumentException("页面包含重复记录，请刷新后重试");
      BusinessRecord current=store.find(actor,id);
      if(!current.dataset().equals(dataset))throw new IllegalArgumentException("表种与记录不一致");
      long expected=number(form.get("version"+i),"记录版本");Map<String,String> values=new TreeMap<>();
      for(DatasetSchema.Field field:schema.fields)if(field.editable()) {
        String key="value_"+i+"_"+field.key();if(!form.containsKey(key))throw new IllegalArgumentException("页面字段缺失，请刷新后重试");
        values.put(field.key(),form.get(key));
      }
      result.add(new RecordChange(id,expected,values));
    }
    return List.copyOf(result);
  }

  private String submissions(WorkflowPages pages,AuthService.Session session,Map<String,String> query,boolean pending) {
    ActorContext actor=session.actor;if(pending&&actor.role()!=Role.REVIEWER)throw new SecurityException("只有复核员可以查看复核待办");
    String dataset=clean(query.get("dataset"));if(!dataset.isEmpty())DatasetSchema.get(dataset);
    String org=queryOrganization(actor,query.get("organization"));
    State state=null;if(!pending&&!clean(query.get("state")).isEmpty())try{state=State.valueOf(query.get("state"));}catch(Exception e){throw new IllegalArgumentException("提交状态无效");}
    LocalDate from=date(query.get("from"),"开始日期"),through=date(query.get("through"),"结束日期");
    if(from!=null&&through!=null&&from.isAfter(through))throw new IllegalArgumentException("开始日期不能晚于结束日期");
    int page=positivePage(query.get("page"));boolean mine=!pending&&(actor.role()==Role.OPERATOR||"1".equals(query.get("mine")));
    Query request=new Query(empty(dataset),empty(org),state,from,through,mine,(page-1)*LIST_PAGE_SIZE,LIST_PAGE_SIZE);
    List<Submission> rows=pending?workflow.pendingReviews(actor,request):workflow.submissions(actor,request);
    return pages.submissions(rows,dataset,org,state,from,through,mine,page,pending,notice(query));
  }

  private void verifyPrior(ActorContext actor,String prior,String dataset) {
    if(prior.isEmpty())return;Submission old=workflow.submission(actor,prior);
    if(!old.ownerId().equals(actor.userId())||old.state()!=State.RETURNED||!old.dataset().equals(dataset)||!old.organizationId().equals(actor.organizationId()))
      throw new IllegalArgumentException("只能关联本人同机构、同表格的已退回提交单");
  }

  private String editableOrganization(ActorContext actor,String requested) {
    if(actor.role()==Role.DIVISION_ADMIN) {
      String org=clean(requested);if(org.isEmpty())return "";if(!Organizations.BRANCHES.containsKey(org))throw new IllegalArgumentException("请选择有效支行");
      AccessPolicy.require(actor,AccessPolicy.Action.DIRECT_EDIT,org);return org;
    }
    if(!Organizations.BRANCHES.containsKey(actor.organizationId()))throw new SecurityException("当前账号没有可填写的支行");
    if(!clean(requested).isEmpty()&&!actor.organizationId().equals(clean(requested)))throw new SecurityException("不能选择其他支行的填报数据");
    AccessPolicy.require(actor,actor.role()==Role.OPERATOR?AccessPolicy.Action.SAVE_DRAFT:AccessPolicy.Action.DIRECT_EDIT,actor.organizationId());
    return actor.organizationId();
  }

  private String queryOrganization(ActorContext actor,String requested) {
    String org=clean(requested);if(org.isEmpty())return "";
    if(!Organizations.BRANCHES.containsKey(org))throw new IllegalArgumentException("查询机构无效");
    AccessPolicy.require(actor,AccessPolicy.Action.VIEW,org);return org;
  }

  private static boolean canDirect(ActorContext actor) {
    return actor.role()==Role.DIVISION_ADMIN||actor.role()==Role.BRANCH_ADMIN||actor.role()==Role.REVIEWER;
  }

  private static LocalDate date(String value,String label) {
    String clean=clean(value);if(clean.isEmpty())return null;
    try{return LocalDate.parse(clean);}catch(DateTimeParseException e){throw new IllegalArgumentException(label+"格式应为 YYYY-MM-DD");}
  }

  private static int positivePage(String value) {int page=HttpSupport.integer(value,1);return Math.max(1,Math.min(page,50000));}
  private static int bounded(String value,int minimum,int maximum,String label) {int n=HttpSupport.integer(value,-1);if(n<minimum||n>maximum)throw new IllegalArgumentException(label+"无效");return n;}
  private static long number(String value,String label) {try{return Long.parseLong(value);}catch(Exception e){throw new IllegalArgumentException(label+"无效，请刷新后重试");}}
  private static String required(Map<String,String> values,String key) {String value=clean(values.get(key));if(value.isEmpty())throw new IllegalArgumentException("缺少必要参数，请刷新后重试");return value;}
  private static String clean(String value) {return value==null?"":value.strip();}
  private static String empty(String value) {return value==null||value.isEmpty()?null:value;}
  private static String notice(Map<String,String> values) {return HttpSupport.limit(values.get("notice"),300);}

  private static String editUrl(Map<String,String> form,String draft,String notice) {
    StringBuilder url=new StringBuilder("/workflow/edit?dataset=").append(HttpSupport.url(form.getOrDefault("dataset","multi")));
    for(String key:List.of("organization","from","through","page","record"))if(!clean(form.get(key)).isEmpty())url.append('&').append(key).append('=').append(HttpSupport.url(form.get(key)));
    url.append("&draft=").append(HttpSupport.url(draft)).append("&notice=").append(HttpSupport.url(notice));return url.toString();
  }

  private boolean workflowError(HttpExchange x,WorkflowPages pages,WorkflowException e)throws IOException {
    int status=switch(e.code()) {
      case NOT_FOUND -> 404;
      case INVALID_INPUT,NO_REVIEWER,OWNER_CHANGED -> 400;
      case TRANSACTION_FAILED -> 500;
      default -> 409;
    };
    send(x,status,pages.problem(status,e.getMessage(),e.conflicts()));return true;
  }
  private static void send(HttpExchange x,int status,String html)throws IOException {HttpSupport.sendHtml(x,status,html);}
}

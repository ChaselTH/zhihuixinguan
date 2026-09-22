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
    if(!Set.of("/workflow","/workflow/drafts","/workflow/edit","/workflow/preview","/workflow/submissions","/workflow/reviews","/workflow/submission","/workflow/record-history","/workflow/reopen","/workflow/reconfirm").contains(path))return false;
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
        case "/workflow/record-history" -> {
          BusinessRecord row=store.find(session.actor,required(query,"record"));
          send(x,200,pages.recordHistory(row,workflow.recordEvents(session.actor,row.id(),0,100),workflow.recordHistory(session.actor,row.id(),0,50)));
        }
        case "/workflow/reopen" -> {AccessPolicy.require(session.actor,AccessPolicy.Action.DIVISION_REVIEW,Organizations.DIVISION);BusinessRecord row=store.find(session.actor,required(query,"record"));if((row.workflowStage()==RowStage.PUBLISHED||row.workflowStage()==RowStage.LEGACY_PUBLISHED)&&!store.completionRules().visible(session.actor).get(row.dataset()).complete(row.values()))throw new IllegalArgumentException("该行尚未满足正式完成规则，无需执行终审重开");if(row.workflowStage()!=RowStage.PUBLISHED&&row.workflowStage()!=RowStage.LEGACY_PUBLISHED&&row.workflowStage()!=RowStage.RETURNED)throw new IllegalArgumentException("只能查看正式已完成或退回的记录");send(x,200,pages.reopen(row,notice(query)));}
        case "/workflow/reconfirm" -> {BusinessRecord row=store.find(session.actor,required(query,"record"));if(!canEditStage(session.actor,row)||row.workflowStage()!=RowStage.RETURNED)throw new SecurityException("该记录当前不在可核对的退回范围");send(x,200,pages.reconfirm(row));}
        default -> { return false; }
      }
      return true;
    } catch(WorkflowException e) { return workflowError(x,pages,e); }
      catch(SecurityException e) { send(x,403,pages.problem(403,e.getMessage(),List.of()));return true; }
      catch(IllegalArgumentException e) { send(x,400,pages.problem(400,e.getMessage(),List.of()));return true; }
  }

  boolean post(HttpExchange x,AuthService.Session session,Map<String,String> form)throws Exception {
    String path=x.getRequestURI().getPath();
    if(!Set.of("/workflow/draft/save","/workflow/direct/preview","/workflow/confirm","/workflow/review/approve","/workflow/review/reject","/workflow/reopen","/workflow/reconfirm","/workflow/returned/restore").contains(path))return false;
    WorkflowPages pages=pages(session);
    try {
      switch(path) {
        case "/workflow/draft/save" -> saveDraft(x,pages,session,form);
        case "/workflow/direct/preview" -> directPreview(x,pages,session,form);
        case "/workflow/reconfirm" -> {
          if(!"yes".equals(form.get("confirm")))throw new IllegalArgumentException("请明确确认已核对原内容");
          Preview preview=workflow.previewReturned(session.actor,required(form,"recordId"),number(form.get("expectedVersion"),"正式版本"));
          send(x,200,pages.preview(preview,"本次为退回任务的原值重新确认，不修改字段；最终确认后生成新一轮可追溯审批。"));
        }
        case "/workflow/returned/restore" -> {
          Draft draft=workflow.restoreReturned(session.actor,required(form,"submissionId"),recordIds(required(form,"recordIds")),required(form,"requestId"));
          HttpSupport.redirect(x,"/workflow/edit?dataset="+HttpSupport.url(draft.dataset())+"&draft="+HttpSupport.url(draft.id()));
        }
        case "/workflow/confirm" -> {
          Submission result=workflow.confirm(session.actor,required(form,"previewId"),required(form,"requestId"));
          send(x,200,pages.submission(result,"提交已确认；请以当前单据状态为准。"));
        }
        case "/workflow/review/approve" -> {
          requireReviewer(session.actor);
          Submission result=workflow.approveRows(session.actor,required(form,"submissionId"),recordIds(required(form,"recordIds")),required(form,"requestId"));
          send(x,200,pages.submission(result,session.actor.role()==Role.DIVISION_ADMIN?"分行终审已通过，正式值、状态、审计和通知已在同一事务中发布。":"支行复核已通过并提交分行；正式值尚未改变。"));
        }
        case "/workflow/review/reject" -> {
          requireReviewer(session.actor);
          Submission result=workflow.rejectRows(session.actor,required(form,"submissionId"),recordIds(required(form,"recordIds")),form.get("reason"),required(form,"requestId"));
          send(x,200,pages.submission(result,"已退回且未修改正式值；提交人可以恢复草稿后重新提交。"));
        }
        case "/workflow/reopen" -> {
          AccessPolicy.require(session.actor,AccessPolicy.Action.DIVISION_REVIEW,Organizations.DIVISION);
          if(!"yes".equals(form.get("confirm")))throw new IllegalArgumentException("请勾选确认，仅重新打开所选记录");
          String recordId=required(form,"recordId");BusinessRecord row=store.find(session.actor,recordId);
          workflow.reopenCompleted(session.actor,recordId,number(form.get("expectedVersion"),"正式版本"),form.get("reason"),required(form,"requestId"));
          HttpSupport.redirect(x,"/workflow/reopen?record="+HttpSupport.url(recordId)+"&notice="+HttpSupport.url("已退回支行待处理；正式值和正式版本保持不变。"));
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
    List<Submission> pending=actor.role()==Role.REVIEWER?workflow.pendingReviews(actor,Query.firstPage()):actor.role()==Role.DIVISION_ADMIN?workflow.pendingDivisionReviews(actor,Query.firstPage()):List.of();
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
      draft=workflow.editableDraft(actor,draftId);
      if(!draft.dataset().equals(dataset))throw new IllegalArgumentException("草稿与当前表种不一致");
    }
    String prior=clean(query.get("prior"));
    if(draft!=null&&prior.isEmpty())prior=draft.priorSubmissionId();
    verifyPrior(actor,prior,dataset);
    List<BusinessRecord> rows=organization.isEmpty()?List.of():new ArrayList<>(store.list(actor,dataset,from,through));
    if(!organization.isEmpty())rows.removeIf(row->!organization.equals(row.organizationId()));
    rows.removeIf(row->!canEditStage(actor,row));
    // Restoring a private draft is a focused view: show only rows with actual
    // saved differences. New rows are added from the unified business table.
    if(draft!=null) {
      List<BusinessRecord> draftRows=new ArrayList<>();
      for(SnapshotRow savedRow:draft.rows()) {
        BusinessRecord current=store.find(actor,savedRow.before().id());
        if(current.dataset().equals(dataset)&&current.organizationId().equals(organization)&&canEditStage(actor,current))draftRows.add(current);
      }
      rows=draftRows;
    }
    String focus=clean(query.get("record"));
    if(!focus.isEmpty()){
      BusinessRecord target=store.find(actor,focus);
      if(!target.dataset().equals(dataset)||!target.organizationId().equals(organization))throw new SecurityException("记录不属于当前填报范围");
      if(!canEditStage(actor,target))throw new SecurityException("记录当前不可编辑，请从待处理清单进入");
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
    List<RecordChange> pageChanges=pageChanges(session.actor,dataset,form,old);
    LinkedHashMap<String,RecordChange> complete=new LinkedHashMap<>();
    if(old!=null)for(SnapshotRow row:old.rows())complete.put(row.before().id(),row.change());
    for(RecordChange change:pageChanges)complete.put(change.recordId(),change);
    String prior=clean(form.get("priorSubmissionId"));if(prior.isEmpty()&&old!=null)prior=old.priorSubmissionId();
    verifyPrior(session.actor,prior,dataset);
    Draft saved=workflow.saveDraft(session.actor,id,version,dataset,List.copyOf(complete.values()),prior,required(form,"requestId"));
    if("preview".equals(form.get("intent"))) {
      Preview preview=workflow.previewDraft(session.actor,saved.id(),saved.version());
      send(x,200,pages.preview(preview,"草稿已保存，以下为本草稿全部差异（含其他分页／筛选范围）；预览尚未创建待办。",editUrl(form,saved.id(),"")));
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
    return pageChanges(actor,dataset,form,null);
  }
  private List<RecordChange> pageChanges(ActorContext actor,String dataset,Map<String,String> form,Draft draft) {
    int count=bounded(form.get("rows"),0,Math.max(EDIT_PAGE_SIZE,50),"页面记录数");
    if(count<1&&draft==null)throw new IllegalArgumentException("当前页没有可提交的记录");
    boolean sparse="differences".equals(form.get("fieldsMode"));
    if(sparse&&draft==null)throw new IllegalArgumentException("差异编辑需要指定本人草稿");
    DatasetSchema schema=DatasetSchema.get(dataset);List<RecordChange> result=new ArrayList<>();Set<String> seen=new HashSet<>();
    for(int i=0;i<count;i++) {
      String id=required(form,"id"+i);if(!seen.add(id))throw new IllegalArgumentException("页面包含重复记录，请刷新后重试");
      BusinessRecord current=store.find(actor,id);
      if(!current.dataset().equals(dataset))throw new IllegalArgumentException("表种与记录不一致");
      long expected=number(form.get("version"+i),"记录版本");Map<String,String> values=new TreeMap<>();
      SnapshotRow saved=sparse?draft.rows().stream().filter(row->row.before().id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("记录不属于当前草稿")):null;
      for(DatasetSchema.Field field:schema.fields)if(field.editable()) {
        if(sparse&&!saved.change().values().isEmpty()&&!saved.change().values().containsKey(field.key()))continue;
        String key="value_"+i+"_"+field.key();if(!form.containsKey(key))throw new IllegalArgumentException("页面字段缺失，请刷新后重试");
        values.put(field.key(),form.get(key));
      }
      result.add(new RecordChange(id,expected,values));
    }
    return List.copyOf(result);
  }

  private String submissions(WorkflowPages pages,AuthService.Session session,Map<String,String> query,boolean pending) {
    ActorContext actor=session.actor;if(pending&&actor.role()!=Role.REVIEWER&&actor.role()!=Role.DIVISION_ADMIN)throw new SecurityException("当前角色没有审核待办");
    String dataset=clean(query.get("dataset"));if(!dataset.isEmpty())DatasetSchema.get(dataset);
    String org=queryOrganization(actor,query.get("organization"));
    State state=null;if(!pending&&!clean(query.get("state")).isEmpty())try{state=State.valueOf(query.get("state"));}catch(Exception e){throw new IllegalArgumentException("提交状态无效");}
    LocalDate from=date(query.get("from"),"开始日期"),through=date(query.get("through"),"结束日期");
    if(from!=null&&through!=null&&from.isAfter(through))throw new IllegalArgumentException("开始日期不能晚于结束日期");
    int page=positivePage(query.get("page"));boolean mine=!pending&&(actor.role()==Role.OPERATOR||"1".equals(query.get("mine")));
    Query request=new Query(empty(dataset),empty(org),state,from,through,mine,(page-1)*LIST_PAGE_SIZE,LIST_PAGE_SIZE);
    List<Submission> rows=pending?(actor.role()==Role.DIVISION_ADMIN?workflow.pendingDivisionReviews(actor,request):workflow.pendingReviews(actor,request)):workflow.submissions(actor,request);
    return pages.submissions(rows,dataset,org,state,from,through,mine,page,pending,notice(query));
  }

  private void verifyPrior(ActorContext actor,String prior,String dataset) {
    if(prior.isEmpty())return;Submission old=workflow.submission(actor,prior);
    if(!old.rowStages().containsValue(RowStage.RETURNED)||!old.dataset().equals(dataset)||!old.organizationId().equals(actor.organizationId()))
      throw new IllegalArgumentException("只能关联本机构同表格的已退回业务行");
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

  private boolean canEditStage(ActorContext actor,BusinessRecord row) {
    RowStage stage=row.workflowStage();if(stage==RowStage.BRANCH_REVIEW||stage==RowStage.DIVISION_REVIEW)return false;
    if(actor.role()==Role.DIVISION_ADMIN)return true;
    if(actor.role()==Role.OPERATOR||actor.role()==Role.BRANCH_ADMIN||actor.role()==Role.REVIEWER) {
      if(stage==RowStage.READY||stage==RowStage.RETURNED)return true;
      if(stage==RowStage.PUBLISHED||stage==RowStage.LEGACY_PUBLISHED)return !store.completionRules().visible(actor).get(row.dataset()).complete(row.values());
    }
    return false;
  }

  private static void requireReviewer(ActorContext actor) {
    if(actor.role()==Role.DIVISION_ADMIN)AccessPolicy.require(actor,AccessPolicy.Action.DIVISION_REVIEW,Organizations.DIVISION);
    else if(actor.role()==Role.REVIEWER)AccessPolicy.require(actor,AccessPolicy.Action.REVIEW,actor.organizationId());
    else throw new SecurityException("当前账号无权审核填报");
  }

  private static LocalDate date(String value,String label) {
    String clean=clean(value);if(clean.isEmpty())return null;
    try{return LocalDate.parse(clean);}catch(DateTimeParseException e){throw new IllegalArgumentException(label+"格式应为 YYYY-MM-DD");}
  }

  private static int positivePage(String value) {int page=HttpSupport.integer(value,1);return Math.max(1,Math.min(page,50000));}
  private static int bounded(String value,int minimum,int maximum,String label) {int n=HttpSupport.integer(value,-1);if(n<minimum||n>maximum)throw new IllegalArgumentException(label+"无效");return n;}
  private static long number(String value,String label) {try{return Long.parseLong(value);}catch(Exception e){throw new IllegalArgumentException(label+"无效，请刷新后重试");}}
  private static String required(Map<String,String> values,String key) {String value=clean(values.get(key));if(value.isEmpty())throw new IllegalArgumentException("缺少必要参数，请刷新后重试");return value;}
  private static List<String> recordIds(String value){List<String> ids=Arrays.stream(value.split(",",-1)).map(String::strip).toList();if(ids.stream().anyMatch(String::isEmpty))throw new IllegalArgumentException("审核记录范围无效，请刷新页面后重新选择");return ids;}
  private static String clean(String value) {return value==null?"":value.strip();}
  private static String empty(String value) {return value==null||value.isEmpty()?null:value;}
  private static String notice(Map<String,String> values) {return HttpSupport.limit(values.get("notice"),300);}

  private static String editUrl(Map<String,String> form,String draft,String notice) {
    if("details".equals(form.get("view")))return detailsUrl(form,draft)+"&notice="+HttpSupport.url(notice);
    StringBuilder url=new StringBuilder("/workflow/edit?dataset=").append(HttpSupport.url(form.getOrDefault("dataset","multi")));
    for(String key:List.of("organization","from","through","page","record"))if(!clean(form.get(key)).isEmpty())url.append('&').append(key).append('=').append(HttpSupport.url(form.get(key)));
    url.append("&draft=").append(HttpSupport.url(draft)).append("&notice=").append(HttpSupport.url(notice));return url.toString();
  }
  static String detailsUrl(Map<String,String> form,String draft){
    StringBuilder url=new StringBuilder("/details?dataset=").append(HttpSupport.url(form.getOrDefault("dataset","multi")));
    for(String key:List.of("scope","month","year","quarter","start","end","branch","q","completion","period","pageSize","page"))
      if(!clean(form.get(key)).isEmpty())url.append('&').append(key).append('=').append(HttpSupport.url(form.get(key)));
    if(!clean(draft).isEmpty())url.append("&draft=").append(HttpSupport.url(draft));return url.toString();
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

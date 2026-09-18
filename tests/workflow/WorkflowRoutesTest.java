import com.sun.net.httpserver.*;
import java.io.*;
import java.lang.reflect.Constructor;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.Principal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.*;
import xinguan.platform.*;
import xinguan.platform.WorkflowContracts.*;

/** Real H2 workflow-route test with synthetic users and records; Main integration remains A-owned. */
public final class WorkflowRoutesTest {
  static int assertions;
  static PlatformStore store;
  static WorkflowRoutes routes;
  static MutableClock clock=new MutableClock(Instant.parse("2026-09-15T02:00:00Z"));
  static AtomicReference<String> failAt=new AtomicReference<>();
  static UserAccount superUser,division,branchAdmin,operator,otherOperator,reviewer,jintanOperator;
  static AuthService.Session superSession,divisionSession,branchSession,operatorSession,otherSession,reviewerSession,jintanSession;

  public static void main(String[] args)throws Exception {
    Path data=Files.createTempDirectory("zhihuixinguan-workflow-http-");
    try(PlatformStore opened=open(data)) {
      store=opened;setup();routes=new WorkflowRoutes(store,"0.3.0-a1b1.1");run();integrationRegressions();
      if(args.length==2&&"--fixtures".equals(args[0]))writeFixtures(Path.of(args[1]));
      System.out.println("WORKFLOW_ROUTES_OK assertions="+assertions+" real synthetic identities, H2 transactions and captured HTML forms");
    }
  }

  static void setup() {
    store.bootstrapSuperAdmin("910000001","Super-Workflow-2026!");
    superUser=store.authenticateUser("910000001","Super-Workflow-2026!");
    division=ready("910000002","测试分行管理员",Role.DIVISION_ADMIN,Organizations.DIVISION);
    branchAdmin=ready("910000003","测试武进管理员",Role.BRANCH_ADMIN,"WUJIN");
    operator=ready("910000004","测试武进操作员",Role.OPERATOR,"WUJIN");
    otherOperator=ready("910000005","同支行另一操作员",Role.OPERATOR,"WUJIN");
    reviewer=ready("910000006","测试武进复核员",Role.REVIEWER,"WUJIN");
    jintanOperator=ready("910000007","测试金坛操作员",Role.OPERATOR,"JINTAN");
    superSession=session(superUser,"super");divisionSession=session(division,"division");branchSession=session(branchAdmin,"branch");operatorSession=session(operator,"operator");otherSession=session(otherOperator,"other");reviewerSession=session(reviewer,"reviewer");jintanSession=session(jintanOperator,"jintan");
    List<BusinessRecord> rows=new ArrayList<>();
    for(int i=0;i<24;i++)rows.add(record("WUJIN",i,i==0?"虚构<script>客户</script>":"虚构武进客户 "+i));
    rows.add(record("JINTAN",100,"虚构金坛客户"));
    store.importRows(division.actor(),"multi",rows,false,id());
  }

  static void run()throws Exception {
    Exchange home=get(operatorSession,"/workflow");check(home.status==200&&home.body().contains("工作流工作台")&&home.body().contains("/assets/workflow.css"),"operator workflow home and dedicated stylesheet");
    Exchange edit=get(operatorSession,"/workflow/edit?dataset=multi&from=2026-09-01&through=2026-09-30");
    check(edit.status==200&&edit.body().contains("保存草稿")&&edit.body().contains("value=\"preview\"")&&edit.body().contains("第 1 / 2 页"),"server-rendered editor has unified draft actions and pagination");
    check(!edit.body().contains("name=\"actor\"")&&!edit.body().contains("name=\"role\""),"form never accepts browser actor or role claims");

    List<BusinessRecord> wujin=store.list(operator.actor(),"multi",null,null);BusinessRecord first=wujin.get(0),second=wujin.get(1),third=wujin.get(2),fourth=wujin.get(3),fifth=wujin.get(4),sixth=wujin.get(5),seventh=wujin.get(6);
    int escapedIndex=0;for(int i=0;i<wujin.size();i++)if(wujin.get(i).values().get(DatasetSchema.get("multi").customerColumn).contains("<script>")){escapedIndex=i;break;}
    Exchange escapedPage=get(operatorSession,"/workflow/edit?dataset=multi&from=2026-09-01&through=2026-09-30&page="+(escapedIndex/20+1));check(escapedPage.body().contains("虚构&lt;script&gt;客户&lt;/script&gt;")&&!escapedPage.body().contains("<script>客户</script>"),"source text is HTML escaped on its actual page");
    Map<String,String> firstSave=draftForm(operatorSession,first,"私人草稿 <img src=x>","","0","","save",id());
    Exchange saved=post(operatorSession,"/workflow/draft/save",firstSave);check(saved.status==303,"new private draft saves with redirect");
    Draft draft=store.workflow().drafts(operator.actor(),"multi",0,10).get(0);check(draft.rows().size()==1&&after(draft,first.id()).contains("<img"),"draft retains synthetic private value");
    Map<String,String> reused=new LinkedHashMap<>(firstSave);reused.put("value_0_feedback","different payload");Exchange reuse=post(operatorSession,"/workflow/draft/save",reused);check(reuse.status==409&&reuse.body().contains("请求编号"),"changed payload cannot reuse request id");

    Map<String,String> secondSave=draftForm(operatorSession,second,"第二页虚构填写",draft.id(),Long.toString(draft.version()),"","save",id());
    check(post(operatorSession,"/workflow/draft/save",secondSave).status==303,"second page draft save succeeds");draft=store.workflow().draft(operator.actor(),draft.id());
    check(draft.rows().size()==2&&after(draft,first.id()).contains("<img")&&after(draft,second.id()).equals("第二页虚构填写"),"saving another page preserves complete prior draft differences");
    Exchange privateRead=get(otherSession,"/workflow/edit?dataset=multi&draft="+draft.id());check(privateRead.status==403&&!privateRead.body().contains("私人草稿"+" <img"),"same-branch peer cannot read private draft body");
    BusinessRecord jintan=store.list(jintanOperator.actor(),"multi",null,null).get(0);Map<String,String> forged=draftForm(operatorSession,jintan,"越权",draft.id(),Long.toString(draft.version()),"","save",id());
    check(post(operatorSession,"/workflow/draft/save",forged).status==403,"cross-branch record id tampering is rejected");

    Map<String,String> previewSave=draftForm(operatorSession,first,"私人草稿 <img src=x>",draft.id(),Long.toString(draft.version()),"","preview",id());
    Exchange previewPage=post(operatorSession,"/workflow/draft/save",previewSave);check(previewPage.status==200&&previewPage.body().contains("服务端")&&previewPage.body().contains("&lt;img src=x&gt;")&&!previewPage.body().contains("name=\"value_"),"preview renders escaped server diff and confirmation form carries no replacement values");
    String previewId=hidden(previewPage.body(),"previewId");check(!previewId.isEmpty()&&previewPage.body().contains("name=\"csrf\"")&&previewPage.body().contains("name=\"requestId\""),"preview confirmation contains csrf, preview id and stable request id");
    Exchange restoredPreview=get(operatorSession,"/workflow/preview?id="+previewId);check(hidden(previewPage.body(),"requestId").equals(hidden(restoredPreview.body(),"requestId")),"restoring the same preview preserves confirmation request id");
    String confirmRequest=id();Exchange submittedPage=post(operatorSession,"/workflow/confirm",Map.of("previewId",previewId,"requestId",confirmRequest,"csrf",operatorSession.csrf));
    check(submittedPage.status==200&&submittedPage.body().contains("待复核"),"operator confirmation creates pending immutable submission");
    Submission submission=store.workflow().submissions(operator.actor(),new Query(null,null,null,null,null,true,0,20)).get(0);
    check(store.find(operator.actor(),first.id()).values().get(DatasetSchema.get("multi").index("feedback")).isBlank(),"draft and pending submission do not change official value");
    Draft latest=store.workflow().draft(operator.actor(),draft.id());Map<String,String> later=draftForm(operatorSession,first,"提交后的后续草稿",latest.id(),Long.toString(latest.version()),"","save",id());check(post(operatorSession,"/workflow/draft/save",later).status==409&&get(operatorSession,"/workflow/edit?dataset=multi&draft="+draft.id()).status==400,"submitted draft version is frozen and old editor link is safe");
    check(after(store.workflow().submission(operator.actor(),submission.id()),first.id()).contains("<img"),"later draft edit cannot mutate submitted snapshot");

    Exchange pending=get(reviewerSession,"/workflow/reviews");check(pending.status==200&&pending.body().contains(shortIdText(submission.id()))&&pending.body().contains("复核待办"),"reviewer sees own-branch pending queue");
    Exchange detail=get(reviewerSession,"/workflow/submission?id="+submission.id());check(detail.status==200&&detail.body().contains("/workflow/review/approve")&&detail.body().contains("/workflow/review/reject")&&detail.body().contains("approve-"+submission.id())&&detail.body().contains("reject-"+submission.id()),"reviewer sees whole-submission forms with stable action request ids");
    String approvalRequest=id();Exchange approved=post(reviewerSession,"/workflow/review/approve",Map.of("submissionId",submission.id(),"requestId",approvalRequest,"csrf",reviewerSession.csrf));
    check(approved.status==200&&approved.body().contains("已通过"),"review approval succeeds");
    check(post(reviewerSession,"/workflow/review/approve",Map.of("submissionId",submission.id(),"requestId",approvalRequest,"csrf",reviewerSession.csrf)).status==200,"same approval request is idempotent");
    Exchange decided=post(reviewerSession,"/workflow/review/approve",Map.of("submissionId",submission.id(),"requestId",id(),"csrf",reviewerSession.csrf));check(decided.status==409&&decided.body().contains("已经处理"),"second reviewer decision reports already handled");
    check(store.find(operator.actor(),first.id()).values().get(DatasetSchema.get("multi").index("feedback")).contains("<img"),"approved value becomes official exactly once");

    Submission returned=submit(operatorSession,third,"待退回内容","");String reason="请补充 <核验> 原因";
    Exchange returnedPage=post(reviewerSession,"/workflow/review/reject",Map.of("submissionId",returned.id(),"requestId",id(),"reason",reason,"csrf",reviewerSession.csrf));check(returnedPage.status==200&&returnedPage.body().contains("请补充 &lt;核验&gt; 原因"),"required reject reason is escaped and persisted");
    Exchange ownerReturned=get(operatorSession,"/workflow/submission?id="+returned.id());check(ownerReturned.status==200&&ownerReturned.body().contains("恢复草稿并修订"),"returned owner receives draft revision link");
    Draft returnedDraft=store.workflow().draft(operator.actor(),returned.draftId());Map<String,String> resubmit=draftForm(operatorSession,third,"退回后修订内容",returnedDraft.id(),Long.toString(returnedDraft.version()),returned.id(),"preview",id());
    Exchange rePreview=post(operatorSession,"/workflow/draft/save",resubmit);Submission resubmitted=confirm(operatorSession,hidden(rePreview.body(),"previewId"));
    check(resubmitted.priorSubmissionId().equals(returned.id())&&store.workflow().submission(operator.actor(),returned.id()).state()==State.RETURNED,"resubmission links old returned submission without rewriting history");

    for(AuthService.Session directSession:List.of(divisionSession,branchSession,reviewerSession)) {
      BusinessRecord current=store.find(directSession.actor,fourth.id());Map<String,String> direct=directForm(directSession,current,"直接修改预览 "+directSession.actor.role());Exchange directPage=post(directSession,"/workflow/direct/preview",direct);
      check(directPage.status==200&&directPage.body().contains("直接生效")&&directPage.body().contains("确认前正式数据没有变化"),directSession.actor.role()+" can preview direct change");
    }
    check(post(superSession,"/workflow/direct/preview",directForm(superSession,fourth,"超管越权")).status==403,"super administrator cannot use direct edit entry");
    check(post(operatorSession,"/workflow/direct/preview",directForm(operatorSession,fourth,"操作员越权")).status==403,"operator cannot bypass review with direct entry");

    Exchange noReviewerPreview=post(jintanSession,"/workflow/draft/save",draftForm(jintanSession,jintan,"金坛待提交","","0","","preview",id()));String noReviewerId=hidden(noReviewerPreview.body(),"previewId");
    Exchange noReviewer=post(jintanSession,"/workflow/confirm",Map.of("previewId",noReviewerId,"requestId",id(),"csrf",jintanSession.csrf));check(noReviewer.status==400&&noReviewer.body().contains("尚无有效复核员")&&store.workflow().drafts(jintanOperator.actor(),"multi",0,10).size()==1,"missing reviewer reports actionable error and retains draft");

    Exchange stalePreviewPage=post(operatorSession,"/workflow/draft/save",draftForm(operatorSession,fifth,"旧预览内容","","0","","preview",id()));String stalePreviewId=hidden(stalePreviewPage.body(),"previewId");Draft staleDraft=store.workflow().drafts(operator.actor(),"multi",0,20).stream().filter(d->after(d,fifth.id()).equals("旧预览内容")).findFirst().orElseThrow();
    check(post(operatorSession,"/workflow/draft/save",draftForm(operatorSession,fifth,"预览后新内容",staleDraft.id(),Long.toString(staleDraft.version()),"","save",id())).status==303,"draft changes after preview");
    Exchange staleConfirm=post(operatorSession,"/workflow/confirm",Map.of("previewId",stalePreviewId,"requestId",id(),"csrf",operatorSession.csrf));check(staleConfirm.status==409&&staleConfirm.body().contains("预览后草稿已变化"),"stale draft preview cannot be confirmed");

    Exchange expiryPage=post(operatorSession,"/workflow/draft/save",draftForm(operatorSession,sixth,"即将过期","","0","","preview",id()));String expiryId=hidden(expiryPage.body(),"previewId");clock.advance(Duration.ofMinutes(16));
    Exchange expired=post(operatorSession,"/workflow/confirm",Map.of("previewId",expiryId,"requestId",id(),"csrf",operatorSession.csrf));check(expired.status==409&&expired.body().contains("超过 15 分钟"),"expired confirmation asks for a new server preview");

    BusinessRecord faultRecord=store.find(reviewer.actor(),seventh.id());Exchange faultPreview=post(reviewerSession,"/workflow/direct/preview",directForm(reviewerSession,faultRecord,"故障后不得部分写入"));String faultPreviewId=hidden(faultPreview.body(),"previewId"),faultRequest=id();failAt.set("confirmation-complete");
    Exchange failed=post(reviewerSession,"/workflow/confirm",Map.of("previewId",faultPreviewId,"requestId",faultRequest,"csrf",reviewerSession.csrf));failAt.set(null);
    check(failed.status==500&&failed.body().contains("事务未完成")&&store.find(reviewer.actor(),seventh.id()).values().get(DatasetSchema.get("multi").index("feedback")).isBlank(),"transaction failure is explicit and leaves official value unchanged");
    Exchange retried=post(reviewerSession,"/workflow/confirm",Map.of("previewId",faultPreviewId,"requestId",faultRequest,"csrf",reviewerSession.csrf));check(retried.status==200&&store.find(reviewer.actor(),seventh.id()).values().get(DatasetSchema.get("multi").index("feedback")).equals("故障后不得部分写入"),"uncertain direct confirmation safely retries with original request id");
  }

  static Submission submit(AuthService.Session session,BusinessRecord record,String value,String prior)throws Exception {
    Exchange page=post(session,"/workflow/draft/save",draftForm(session,record,value,"","0",prior,"preview",id()));return confirm(session,hidden(page.body(),"previewId"));
  }

  static void integrationRegressions()throws Exception {
    BusinessRecord pageRecord=store.list(operator.actor(),"multi",null,null).get(0);String field="feedback";
    for(int i=0;i<35;i++)store.workflow().saveDraft(operator.actor(),"",0,"multi",List.of(new RecordChange(pageRecord.id(),pageRecord.version(),Map.of(field,"分页草稿 "+i))),"",id());
    for(int i=35;i<136;i++)store.workflow().saveDraft(operator.actor(),"",0,"multi",List.of(new RecordChange(pageRecord.id(),pageRecord.version(),Map.of(field,"分页草稿 "+i))),"",id());
    var all=store.workflow().drafts(operator.actor(),"multi",0,100);var tail=store.workflow().drafts(operator.actor(),"multi",100,100);
    String page1=get(operatorSession,"/workflow/drafts?dataset=multi").body(),page2=get(operatorSession,"/workflow/drafts?dataset=multi&page=2").body();
    check(all.size()==100&&tail.size()>=36&&page1.contains(all.get(0).id())&&page1.contains("下一页")&&page2.contains(all.get(34).id()),"all drafts reachable beyond first ten and first page");
    Draft deep=tail.get(0);check(get(operatorSession,"/workflow/edit?dataset=multi&organization=WUJIN&draft="+deep.id()).status==200,"draft recovery does not depend on first 100 active versions");
    String currentFeedback=DatasetSchema.get("multi").value(pageRecord.values(),DatasetSchema.get("multi").index(field));
    Draft empty=store.workflow().saveDraft(operator.actor(),"",0,"multi",List.of(new RecordChange(pageRecord.id(),pageRecord.version(),Map.of(field,currentFeedback))),"",id());
    check(empty.rows().isEmpty()&&!store.workflow().drafts(operator.actor(),"multi",0,100).stream().anyMatch(d->d.id().equals(empty.id())),"zero-difference draft leaves active list");
    check(!get(otherSession,"/workflow/drafts?dataset=multi").body().contains(all.get(0).id()),"draft pagination remains owner-only");
    check(get(superSession,"/workflow/drafts").status==403,"admin cannot list private drafts");
    check(get(operatorSession,"/workflow/edit?dataset=multi&organization=JINTAN").status==403,"editor rejects forged organization filter");
    check(!get(reviewerSession,"/workflow/reviews").body().contains("<option value=\"APPROVED\""),"pending filters do not emit orphan state options");
  }
  static Submission confirm(AuthService.Session session,String previewId)throws Exception {
    Exchange result=post(session,"/workflow/confirm",Map.of("previewId",previewId,"requestId",id(),"csrf",session.csrf));check(result.status==200,"submission confirmation succeeds");
    Matcher matcher=Pattern.compile("单号 ([A-Za-z0-9-]{10,})").matcher(result.body());check(matcher.find(),"confirmed page exposes stable submission id");return store.workflow().submission(session.actor,matcher.group(1));
  }

  static Map<String,String> draftForm(AuthService.Session session,BusinessRecord record,String feedback,String draftId,String draftVersion,String prior,String intent,String requestId) {
    Map<String,String> form=recordForm(session,record,feedback);form.put("draftId",draftId);form.put("draftVersion",draftVersion);form.put("priorSubmissionId",prior);form.put("intent",intent);form.put("requestId",requestId);return form;
  }
  static Map<String,String> directForm(AuthService.Session session,BusinessRecord record,String feedback){Map<String,String> form=recordForm(session,record,feedback);form.put("requestId",id());return form;}
  static Map<String,String> recordForm(AuthService.Session session,BusinessRecord record,String feedback) {
    DatasetSchema schema=DatasetSchema.get(record.dataset());Map<String,String> form=new LinkedHashMap<>();form.put("csrf",session.csrf);form.put("dataset",record.dataset());form.put("organization",record.organizationId());form.put("rows","1");form.put("id0",record.id());form.put("version0",Long.toString(record.version()));
    for(DatasetSchema.Field field:schema.fields)if(field.editable()){String value=schema.value(record.values(),schema.index(field.key()));if(field.key().equals("feedback"))value=feedback;form.put("value_0_"+field.key(),value);}return form;
  }

  static String after(Draft draft,String recordId){for(SnapshotRow row:draft.rows())if(row.before().id().equals(recordId))return row.change().values().getOrDefault("feedback","");return "";}
  static String after(Submission submission,String recordId){for(SnapshotRow row:submission.rows())if(row.before().id().equals(recordId))return row.change().values().getOrDefault("feedback","");return "";}
  static Exchange get(AuthService.Session session,String path)throws Exception{Exchange x=new Exchange("GET",path);boolean matched=routes.get(x,session,HttpSupport.query(x.getRequestURI()));check(matched,"GET route matched "+path);return x;}
  static Exchange post(AuthService.Session session,String path,Map<String,String> form)throws Exception{Exchange x=new Exchange("POST",path);boolean matched=routes.post(x,session,form);check(matched,"POST route matched "+path);return x;}

  static UserAccount ready(String number,String name,Role role,String org) {
    PlatformStore.CreatedUser created=store.createUser(superUser.actor(),number,name,role,org);String password="Ready-"+number+"-Aa1!";store.changeOwnPassword(created.user().actor(),password);return store.sessionUser(created.user().id());
  }
  static AuthService.Session session(UserAccount user,String token){return new AuthService.Session(token,"csrf-"+token,clock.instant().getEpochSecond(),user);}
  static BusinessRecord record(String org,int index,String customer) {
    DatasetSchema schema=DatasetSchema.get("multi");List<String> values=new ArrayList<>(Collections.nCopies(schema.width(),""));values.set(0,Integer.toString(index+1));values.set(schema.branchColumn,Organizations.label(org));values.set(schema.customerColumn,customer);values.set(schema.codeColumn,"C"+index);values.set(schema.periodColumn,"2026-09");values.set(16,"虚构预警 "+index);
    return new BusinessRecord("",1,"multi",new xinguan.platform.Period("2026-09",LocalDate.of(2026,9,1),LocalDate.of(2026,9,30)),org,values,"synthetic.xlsx",clock.instant().toString(),clock.instant().toString(),Map.of());
  }
  static PlatformStore open(Path data)throws Exception {
    Constructor<PlatformStore> constructor=PlatformStore.class.getDeclaredConstructor(Path.class,Clock.class,Consumer.class);constructor.setAccessible(true);
    Consumer<String> checkpoint=point->{if(point.equals(failAt.get()))throw new IllegalStateException("synthetic fault at "+point);};return constructor.newInstance(data,clock,checkpoint);
  }
  static String hidden(String html,String name){Matcher m=Pattern.compile("name=\\\""+Pattern.quote(name)+"\\\" value=\\\"([^\\\"]*)\\\"").matcher(html);return m.find()?m.group(1):"";}
  static String shortIdText(String id){return id.substring(0,Math.min(8,id.length()));}
  static String id(){return UUID.randomUUID().toString();}
  static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}

  static void writeFixtures(Path output)throws Exception {
    Files.createDirectories(output.resolve("assets"));Path root=Path.of("").toAbsolutePath();for(String css:List.of("style.css","foundation.css","access.css","workflow.css"))Files.copy(root.resolve("web/assets").resolve(css),output.resolve("assets").resolve(css),StandardCopyOption.REPLACE_EXISTING);
    String editor=get(operatorSession,"/workflow/edit?dataset=multi&from=2026-09-01&through=2026-09-30").body().replace("/assets/","assets/");
    Files.writeString(output.resolve("editor.html"),editor,StandardCharsets.UTF_8);
    List<Submission> all=store.workflow().submissions(operator.actor(),new Query(null,null,null,null,null,true,0,50));if(!all.isEmpty()) {
      Files.writeString(output.resolve("submission.html"),get(operatorSession,"/workflow/submission?id="+all.get(0).id()).body().replace("/assets/","assets/"),StandardCharsets.UTF_8);
      Submission pending=all.stream().filter(item->item.state()==State.SUBMITTED).findFirst().orElse(null);
      if(pending!=null)Files.writeString(output.resolve("review.html"),get(reviewerSession,"/workflow/submission?id="+pending.id()).body().replace("/assets/","assets/"),StandardCharsets.UTF_8);
    }
  }

  static final class MutableClock extends Clock {
    private Instant instant;MutableClock(Instant instant){this.instant=instant;}void advance(Duration duration){instant=instant.plus(duration);}
    @Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return instant;}
  }

  static final class Exchange extends HttpExchange {
    final Headers requestHeaders=new Headers(),responseHeaders=new Headers();final URI uri;final String method;final ByteArrayOutputStream response=new ByteArrayOutputStream();int status=-1;long length;
    Exchange(String method,String path){this.method=method;this.uri=URI.create(path);}
    String body(){return response.toString(StandardCharsets.UTF_8);}
    @Override public Headers getRequestHeaders(){return requestHeaders;}@Override public Headers getResponseHeaders(){return responseHeaders;}@Override public URI getRequestURI(){return uri;}@Override public String getRequestMethod(){return method;}@Override public HttpContext getHttpContext(){return null;}@Override public void close(){}@Override public InputStream getRequestBody(){return InputStream.nullInputStream();}@Override public OutputStream getResponseBody(){return response;}@Override public void sendResponseHeaders(int code,long length){this.status=code;this.length=length;}@Override public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",12345);}@Override public int getResponseCode(){return status;}@Override public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",2874);}@Override public String getProtocol(){return "HTTP/1.1";}@Override public Object getAttribute(String name){return null;}@Override public void setAttribute(String name,Object value){}@Override public void setStreams(InputStream input,OutputStream output){}@Override public HttpPrincipal getPrincipal(){return null;}
  }
}

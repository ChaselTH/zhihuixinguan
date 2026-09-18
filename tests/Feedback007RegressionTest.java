import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import xinguan.platform.*;
import xinguan.platform.WorkflowContracts.*;

/** Real route forms and isolated synthetic DB. No user's data or running service. */
public final class Feedback007RegressionTest {
  static PlatformStore platform;static DataStore data;static WorkflowRoutes workflow;static BusinessRoutes business;
  static UserAccount root,division,operator,reviewer,peer;static AuthService.Session op,div;static int seq=820,assertions;
  public static void main(String[] args)throws Exception {
    try(DataStore store=new DataStore(Files.createTempDirectory("xinguan-feedback007-"))){
      data=store;platform=store.platform;String password=id();platform.bootstrapSuperAdmin("000000001",password);root=platform.authenticateUser("000000001",password);
      division=user(Role.DIVISION_ADMIN,"CZ");operator=user(Role.OPERATOR,"WUJIN");reviewer=user(Role.REVIEWER,"WUJIN");peer=user(Role.OPERATOR,"WUJIN");
      op=session(operator);div=session(division);workflow=new WorkflowRoutes(platform,"regression");business=new BusinessRoutes(store,"regression");
      pagesAndSubmission();history();oldAndEmptyDrafts();
    }
    System.out.println("FEEDBACK007_REGRESSION_OK assertions="+assertions+" actual generated forms, cross-page context, audit dates/cleanup and old drafts");
  }
  static void pagesAndSubmission()throws Exception{
    List<BusinessRecord> input=new ArrayList<>();for(int i=0;i<25;i++)input.add(candidate("multi","WUJIN","PAGE-"+i,"2026-09"));platform.importRows(div.actor,"multi",input,false,id());
    String base="/details?dataset=multi&month=2026-09&pageSize=20";
    String first=page(op,base+"&page=1");
    Matcher link=Pattern.compile("href=\"(/records/history\\?[^\"]+)\"").matcher(first);check(link.find(),"branch history entry exists");
    String url=decode(link.group(1));check(url.equals("/records/history?organization=WUJIN"),"history link uses org code, no forced table/period or double entity");
    check(page(op,url).contains("支行修改记录"),"generated history href opens successfully");
    check(first.contains("/assets/workflow.js")&&first.contains("class=\"workflow-edit-form\""),"actual unified page loads guard and marks editable form");
    Map<String,String> fields=form(first,"/workflow/draft/save");fill(fields,"PAGE-ONE-EDIT");
    var save=post(op,"/workflow/draft/save",fields);String location=save.getResponseHeaders().getFirst("Location");
    check(location.startsWith("/details?")&&location.contains("pageSize=20")&&location.contains("page=1")&&location.contains("draft="),"save redirects back to original unified table context");
    Draft draft=platform.workflow().drafts(op.actor,"multi",0,10).get(0);String firstId=draft.rows().get(0).before().id();
    first=page(op,location);check(first.contains("&amp;draft="+draft.id()+"&amp;page=2")&&first.contains("name=\"draft\" value=\""+draft.id()+"\""),"pagination and filters carry exact draft context");
    fields=form(page(op,base+"&page=2&draft="+draft.id()),"/workflow/draft/save");check(fields.get("draftId").equals(draft.id()),"second page retains draft even though first page row is absent");
    fill(fields,"PAGE-TWO-EDIT");post(op,"/workflow/draft/save",fields);
    check(platform.workflow().drafts(op.actor,"multi",0,100).size()==1,"two pages save one draft");
    fields=form(page(op,base+"&page=2&draft="+draft.id()),"/workflow/draft/save");fill(fields,"PAGE-TWO-EDIT");fields.put("intent","preview");
    var previewPage=post(op,"/workflow/draft/save",fields);String previewId=hidden(previewPage.body(),"previewId");Preview preview=platform.workflow().preview(op.actor,previewId);
    check(preview.rows().size()==2&&preview.rows().stream().anyMatch(r->r.before().id().equals(firstId)),"submit second page includes both pages, immutable server snapshot");
    check(platform.list(op.actor,"multi",null,null).stream().noneMatch(BusinessRecord::complete),"draft and preview excluded from formal completion");
    check(new AuthorizedExportService(data).export(op.actor,Map.of("dataset","multi","scope","year","year","2026","completion","complete")).count()==0,"draft excluded from official export");
    check(!page(session(peer),base).contains("PAGE-ONE-EDIT"),"same branch peer cannot see draft values");
    var submitted=platform.workflow().confirm(op.actor,previewId,id());check(platform.workflow().drafts(op.actor,"multi",0,10).isEmpty(),"confirmed draft removed from active list");
    expect(WorkflowException.class,()->platform.workflow().editableDraft(op.actor,draft.id()));
    check(!page(op,base).contains("PAGE-ONE-EDIT"),"submitted values not overlaid as drafts");
    platform.workflow().reject(reviewer.actor(),submitted.id(),"请补充测试说明",id());
    var returned=platform.workflow().editableDraft(op.actor,draft.id());check(returned.priorSubmissionId().equals(submitted.id()),"returned lineage restored even without prior URL parameter");
    var edit=getWorkflow(op,"/workflow/edit?dataset=multi&draft="+draft.id());check(edit.status==200&&edit.body().contains("workflow-draft-diff"),"focused draft difference UI opens");
    check(edit.body().contains("value_0_feedback")&&!edit.body().contains("value_0_default_risk"),"only modified fields rendered, not all yellow fields");
    Map<String,String> revert=form(edit.body(),"/workflow/draft/save");revert.put("value_0_feedback","");revert.put("value_1_feedback","");revert.put("intent","save");post(op,"/workflow/draft/save",revert);
    check(platform.workflow().drafts(op.actor,"multi",0,100).isEmpty(),"reverting all changes removes zero-diff active draft");
    check(!getWorkflow(op,"/workflow").body().contains("2 条差异"),"workspace excludes reverted draft");
  }
  static void history()throws Exception{
    platform.importRows(div.actor,"negative",List.of(candidate("negative","WUJIN","OLD-SOURCE","2025-01")),false,id());
    var record=platform.list(div.actor,"negative",null,null).get(0);var preview=platform.workflow().previewDirect(div.actor,"negative",List.of(new RecordChange(record.id(),record.version(),Map.of("feedback","TODAY-EDIT-OLD-MONTH"))));var submitted=platform.workflow().confirm(div.actor,preview.id(),id());
    String today=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();String url="/records/history?organization=WUJIN&dataset=negative&from="+today+"&through="+today;
    check(page(div,url).contains("TODAY-EDIT-OLD-MONTH"),"event-date filter includes old source periods edited today");
    check(page(op,"/records/history?organization=WUJIN").contains("TODAY-EDIT-OLD-MONTH"),"default branch history spans all tables");
    var other=session(user(Role.OPERATOR,"JINTAN"));expect(SecurityException.class,()->page(other,url));
    check(!page(other,"/records/history").contains("TODAY-EDIT-OLD-MONTH"),"branch history excludes foreign changes");
    var filter=new AccessPlatform.AuditFilter("business","WUJIN","negative","",null,null);
    var purge=platform.maintenance().previewAudit(root.actor(),filter,submitted.id());platform.maintenance().confirm(root.actor(),purge.token(),"audit");
    check(!page(div,url).contains("TODAY-EDIT-OLD-MONTH"),"purged audit never reconstructed from immutable snapshot");
    check(platform.workflow().submission(div.actor,submitted.id()).rows().size()==1,"audit purge keeps immutable business submission intact");
    record=platform.find(div.actor,record.id());
    // Over 100 edits on a single old row: no per-record 100-item truncation.
    for(int i=0;i<103;i++){var p=platform.workflow().previewDirect(div.actor,"negative",List.of(new RecordChange(record.id(),record.version(),Map.of("feedback","HISTORY-"+i))));platform.workflow().confirm(div.actor,p.id(),id());record=platform.find(div.actor,record.id());}
    int count=0;Set<String> ids=new HashSet<>();for(int offset=0;;offset+=25){var part=platform.access().audit(div.actor,new AccessPlatform.AuditFilter("business","WUJIN","negative","DIRECT_EDIT",null,null),offset,25);for(var e:part){check(ids.add(e.id()),"history pages have no duplicate audit entries");count++;}if(part.size()<25)break;}
    check(count==103,"history SQL pagination reaches all edits beyond 100");
    check(page(div,"/records/history?organization=WUJIN&dataset=negative&search=DIRECT_EDIT&offset=100").contains("HISTORY-"),"history route reaches tail page");
  }
  static void oldAndEmptyDrafts()throws Exception{
    var record=platform.list(op.actor,"negative",null,null).get(0);
    for(int i=0;i<101;i++)platform.workflow().saveDraft(op.actor,"",0,"negative",List.of(new RecordChange(record.id(),record.version(),Map.of("feedback","DRAFT-"+i))),"",id());
    var beyond=platform.workflow().drafts(op.actor,"negative",100,1).get(0);var reopen=getWorkflow(op,"/workflow/edit?dataset=negative&draft="+beyond.id());
    check(reopen.status==200&&!reopen.body().contains("已提交并冻结"),"101st active draft opens by exact point lookup");
    check(page(op,"/details?dataset=negative&scope=year&year=2025").contains("不会自动混合不同草稿"),"multiple drafts require explicit selection");
    var selected=form(page(op,"/details?dataset=negative&scope=year&year=2025&draft="+beyond.id()),"/workflow/draft/save");check(selected.get("draftId").equals(beyond.id()),"selected older draft, not arbitrary first row match");
    expect(SecurityException.class,()->page(session(peer),"/details?dataset=negative&draft="+beyond.id()));
    expect(IllegalArgumentException.class,()->page(op,"/details?dataset=multi&draft="+beyond.id()));
    for(int i=0;i<31;i++)platform.workflow().saveDraft(op.actor,"",0,"negative",List.of(),"",id());
    check(platform.workflow().drafts(op.actor,"negative",0,30).size()==30&&platform.workflow().drafts(op.actor,"negative",100,30).size()==1,"empty drafts filtered before pagination, not after");
    var latest=platform.workflow().drafts(op.actor,"negative",0,1).get(0);platform.workflow().saveDraft(op.actor,latest.id(),latest.version(),"negative",List.of(),"",id());
    expect(WorkflowException.class,()->platform.workflow().saveDraft(op.actor,latest.id(),latest.version(),"negative",List.of(new RecordChange(record.id(),record.version(),Map.of("feedback","STALE"))),"",id()));
  }
  static BusinessRecord candidate(String dataset,String org,String name,String month){var s=DatasetSchema.get(dataset);var p=xinguan.platform.Period.parse(month,"");List<String> v=new ArrayList<>(Collections.nCopies(s.width(),""));v.set(0,name);v.set(s.customerColumn,name);v.set(s.codeColumn,"CODE-"+name);v.set(s.branchColumn,Organizations.label(org));if(s.periodColumn>=0)v.set(s.periodColumn,p.key());else v.set(s.index("default_first_date"),p.start().toString());return new BusinessRecord("",0,dataset,p,org,v,"synthetic.xlsx",Instant.now().toString(),"",Map.of());}
  static UserAccount user(Role role,String org){var created=platform.createUser(root.actor(),String.format("%09d",++seq),"SYNTHETIC-"+seq,role,org);platform.changeOwnPassword(created.user().actor(),id());return platform.sessionUser(created.user().id());}
  static AuthService.Session session(UserAccount user){var s=new AuthService.Session(id(),id(),Instant.now().getEpochSecond(),user);s.safetyVersion=AccessPlatform.SAFETY_VERSION;return s;}
  static String page(AuthService.Session s,String uri)throws Exception{var x=new WorkflowRoutesTest.Exchange("GET",uri);business.get(x,s,HttpSupport.query(x.getRequestURI()));check(x.status==200,"GET "+uri);return x.body();}
  static WorkflowRoutesTest.Exchange getWorkflow(AuthService.Session s,String uri)throws Exception{var x=new WorkflowRoutesTest.Exchange("GET",uri);workflow.get(x,s,HttpSupport.query(x.getRequestURI()));return x;}
  static WorkflowRoutesTest.Exchange post(AuthService.Session s,String uri,Map<String,String> fields)throws Exception{var x=new WorkflowRoutesTest.Exchange("POST",uri);workflow.post(x,s,fields);check(x.status==200||x.status==303,"post "+x.status+" "+x.body());return x;}
  static void fill(Map<String,String> form,String text){var schema=DatasetSchema.get(form.get("dataset"));for(int i=0;i<Integer.parseInt(form.get("rows"));i++)for(var field:schema.fields)if(field.editable())form.put("value_"+i+"_"+field.key(),field.key().equals("feedback")&&i==0?text:"");form.put("intent","save");}
  static Map<String,String> form(String html,String action){Matcher f=Pattern.compile("<form[^>]*action=\""+Pattern.quote(action)+"\"[^>]*>(.*?)</form>",Pattern.DOTALL).matcher(html);if(!f.find())throw new AssertionError("missing form");Map<String,String> out=new LinkedHashMap<>();Matcher m=Pattern.compile("<input[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"").matcher(f.group(1));while(m.find())out.put(decode(m.group(1)),decode(m.group(2)));return out;}
  static String hidden(String html,String key){Matcher m=Pattern.compile("name=\""+key+"\" value=\"([^\"]*)\"").matcher(html);if(!m.find())throw new AssertionError("missing "+key);return decode(m.group(1));}
  static String decode(String text){return text.replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">");}
  interface Attempt{void run()throws Exception;}
  static void expect(Class<? extends Exception> type,Attempt work)throws Exception{try{work.run();throw new AssertionError("expected "+type);}catch(Exception e){check(type.isInstance(e),"expected "+type+", got "+e);}}
  static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
  static String id(){return UUID.randomUUID().toString();}
}

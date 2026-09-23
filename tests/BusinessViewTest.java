import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import xinguan.platform.*;
import xinguan.platform.WorkflowContracts.*;

/** B2 read model and actual XLSX checks using real persisted synthetic identities. */
public final class BusinessViewTest {
  static int assertions,sequence=700;static PlatformStore platform;
  public static void main(String[] args)throws Exception{
    try(DataStore data=new DataStore(Files.createTempDirectory("xinguan-b2-views-"))){
      platform=data.platform;String password=id();platform.bootstrapSuperAdmin("000000001",password);var root=platform.authenticateUser("000000001",password).actor();
      var division=user(root,Role.DIVISION_ADMIN,"CZ");var operator=user(root,Role.OPERATOR,"WUJIN");var peer=user(root,Role.OPERATOR,"WUJIN");var reviewer=user(root,Role.REVIEWER,"WUJIN");var branch=user(root,Role.BRANCH_ADMIN,"WUJIN");var other=user(root,Role.REVIEWER,"JINTAN");
      var workflow=platform.workflow();
      for(var schema:DatasetSchema.all()){
        String dataset=schema.id,field=dataset.equals("cross")?"cross_feedback":"feedback";
        platform.importRows(division,dataset,List.of(FoundationTest.candidate(dataset,"WUJIN","B2-OWN-"+dataset),FoundationTest.candidate(dataset,"JINTAN","B2-OTHER-"+dataset)),false,id());
        var official=platform.list(operator,dataset,null,null).get(0);var dashboard=view(data,operator);var row=dashboard.rows(dataset).get(0);
        var draft=workflow.saveDraft(operator,"",0,dataset,List.of(new RecordChange(official.id(),official.version(),Map.of(field,"B2-PRIVATE-CONTENT"))),"",id());
        var own=BusinessWorkflowState.load(platform,operator,List.of(row));check(own.get(row.record.id).draft()!=null,"own active draft visible");
        for(var role:List.of(root,division,peer,reviewer,branch))check(BusinessWorkflowState.load(platform,role,List.of(row)).get(row.record.id).draft()==null,"private draft existence isolated");
        RowRef scopedRow=row;expect(SecurityException.class,()->BusinessWorkflowState.load(platform,other,List.of(scopedRow)));
        var html=pages(operator).details(dashboard,filter(operator,dataset,"all").withDraft(draft.id()),1,own.editing(draft,false));check(html.contains("我的草稿")&&html.contains("B2-PRIVATE-CONTENT"),"owner restores selected private draft in unified table while other roles remain isolated");
        check(html.contains("name=\"draftId\" value=\""+draft.id()+"\"")&&html.contains("支行修改记录")&&!html.contains("查看追溯")&&!html.contains("business-row-ref"),"real page-level controls replace row edit/trace actions without hidden test markers");
        String detailTable=html.substring(html.indexOf("<table class=\"data-table detail-table\">"));detailTable=detailTable.substring(0,detailTable.indexOf("</table>"));
        check(!detailTable.contains("期次／历史保留信息")&&detailTable.split("<th[ >]",-1).length-1==schema.width()+1,"detail has only template columns plus existing workflow action column: "+dataset);
        String body=detailTable.substring(detailTable.indexOf("<tbody>"));check(body.split("<td[ >]",-1).length-1==schema.width()+1,"row cells align after extra metadata column removed");
        check(detailTable.contains(dataset.equals("cross")?"违约首次出现时间":"时间顺序"),"original template time field retained");
        var emptyFilter=BusinessFilter.from(operator,Map.of("dataset",dataset,"q","NO-RC8-MATCH"));String emptyDetail=pages(operator).details(dashboard,emptyFilter,1,BusinessWorkflowState.empty());
        check(emptyDetail.contains("colspan=\""+(schema.width()+1)+"\""),"empty table spans exactly displayed columns");
        assertExport(data,operator,dataset,"complete",0,"");assertExport(data,operator,dataset,"incomplete",1,"");
        var pending=workflow.confirm(operator,workflow.previewDraft(operator,draft.id(),draft.version()).id(),id());
        var sent=BusinessWorkflowState.load(platform,operator,List.of(row));check(sent.get(row.record.id).pending().id().equals(pending.id())&&sent.get(row.record.id).draft()==null,"submitted draft not mislabelled unsent");
        expect(WorkflowException.class,()->workflow.saveDraft(peer,"",0,dataset,List.of(new RecordChange(official.id(),official.version(),Map.of(field,"PEER-PENDING"))),"",id()));
        check(workflow.drafts(peer,dataset,0,10).isEmpty(),"parallel operator cannot create a second active task for an in-flight row");
        check(view(data,operator).completedCount()==0,"pending does not increase official completion");
        String reviewerDetails=pages(reviewer).details(view(data,reviewer),filter(reviewer,dataset,"all"),1,BusinessWorkflowState.load(platform,reviewer,List.of(row)));check(reviewerDetails.contains("class=\"btn btn-primary\" href=\"/workflow/submission?id=")&&reviewerDetails.contains("class=\"btn btn-light\" href=\"/workflow/record-history?record=")&&!reviewerDetails.contains("查看提交回执"),"reviewer sees two visually separate review/history buttons without duplicate receipt");
        var divisionPending=pages(division).details(view(data,division),filter(division,dataset,"all"),1,BusinessWorkflowState.load(platform,division,List.of(row)));check(!divisionPending.contains("business-badge workflow-stage\">待支行复核")&&!divisionPending.contains("class=\"btn btn-primary\" href=\"/workflow/submission?id="),"division list does not offer branch-stage review or branch-stage badge");
        workflow.reject(reviewer,pending.id(),"B2-RETURN-REASON",id());
        dashboard=view(data,operator);row=dashboard.rows(dataset).get(0);var returned=BusinessWorkflowState.load(platform,operator,List.of(row));check(returned.get(row.record.id).draft()!=null&&returned.get(row.record.id).pending()==null,"returned draft recoverable");
        var returnedPage=pages(operator).details(dashboard,filter(operator,dataset,"all"),1,returned);check(returnedPage.contains("退回待修改")&&returnedPage.contains("B2-RETURN-REASON"),"returned label and reason separate from official completion");
        var next=workflow.saveDraft(operator,draft.id(),draft.version(),dataset,List.of(new RecordChange(official.id(),official.version(),Map.of(field,"0"))),pending.id(),id());
        var approved=workflow.confirm(operator,workflow.previewDraft(operator,next.id(),next.version()).id(),id());workflow.approve(reviewer,approved.id(),id());
        check(platform.find(operator,official.id()).values().get(DatasetSchema.get(dataset).index(field)).isBlank(),"branch review does not publish through business read model");workflow.approve(division,approved.id(),id());
        dashboard=view(data,operator);row=dashboard.rows(dataset).get(0);var resolved=BusinessWorkflowState.load(platform,operator,List.of(row));
        check(resolved.get(row.record.id).draft()==null&&resolved.get(row.record.id).pending()==null,"published draft no longer active");
        check(dashboard.filtered(dataset,"","","complete").isEmpty(),"branch operator cannot see completed formal rows");assertExport(data,operator,dataset,"complete",0,"");assertExport(data,operator,dataset,"incomplete",0,"");
        var divisionDashboard=view(data,division);var divisionRow=divisionDashboard.rows(dataset).get(0);var divisionState=BusinessWorkflowState.load(platform,division,List.of(divisionRow));
        check(divisionDashboard.filtered(dataset,"","","complete").size()==1,"zero counts as completed formal fill for division administrator");assertExport(data,division,dataset,"complete",1,"0");
        var completedHtml=pages(division).details(divisionDashboard,filter(division,dataset,"complete"),1,divisionState);check(completedHtml.contains("row-complete")&&completedHtml.contains("editable-cell"),"green formal row retains yellow cells");
        check(!pages(operator).dashboard(dashboard,resolved).contains("虚构测试企业 B2-OWN-"+dataset),"home excludes completed formal record for current dataset");
        var current=platform.find(division,official.id());workflow.confirm(division,workflow.previewDirect(division,dataset,List.of(new RecordChange(current.id(),current.version(),Map.of(field,"")))).id(),id());
        assertExport(data,operator,dataset,"incomplete",1,"");check(view(data,operator).filtered(dataset,"","","complete").isEmpty(),"clear last yellow resets completion");
        check(BusinessWorkflowState.load(platform,operator,view(data,operator).rows(dataset)).get(official.id()).draft()==null,"approved draft cannot reappear after later formal edits");
      }
      var empty=pages(root).dashboard(new DashboardData(RangeSelection.from(Map.of(),List.of()),List.of(),List.of(),List.of()));check(empty.contains("本期无记录")&&empty.contains("completion-icon empty"),"zero record branch is not yellow overdue/incomplete");
      var d=view(data,division);var selected=filter(division,"multi","all");String detail=pages(division).details(d,selected,1,BusinessWorkflowState.empty());check(detail.contains("business.css")&&detail.contains("col-feedback")&&!detail.contains("fetch("),"local CSS, wide feedback, HTML-only core");
      check(detail.contains("保存资料补充 · 预览确认")&&!detail.contains("跨支行清单请先筛选"),"division administrator can submit mixed-branch page in one transaction");
      var single=BusinessFilter.from(division,Map.of("dataset","multi","branch","武进"));check(pages(division).details(d,single,1,BusinessWorkflowState.empty()).contains("保存资料补充 · 预览确认"),"one branch keeps batch preview action");
      var internal=new InternalPages("test",session(operator)).overview(view(data,operator),filter(operator,"cross","all"),BusinessWorkflowState.empty());check(internal.contains("行内报表 · 待配置")&&!internal.contains("dataset=negative"),"internal module does not masquerade risk schemas as unknown templates");
      for(var actor:List.of(root,division,branch,operator,reviewer))for(String completion:List.of("all","complete","incomplete"))for(var range:List.of(Map.of("scope","year","year","2026"),Map.of("scope","quarter","year","2026","quarter","3"),Map.of("scope","custom","start","2026-07","end","2026-09"))){
        Map<String,String> query=new HashMap<>(range);query.put("completion",completion);query.put("q","B2-OWN");query.put("branch","武进");query.put("dataset","multi");
        var filter=BusinessFilter.from(actor,query);var dashboard=new DashboardData(RangeSelection.from(query,data.months(actor)),data.months(actor),List.of(),data.readRange(RangeSelection.from(query,data.months(actor)),actor),data.platform.completionRules().visible(actor),actor);
        check(new AuthorizedExportService(data).export(actor,query).count()==filter.rows(dashboard).size(),"all roles view/export use exact same filters");
        String html=pages(actor).details(dashboard,filter,1,BusinessWorkflowState.empty());check(html.contains("completion="+completion)&&html.contains("name=\"completion\"")&&html.contains("q=B2-OWN"),"export/range/search carry state");
      }
      expect(IllegalArgumentException.class,()->BusinessFilter.from(division,Map.of("completion","pending")));expect(SecurityException.class,()->BusinessFilter.from(operator,Map.of("branch","金坛")));
      pagination(data,division,operator);
    }
    System.out.println("BUSINESS_VIEW_OK assertions="+assertions+" private workflow labels, official completion, scoped filters, real exports and pagination");
  }
  static void pagination(DataStore data,ActorContext division,ActorContext operator)throws Exception{
    List<BusinessRecord> records=new ArrayList<>();for(int i=0;i<53;i++)records.add(FoundationTest.candidate("multi","WUJIN","B2-PAGE-"+i));platform.importRows(division,"multi",records,false,id());
    var filter=BusinessFilter.from(operator,Map.of("dataset","multi","completion","incomplete","q","B2-PAGE"));var d=view(data,operator);
    String first=pages(operator).details(d,filter,1,BusinessWorkflowState.empty()),second=pages(operator).details(d,filter,2,BusinessWorkflowState.empty());
    check(first.contains("&amp;completion=incomplete&amp;page=2")&&second.contains("&amp;completion=incomplete&amp;page=1"),"completion/search preserved across pages");
    check(first.contains("name=\"rows\" value=\"20\"")&&second.contains("name=\"rows\" value=\"20\""),"default pagination size 20");
    check(pages(operator).details(d,filter,3,BusinessWorkflowState.empty()).contains("name=\"rows\" value=\"13\""),"final page 13 rows");
    for(int size:List.of(10,50)){var sized=BusinessFilter.from(operator,Map.of("dataset","multi","q","B2-PAGE","pageSize",""+size));check(pages(operator).details(d,sized,1,BusinessWorkflowState.empty()).contains("name=\"rows\" value=\""+size+"\""),"selected page size");}
    expect(IllegalArgumentException.class,()->BusinessFilter.from(operator,Map.of("pageSize","99999")));
    check(new AuthorizedExportService(data).export(operator,Map.of("dataset","multi","scope","year","year","2026","q","B2-PAGE","completion","incomplete")).count()==53,"export is full matching set not current page");
  }
  static void assertExport(DataStore data,ActorContext actor,String dataset,String completion,int count,String expected)throws Exception{
    var result=new AuthorizedExportService(data).export(actor,Map.of("dataset",dataset,"scope","year","year","2026","completion",completion));check(result.count()==count,"formal export count "+completion+" expected="+count+" actual="+result.count());
    try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(result.bytes()))){var schema=DatasetSchema.get(dataset);check(wb.getNumberOfSheets()==1,"no private extra sheet");if(count>0)check(wb.getSheetAt(0).getRow(schema.headerRows).getCell(schema.index(dataset.equals("cross")?"cross_feedback":"feedback")).getStringCellValue().equals(expected),"formal export cell only");}
  }
  static DashboardData view(DataStore data,ActorContext actor){var months=data.months(actor);var r=RangeSelection.from(Map.of("scope","year","year","2026"),months);return new DashboardData(r,months,List.of(),data.readRange(r,actor),data.platform.completionRules().visible(actor),actor);}
  static BusinessFilter filter(ActorContext a,String dataset,String completion){return BusinessFilter.from(a,Map.of("dataset",dataset,"completion",completion));}
  static AuthService.Session session(ActorContext a){var s=new AuthService.Session(id(),id(),Instant.now().getEpochSecond(),platform.sessionUser(a.userId()));s.safetyVersion=AccessPlatform.SAFETY_VERSION;return s;}
  static RiskPages pages(ActorContext a){return new RiskPages("test",session(a));}
  static ActorContext user(ActorContext root,Role role,String org){var u=platform.createUser(root,String.format("%09d",++sequence),"虚构 B2 "+sequence,role,org).user();platform.changeOwnPassword(u.actor(),id());return platform.sessionUser(u.id()).actor();}
  static String id(){return UUID.randomUUID().toString();}
  static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
  interface Work{void run()throws Exception;}
  static void expect(Class<? extends Throwable> type,Work work){assertions++;try{work.run();}catch(Throwable e){if(type.isInstance(e))return;throw new AssertionError("expected "+type+" got "+e,e);}throw new AssertionError("expected "+type);}
}

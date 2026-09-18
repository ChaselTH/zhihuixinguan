import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import xinguan.platform.*;

public class CompletionRuleViewTest {
  static int assertions;
  public static void main(String[] args)throws Exception {
    var clock=new FeedbackViewTest.TestClock();
    try(DataStore data=new DataStore(Files.createTempDirectory("xinguan-completion-view-"),clock)){
      var s=data.platform;s.bootstrapSuperAdmin("908000001","synthetic-required-only");var root=s.authenticateUser("908000001","synthetic-required-only").actor();
      var div=FeedbackViewTest.user(s,root,"908000002",Role.DIVISION_ADMIN,"CZ");var op=FeedbackViewTest.user(s,root,"908000003",Role.OPERATOR,"WUJIN");
      var session=new AuthService.Session("test","test",Instant.now().getEpochSecond(),s.sessionUser(op.userId()));session.safetyVersion=AccessPlatform.SAFETY_VERSION;var pages=new RiskPages("test",session);
      for(var schema:DatasetSchema.all()){
        String feedback=schema.id.equals("cross")?"cross_feedback":"feedback",choice=schema.id.equals("negative")?"repayment_impact":"default_risk";
        for(String org:List.of("WUJIN","JINTAN")){
          var r=FoundationTest.candidate(schema.id,org,"REQUIRED-"+org+schema.id);var values=new ArrayList<>(r.values());values.set(schema.index(feedback),"正式已填反馈");
          s.importRows(div,schema.id,List.of(new BusinessRecord("",0,schema.id,r.period(),org,values,"synthetic.xlsx",Instant.now().toString(),"",Map.of())),false,id());
        }
        var row=s.list(op,schema.id,null,null).get(0);s.deadlines().save(div,schema.id,row.period().key(),"2026-09-15",0);
        check(FeedbackViewTest.view(data,op).filtered(schema.id,"","","complete").size()==1,"before rules legacy completion remains");
        s.completionRules().save(div,schema.id,Set.of(feedback,choice),0);
      }
      clock.now=Instant.parse("2026-09-17T02:00:00Z");var incomplete=FeedbackViewTest.view(data,op);
      check(incomplete.completedCount()==0&&incomplete.branches.get("武进").completed==0&&incomplete.feedbackPeriods().stream().allMatch(p->p.completed==0&&p.overdue()),"all stats and deadline reminders use current required rules");
      check(RiskPages.homeRows(incomplete).size()==3,"partially filled formal rows reappear in all three home unfinished lists");
      try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(new AuthorizedExportService(data).exportProgress(div,Map.of("scope","year","year","2026")).bytes()))){Row total=wb.getSheetAt(0).getRow(wb.getSheetAt(0).getLastRowNum());check(total.getCell(2).getNumericCellValue()==6&&total.getCell(3).getNumericCellValue()==0,"progress XLSX uses required rules instead of legacy any-filled completion");}
      for(var schema:DatasetSchema.all()){
        var query=Map.of("dataset",schema.id,"scope","year","year","2026","completion","incomplete");var exports=new AuthorizedExportService(data);
        check(incomplete.filtered(schema.id,"","","overdue").size()==1&&exports.export(op,query).count()==1,"branch filter and authorized export agree on incomplete");
        check(exports.export(op,Map.of("dataset",schema.id,"scope","year","year","2026","completion","complete")).count()==0,"incomplete omitted from completed XLSX");
        String html=pages.details(incomplete,BusinessFilter.from(op,query),1,BusinessWorkflowState.empty());
        check(html.contains("required-marker")&&html.contains("正式值缺少 1 项必填")&&html.contains("row-overdue")&&!html.contains(" required=")&&!html.contains("required>"),"required labels do not add blocking HTML validation");
        check(!html.contains("REQUIRED-JINTAN"),"required status never bypasses branch scope");
        var row=s.list(op,schema.id,null,null).get(0);String field=schema.id.equals("negative")?"repayment_impact":"default_risk";
        s.workflow().saveDraft(op,"",0,schema.id,List.of(new RecordChange(row.id(),row.version(),Map.of(field,"否"))),"",id());
        check(FeedbackViewTest.view(data,op).filtered(schema.id,"","","complete").isEmpty(),"private draft cannot satisfy official required fields");
        s.workflow().confirm(div,s.workflow().previewDirect(div,schema.id,List.of(new RecordChange(row.id(),row.version(),Map.of(field,"否")))).id(),id());
        var complete=FeedbackViewTest.view(data,op);check(complete.filtered(schema.id,"","","complete").size()==1&&complete.filtered(schema.id,"","","overdue").isEmpty(),"all required formal values complete and remove overdue");
        check(exports.export(op,Map.of("dataset",schema.id,"scope","year","year","2026","completion","complete")).count()==1,"completed export updates without reimport");
        s.completionRules().save(div,schema.id,Set.of(),1);
      }
      var all=FeedbackViewTest.view(data,root);check(all.completedCount()==6&&all.branches.get("金坛").completed==3,"clearing required rules recalculates historical rows in all branches");
      try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(new AuthorizedExportService(data).exportProgress(div,Map.of("scope","year","year","2026")).bytes()))){Row total=wb.getSheetAt(0).getRow(wb.getSheetAt(0).getLastRowNum());check(total.getCell(2).getNumericCellValue()==6&&total.getCell(3).getNumericCellValue()==6,"progress XLSX totals match screen rules");}
      s.completionRules().save(div,"multi",Set.of("feedback"),2);
      var months=data.months(div);var emptyRange=RangeSelection.from(Map.of("scope","year","year","2027"),months);var empty=data.dashboard(emptyRange,months,div);
      check(empty.records.isEmpty()&&empty.requiredFields.get("multi").equals(Set.of("feedback")),"empty period still shows current header policy");
      String settings=new CompletionRulePages("test",session).settings(s.completionRules().visible(op).get("multi"),false);check(settings.contains("只读")&&!settings.contains("action=\"/completion-rules/save\""),"branch may only read configuration");
    }
    System.out.println("COMPLETION_RULE_VIEW_OK assertions="+assertions+" three datasets, scoped screens, live progress, overdue, defaults and real XLSX");
  }
  static String id(){return UUID.randomUUID().toString();}
  static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
}

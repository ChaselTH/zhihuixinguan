import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import xinguan.platform.*;

final class CompletionRuleHttpTest {
  static int assertions;
  static void run(HttpClient root,HttpClient division,HttpClient branch,HttpClient operator,HttpClient reviewer)throws Exception {
    use(HttpSmokeTest.newClient());check(get("/completion-rules").statusCode()==303,"configuration requires authentication");
    use(division);check(get("/").body().contains("href=\"/completion-rules\""),"division has homepage configuration entry");
    var original=form(get("/completion-rules?dataset=multi").body(),"/completion-rules/save");
    for(var client:List.of(root,branch,operator,reviewer)){
      use(client);String html=get("/completion-rules?dataset=multi").body();check(html.contains("只读")&&!html.contains("action=\"/completion-rules/save\""),"other roles have no save form");
      var forged=new HashMap<>(original);forged.put("csrf",csrf());check(post("/completion-rules/save",forged).statusCode()==403,"forged configuration POST denied");
    }
    use(division);var invalid=new HashMap<>(original);invalid.put("csrf","bad");check(post("/completion-rules/save",invalid).statusCode()==403,"CSRF applies to settings");
    invalid.put("csrf",csrf());invalid.put("required_customer_name","true");check(post("/completion-rules/save",invalid).statusCode()==400,"source field cannot be required");
    invalid.remove("required_customer_name");invalid.remove("required_feedback");check(post("/completion-rules/save",invalid).statusCode()==400,"omitted field cannot silently clear configuration");
    invalid=new HashMap<>(original);invalid.put("required_feedback","maybe");check(post("/completion-rules/save",invalid).statusCode()==400,"invalid boolean rejected");
    for(var schema:DatasetSchema.all()){
      use(division);String primary=schema.id.equals("cross")?"cross_feedback":"feedback",choice=schema.id.equals("negative")?"repayment_impact":"default_risk";
      var settings=form(get("/completion-rules?dataset="+schema.id).body(),"/completion-rules/save");
      check(settings.keySet().stream().filter(k->k.startsWith("required_")).count()==schema.fields.stream().filter(DatasetSchema.Field::editable).count(),"every and only yellow column configurable");
      settings.put("required_"+primary,"true");settings.put("required_"+choice,"true");check(post("/completion-rules/save",settings).statusCode()==303,"division saves required fields");
      check(post("/completion-rules/save",settings).statusCode()==409,"old form revision cannot overwrite settings");
      String key="REQUIRED-HTTP-"+schema.id;
      var uploaded=ImportHttpTest.uploadDataset(schema.id,List.of(HttpSmokeTest.workbook(schema.id,"WUJIN",key)),List.of("synthetic-required-"+schema.id+".xlsx"),csrf());
      check(uploaded.statusCode()==200,"synthetic "+schema.id+" import preview: "+uploaded.statusCode());
      var confirm=HttpSmokeTest.hidden(uploaded.body());confirm.put("csrf",csrf());confirm.put("mode","saved");check(post("/imports/confirm",confirm).statusCode()==303,"required blanks do not block import");
      String detail="/details?dataset="+schema.id+"&month=2026-09&q="+key+"&completion=all";
      use(operator);String html=get(detail+"&draft=new").body();check(html.contains("required-marker")&&!html.contains(" required=")&&html.contains("空白仍可提交"),"table labels are advisory not blocking");
      var edits=form(html,"/workflow/draft/save");edits.put("value_0_"+primary,"虚构部分填写");edits.put("intent","preview");
      var preview=post("/workflow/draft/save",edits);check(preview.statusCode()==200,"missing required value permits server diff");
      var submit=post("/workflow/confirm",form(preview.body(),"/workflow/confirm"));check(submit.statusCode()==200,"operator may submit partial feedback");
      String submission=WorkflowIntegrationHttpTest.id(submit.body());use(reviewer);var approve=form(get("/workflow/submission?id="+submission).body(),"/workflow/review/approve");check(post("/workflow/review/approve",approve).statusCode()==200,"reviewer may approve partial feedback");use(division);check(post("/workflow/review/approve",form(get("/workflow/submission?id="+submission).body(),"/workflow/review/approve")).statusCode()==200,"division may finally publish partial feedback");
      use(operator);html=get(detail).body();check(html.contains("正式未完成")&&html.contains("正式值缺少 1 项必填"),"approved partial data remains incomplete");
      check(exportCount(schema.id,key,"complete")==0&&exportCount(schema.id,key,"incomplete")==1,"formal completion XLSX uses required rules");
      check(get("/?month=2026-09").body().contains(schema.label+" · 待处理 "+exportCount(schema.id,"","incomplete")+" 条"),"home pending count agrees with scoped export despite eight-row preview limit");
      use(branch);var fill=form(get(detail).body(),"/update-batch");fill.put("v0_"+schema.index(choice),"否");preview=post("/update-batch",fill);var firstFinal=post("/workflow/confirm",form(preview.body(),"/workflow/confirm"));check(firstFinal.statusCode()==200&&!get(detail).body().contains("✓ 正式已完成"),"branch direct confirmation still waits for division final review");
      use(division);String firstFinalId=WorkflowIntegrationHttpTest.id(firstFinal.body());check(post("/workflow/review/approve",form(get("/workflow/submission?id="+firstFinalId).body(),"/workflow/review/approve")).statusCode()==200,"division final approval publishes required value");
      use(branch);check(!get(detail).body().contains("✓ 正式已完成"),"branch queue hides completed rows after final publication");use(division);check(get(detail).body().contains("✓ 正式已完成")&&exportCount(schema.id,key,"complete")==1,"division detail and export include completed row");
      String rowId=BusinessHttpTest.recordId(get("/details?dataset="+schema.id+"&month=2026-09&q="+key+"&completion=complete").body());var reopen=form(get("/workflow/reopen?record="+rowId).body(),"/workflow/reopen");reopen.put("confirm","yes");reopen.put("reason","规则回归：终审后重新打开");check(post("/workflow/reopen",reopen).statusCode()==303,"division can reopen a completed row with reason");
      use(branch);fill=form(get(detail+"&completion=incomplete").body(),"/update-batch");fill.put("v0_"+schema.index(choice),"");preview=post("/update-batch",fill);var clearPending=post("/workflow/confirm",form(preview.body(),"/workflow/confirm"));check(clearPending.statusCode()==200&&!get(detail+"&completion=incomplete").body().contains("✓ 正式已完成"),"reopened completion change waits for a new division review");
      use(division);String clearSubmission=WorkflowIntegrationHttpTest.id(clearPending.body());check(post("/workflow/review/approve",form(get("/workflow/submission?id="+clearSubmission).body(),"/workflow/review/approve")).statusCode()==200,"division approves reopened row on its new submission chain");use(branch);check(get(detail+"&completion=incomplete").body().contains("正式未完成"),"clearing a required field reopens completion");
      use(division);settings=form(get("/completion-rules?dataset="+schema.id).body(),"/completion-rules/save");for(var field:schema.fields)if(field.editable())settings.put("required_"+field.key(),"false");
      check(post("/completion-rules/save",settings).statusCode()==303&&get("/details?dataset="+schema.id+"&month=2026-09&q="+key+"&completion=complete").body().contains("✓ 正式已完成"),"clearing rule restores legacy completion without reimport");
    }
    use(root);check(get("/audit?category=business&search=COMPLETION_RULE_SET").body().contains("填报必填设置"),"configuration writes readable shared audit");
    System.out.println("COMPLETION_RULE_HTTP_OK assertions="+assertions+" real Main five roles, three templates, partial submit/review, live completion and XLSX");
  }
  static int exportCount(String dataset,String key,String status)throws Exception {
    var response=HttpSmokeTest.client.send(HttpRequest.newBuilder(URI.create(HttpSmokeTest.base+"/export?dataset="+dataset+"&month=2026-09&q="+key+"&completion="+status)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());check(response.statusCode()==200,"authorized export responds");
    try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(response.body()))){return Math.max(0,wb.getSheetAt(0).getLastRowNum()+1-DatasetSchema.get(dataset).headerRows);}
  }
  static Map<String,String> form(String html,String action){return WorkflowIntegrationHttpTest.form(html,action);}
  static void use(HttpClient c){HttpSmokeTest.client=c;}
  static HttpResponse<String> get(String path)throws Exception{return HttpSmokeTest.get(path);}
  static HttpResponse<String> post(String path,Map<String,String> f)throws Exception{return HttpSmokeTest.post(path,f);}
  static String csrf()throws Exception{return HttpSmokeTest.hidden(get("/").body()).get("csrf");}
  static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
}

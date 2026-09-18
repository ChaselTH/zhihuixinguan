import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.regex.*;
import org.apache.poi.ss.usermodel.*;
import xinguan.platform.*;

/** Full B2 Main routes: focused draft → review → official filter/export → clear. */
final class BusinessHttpTest {
  static int assertions;
  static void run(HttpClient root,HttpClient division,HttpClient branch,HttpClient operator,HttpClient reviewer)throws Exception{
    use(HttpSmokeTest.newClient());check(get("/internal").statusCode()==303&&get("/records/history?id=none").statusCode()==303,"new routes require login");
    use(division);var uploaded=ImportHttpTest.upload(List.of(HttpSmokeTest.workbook("multi","WUJIN","B2-HTTP-FOCUS"),HttpSmokeTest.workbook("multi","JINTAN","B2-HTTP-FOREIGN")),List.of("b2-own.xlsx","b2-other.xlsx"),csrf());
    var confirm=HttpSmokeTest.hidden(uploaded.body());confirm.put("csrf",csrf());confirm.put("mode","saved");check(post("/imports/confirm",confirm).statusCode()==303,"A2 import feeds B2 views");
    List<byte[]> pageFiles=List.of(multiRowsWorkbook());List<String> pageNames=List.of("b2-page-rows.xlsx");
    var pageUpload=ImportHttpTest.upload(pageFiles,pageNames,csrf());var pageConfirm=HttpSmokeTest.hidden(pageUpload.body());pageConfirm.put("csrf",csrf());pageConfirm.put("mode","saved");var pageCommit=post("/imports/confirm",pageConfirm);check(pageUpload.statusCode()==200&&pageCommit.statusCode()==303,"synthetic multi-row page fixture committed upload="+pageUpload.statusCode()+" commit="+pageCommit.statusCode()+" body="+pageUpload.body().substring(0,Math.min(180,pageUpload.body().length())));
    String ownId=recordId(get("/details?dataset=multi&month=2026-09&q=B2-HTTP-FOCUS").body());String foreignId=recordId(get("/details?dataset=multi&month=2026-09&q=B2-HTTP-FOREIGN").body());
    use(division);String adminPage=get("/details?dataset=multi&month=2026-09&q=B2-HTTP-PAGE&pageSize=10").body();check(adminPage.contains("/assets/workflow.js")&&adminPage.contains("class=\"workflow-edit-form\"")&&adminPage.contains("action=\"/update-batch\""),"unified direct-edit table loads workflow guard");
    use(operator);String url="/details?dataset=multi&scope=quarter&year=2026&quarter=3&q=B2-HTTP-FOCUS&completion=incomplete";String list=get(url).body();
    check(list.contains("/workflow/draft/save")&&list.contains("value=\""+ownId+"\"")&&!list.contains("B2-HTTP-FOREIGN")&&!list.contains("business-row-ref"),"scoped real in-table editor without per-row action markers");
    check(get("/assets/business.css").statusCode()==200,"local B2 styles available");check(get("/details?completion=invalid").statusCode()==400,"unknown completion filter fails closed");
    String scopedDetails=get("/details?dataset=multi&month=2026-09&q=B2-HTTP-FOCUS").body();String historyHref=WorkflowIntegrationHttpTest.unescape(WorkflowIntegrationHttpTest.match(scopedDetails,"href=\"(/records/history\\?[^\"]+)\""));check(historyHref.contains("organization=WUJIN")&&!historyHref.contains("amp;"),"history link uses organization key and one HTML encoding");check(get(historyHref).statusCode()==200,"generated history link survives one HTML decode");
    String pageUrl="/details?dataset=multi&month=2026-09&q=B2-HTTP-PAGE&pageSize=10";String pageOne=get(pageUrl).body();check(pageOne.contains("/assets/workflow.js")&&pageOne.contains("class=\"workflow-edit-form\"")&&pageOne.contains("action=\"/workflow/draft/save\""),"unified operator table loads workflow guard and draft form");
    var unifiedForm=WorkflowIntegrationHttpTest.form(pageOne,"/workflow/draft/save");unifiedForm.put("value_0_feedback","B2-CROSS-PAGE-ONE");unifiedForm.put("intent","save");var pageSaved=post("/workflow/draft/save",unifiedForm);String pageSavedUrl=pageSaved.headers().firstValue("location").orElse("");check(pageSaved.statusCode()==303&&pageSavedUrl.startsWith("/details?")&&pageSavedUrl.contains("draft="),"unified page save returns to filtered table with draft context");
    String pageTwoUrl=pageSavedUrl.replace("page=1","page=2");var pageTwoForm=WorkflowIntegrationHttpTest.form(get(pageTwoUrl).body(),"/workflow/draft/save");pageTwoForm.put("value_0_feedback","B2-CROSS-PAGE-TWO");pageTwoForm.put("intent","save");var pageTwoSaved=post("/workflow/draft/save",pageTwoForm);String pageTwoSavedUrl=pageTwoSaved.headers().firstValue("location").orElse("");check(pageTwoSaved.statusCode()==303&&pageTwoSavedUrl.contains("draft="),"second unified page save carries same draft context");
    var unifiedSubmit=WorkflowIntegrationHttpTest.form(get(pageTwoSavedUrl.replace("page=1","page=2")).body(),"/workflow/draft/save");unifiedSubmit.put("intent","preview");var unifiedPreview=post("/workflow/draft/save",unifiedSubmit);check(unifiedPreview.statusCode()==200&&unifiedPreview.body().contains("B2-CROSS-PAGE-ONE")&&unifiedPreview.body().contains("B2-CROSS-PAGE-TWO"),"unified submit preview includes saved differences from both pages");
    var unifiedConfirmed=post("/workflow/confirm",WorkflowIntegrationHttpTest.form(unifiedPreview.body(),"/workflow/confirm"));String today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();check(unifiedConfirmed.statusCode()==200,"unified cross-page submission confirms");
    String unifiedId=WorkflowIntegrationHttpTest.id(unifiedConfirmed.body());String historyToday="/records/history?organization=WUJIN&dataset=multi&from="+today+"&through="+today;
    check(get(historyToday).body().contains("/workflow/submission?id="+unifiedId),"pending event links to authorized immutable detail through shared audit");
    use(reviewer);var pageDecision=WorkflowIntegrationHttpTest.form(get("/workflow/submission?id="+unifiedId).body(),"/workflow/review/approve");check(post("/workflow/review/approve",pageDecision).statusCode()==200,"cross-page snapshot approved as one submission");
    use(operator);check(get(historyToday).body().contains("B2-CROSS-PAGE-ONE")&&get(historyToday).body().contains("B2-CROSS-PAGE-TWO"),"history shows both official field changes by event time");
    String focus="/workflow/edit?dataset=multi&organization=WUJIN&from=2026-09-01&through=2026-09-30&record="+ownId;
    String editor=get(focus).body();var form=WorkflowIntegrationHttpTest.form(editor,"/workflow/draft/save");check(form.get("rows").equals("1")&&form.get("id0").equals(ownId),"row focus never opens unrelated first page");
    check(get(focus.replace(ownId,foreignId)).statusCode()==403,"foreign record focus rejected");
    form.put("value_0_feedback","B2-HTTP-PRIVATE <script>text</script>");form.put("intent","save");var saved=post("/workflow/draft/save",form);check(saved.statusCode()==303&&saved.headers().firstValue("location").orElse("").contains("record="+ownId),"focused draft save restores exact record");
    String draftUrl=saved.headers().firstValue("location").orElseThrow();String draftPage=get(draftUrl).body();String draftId=HttpSmokeTest.hidden(draftPage).get("draftId");
    list=get(url).body();check(list.contains("class=\"business-badge draft\"")&&list.contains("B2-HTTP-PRIVATE"),"owner list restores own draft value while preserving private scope");
    use(root);check(!get(url).body().contains("我的草稿")&&!get(url).body().contains(draftId),"super cannot inspect private draft metadata");
    use(operator);form=WorkflowIntegrationHttpTest.form(draftPage,"/workflow/draft/save");form.put("intent","preview");var preview=post("/workflow/draft/save",form);check(preview.statusCode()==200,"B1 server diff still works from B2 action");
    var submitted=post("/workflow/confirm",HttpSmokeTest.hidden(preview.body()));check(submitted.statusCode()==200,"focused submission confirmed");
    list=get(url).body();check(list.contains("class=\"business-badge reviewing\"")&&!list.contains("class=\"business-badge draft\"")&&!list.contains("B2-HTTP-PRIVATE"),"pending status not completion or draft body");
    check(exportCount("incomplete")==1&&exportCount("complete")==0,"pending excluded from completed XLSX");
    String trace=get("/records/history?id="+ownId).body();check(trace.contains("待复核")&&trace.contains("/audit?submissionId="),"record history uses shared submission/audit links");
    use(reviewer);String reviewerList=get(url).body();check(reviewerList.contains("去复核"),"reviewer can follow exact submission");
    Matcher m=Pattern.compile("/workflow/submission\\?id=([^\"&]+)").matcher(reviewerList);if(!m.find())throw new AssertionError("no pending link");String submissionId=m.group(1);
    var decision=WorkflowIntegrationHttpTest.form(get("/workflow/submission?id="+submissionId).body(),"/workflow/review/approve");check(post("/workflow/review/approve",decision).statusCode()==200,"review publishes official data");
    use(operator);check(!get(url).body().contains("虚构测试企业 B2-HTTP-FOCUS"),"incomplete filter removes approved row");
    String completed=get(url.replace("completion=incomplete","completion=complete")).body();check(completed.contains("B2-HTTP-FOCUS")&&completed.contains("row-complete")&&completed.contains("B2-HTTP-PRIVATE &lt;script&gt;text&lt;/script&gt;"),"approved formal text escaped and completed");
    check(exportCount("incomplete")==0&&exportCount("complete")==1,"completed XLSX matches list");check(!get("/?month=2026-09").body().contains("B2-HTTP-FOCUS"),"homepage shows only formal incomplete");
    for(var client:List.of(branch,operator,reviewer)){use(client);check(get("/records/history?id="+foreignId).statusCode()==403,"cross branch trace forbidden");check(get("/internal?branch="+URLEncoder.encode("金坛",java.nio.charset.StandardCharsets.UTF_8)).statusCode()==403,"cross branch internal query forbidden");}
    for(var client:List.of(root,division,branch,operator,reviewer)){use(client);var internal=get("/internal?scope=year&year=2026&completion=incomplete");check(internal.statusCode()==200&&internal.body().contains("待配置")&&!internal.body().contains("dataset=cross"),"internal is unconfigured; cross-default belongs to risk for all roles");}
    for(var client:List.of(root,division)){use(client);check(get("/export/progress?scope=year&year=2026").statusCode()==200,"upper roles can export all progress");}
    for(var client:List.of(branch,operator,reviewer)){use(client);check(get("/export/progress?scope=year&year=2026").statusCode()==403,"branch roles cannot export progress");check(!get("/progress?scope=year&year=2026").body().contains("/export/progress"),"branch progress page has no export shortcut");}
    use(division);String home=get("/?month=2026-09").body();check(home.contains("交叉违约清单 · 未完成")&&home.contains("href=\"/progress?")&&home.contains("branch-progress"),"three risk lists and linked progress on homepage");
    check(get("/assets/business.js").statusCode()==200&&get("/details?pageSize=500").statusCode()==400,"local interaction script and page size bounds");
    use(root);check(!get(url.replace("completion=incomplete","completion=complete")).body().contains("保存资料补充"),"super remains business read only");
    use(branch);String direct=get(focus).body();form=WorkflowIntegrationHttpTest.form(direct,"/workflow/direct/preview");form.put("value_0_feedback","");preview=post("/workflow/direct/preview",form);check(post("/workflow/confirm",HttpSmokeTest.hidden(preview.body())).statusCode()==200,"direct confirmation clearing remains supported");
    use(operator);check(exportCount("incomplete")==1&&get(url).body().contains("row-pending"),"clearing last value restores incomplete list/export");
    System.out.println("BUSINESS_HTTP_OK assertions="+assertions+" A2 import to B2 focus/draft/review/list/audit/export/clear, real Main");
  }
  static String recordId(String html){String id=HttpSmokeTest.hidden(html).get("id0");if(id==null)throw new AssertionError("no formal record");return id;}
  static byte[] multiRowsWorkbook()throws Exception{DatasetSchema schema=DatasetSchema.get("multi");try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(new ExcelExporter().template("multi")))){Sheet sheet=wb.getSheet(schema.label);for(int i=0;i<25;i++){Row row=sheet.createRow(schema.headerRows+i);List<String> values=FoundationTest.candidate("multi","WUJIN","B2-HTTP-PAGE-"+i).values();for(int c=0;c<values.size();c++)row.createCell(c).setCellValue(values.get(c));}ByteArrayOutputStream out=new ByteArrayOutputStream();wb.write(out);return out.toByteArray();}}
  static int exportCount(String completion)throws Exception{
    var response=HttpSmokeTest.client.send(HttpRequest.newBuilder(URI.create(HttpSmokeTest.base+"/export?dataset=multi&scope=quarter&year=2026&quarter=3&q=B2-HTTP-FOCUS&completion="+completion)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());check(response.statusCode()==200,"authorized export endpoint");
    try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(response.body()))){return Math.max(0,wb.getSheetAt(0).getLastRowNum()+1-DatasetSchema.get("multi").headerRows);}
  }
  static void use(HttpClient c){HttpSmokeTest.client=c;}
  static HttpResponse<String> get(String url)throws Exception{return HttpSmokeTest.get(url);}
  static HttpResponse<String> post(String url,Map<String,String> fields)throws Exception{return HttpSmokeTest.post(url,fields);}
  static String csrf()throws Exception{return HttpSmokeTest.hidden(get("/").body()).get("csrf");}
  static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}

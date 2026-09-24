import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.regex.*;
import org.apache.poi.ss.usermodel.*;
import xinguan.platform.*;

/** Full Main/A1/B1 integration using the isolated HttpSmokeTest server, never user data. */
final class WorkflowIntegrationHttpTest {
  static int assertions;
  static void run(HttpClient root,HttpClient division,HttpClient branch,HttpClient operator,HttpClient reviewer)throws Exception {
    use(HttpSmokeTest.newClient());check(get("/workflow").headers().firstValue("location").orElse("").equals("/login"),"anonymous workflow blocked");
    use(root);check(get("/").body().contains("href=\"/workflow\""),"shared workflow navigation");
    check(get("/workflow").statusCode()==200&&get("/workflow/edit").statusCode()==403&&get("/workflow/reviews").statusCode()==403,"super only views workflow");
    var peer=HttpSmokeTest.create("900000051","虚构同支行同事",Role.OPERATOR,"WUJIN",csrf());
    var outsider=HttpSmokeTest.create("900000052","虚构异支行同事",Role.OPERATOR,"JINTAN",csrf());
    use(HttpSmokeTest.newClient());HttpSmokeTest.loginAndChange("900000051",peer.password());HttpClient peerClient=HttpSmokeTest.client;
    use(HttpSmokeTest.newClient());HttpSmokeTest.loginAndChange("900000052",outsider.password());HttpClient otherClient=HttpSmokeTest.client;
    use(operator);check(get("/workflow/edit?dataset=negative&organization=JINTAN").statusCode()==403,"forged editor branch rejected");
    check(get("/assets/workflow.css").statusCode()==200,"workflow local stylesheet registered");
    var fields=edit("negative");fields.put("value_0_feedback","INTEGRATION_DRAFT_ONLY <script>草稿</script>");fields.put("intent","save");
    var forged=new HashMap<>(fields);forged.put("csrf","forged");check(post("/workflow/draft/save",forged).statusCode()==403,"Main csrf gate applies to B routes");
    check(post("/workflow/direct/preview",fields).statusCode()==403,"operator cannot bypass review");
    var large=new HashMap<>(fields);large.put("oversize","x".repeat(2*1024*1024));check(post("/workflow/draft/save",large).statusCode()==413,"Main enforces workflow form limit");
    var saved=post("/workflow/draft/save",fields);check(saved.statusCode()==303,"private draft saved through real Main");
    String draftUrl=saved.headers().firstValue("location").orElseThrow();String editor=get(draftUrl).body();
    check(editor.contains("INTEGRATION_DRAFT_ONLY &lt;script&gt;"),"own draft restored and escaped");
    check(get("/details?dataset=negative&month=2026-09").body().contains("INTEGRATION_DRAFT_ONLY")&&!exportText("negative").contains("INTEGRATION_DRAFT_ONLY"),"owner can restore draft in unified table while official XLSX remains unchanged");
    use(peerClient);check(get(draftUrl).statusCode()==403,"same branch peer cannot read private draft");use(otherClient);check(get(draftUrl).statusCode()==403,"other branch cannot read private draft");
    use(root);check(get(draftUrl).statusCode()==403,"super cannot read private draft body");
    use(operator);fields=form(editor,"/workflow/draft/save");fields.put("intent","preview");
    var preview=post("/workflow/draft/save",fields);check(preview.statusCode()==200&&preview.body().contains("确认本次修改"),"server diff preview routed");
    var confirm=form(preview.body(),"/workflow/confirm");confirm.put("value_0_feedback","FORGED_CONFIRM_VALUE");
    var submitted=post("/workflow/confirm",confirm);String first=id(submitted.body());
    check(submitted.statusCode()==200&&submitted.body().contains("待支行复核")&&!submitted.body().contains("FORGED_CONFIRM_VALUE"),"confirm freezes server snapshot, ignores replacement fields");
    check(post("/workflow/confirm",confirm).statusCode()==200,"same confirmation idempotent through Main");
    check(!exportText("negative").contains("INTEGRATION_DRAFT_ONLY"),"pending snapshot excluded from export");
    use(otherClient);check(get("/workflow/submission?id="+first).statusCode()==403&&get("/audit?submissionId="+first).statusCode()==403,"cross branch snapshot and audit target blocked");
    use(reviewer);String notices=get("/notifications?unread=yes").body();String notice=match(notices,"/notifications/open[\\s\\S]*?name=\"id\" value=\"([^\"]+)\"");String detail=get("/notifications/detail?id="+notice).body();
    check(detail.contains("/workflow/submission?id="+first),"A1 notification links actual B1 submission");
    Map<String,String> open=HttpSmokeTest.hidden(notices);open.put("id",notice);var fromNotice=post("/notifications/open",open);check(fromNotice.statusCode()==303&&fromNotice.headers().firstValue("location").orElse("").contains("return=%2Fnotifications"),"notification open marks read and carries list origin to submission");check(get(fromNotice.headers().firstValue("location").orElseThrow()).body().contains("data-back=\"fixed\" href=\"/notifications?unread=yes"),"submission opened from notice returns to notification list");
    String queue=get("/workflow/reviews").body();check(queue.contains(first.substring(0,8))&&!queue.contains("<option value=\"APPROVED\""),"read notice does not clear pending review; filter markup valid");
    var rejection=form(get("/workflow/submission?id="+first).body(),"/workflow/review/reject");rejection.put("reason","");var reasonPage=post("/workflow/review/reject",rejection);check(reasonPage.statusCode()==200&&reasonPage.body().contains("填写退回原因")&&reasonPage.body().contains("name=\"reason\"")&&reasonPage.body().contains("data-back=\"fixed\" href=\"/workflow/submission?id=")&&!exportText("negative").contains("INTEGRATION_DRAFT_ONLY"),"missing popup reason opens safe reason page, returns to submission, and leaves official data unchanged");
    rejection.put("reason","补充 <核验> 内容");check(post("/workflow/review/reject",rejection).statusCode()==200,"reviewer returns through Main");
    check(!exportText("negative").contains("INTEGRATION_DRAFT_ONLY"),"returned snapshot excluded from export");
    use(operator);String returnedDetails=get("/details?dataset=negative&month=2026-09").body();check(returnedDetails.contains("INTEGRATION_DRAFT_ONLY &lt;script&gt;草稿&lt;/script&gt;")&&returnedDetails.contains("补充 &lt;核验&gt; 内容")&&!returnedDetails.contains("查看提交回执")&&!returnedDetails.contains("核对后原值重提"),"returned operator row keeps prior proposal and shows the reason without receipt/reconfirm controls");
    String returned=get("/workflow/submission?id="+first).body();String resume=unescape(match(returned,"href=\"([^\"]+)\">恢复草稿并修订"));
    String resumed=get(resume).body();check(resumed.contains("name=\"priorSubmissionId\" value=\""+first+"\""),"difference editor retains returned submission lineage");
    fields=form(resumed,"/workflow/draft/save");fields.put("value_0_feedback","INTEGRATION_APPROVED <核验完成>");fields.put("intent","preview");
    String preview2=post("/workflow/draft/save",fields).body();String second=id(post("/workflow/confirm",form(preview2,"/workflow/confirm")).body());
    check(!second.equals(first)&&get("/workflow/submission?id="+second).body().contains(first),"resubmission creates new linked immutable record");
    use(reviewer);var approval=form(get("/workflow/submission?id="+second).body(),"/workflow/review/approve");
    check(post("/workflow/review/approve",approval).statusCode()==200&&post("/workflow/review/approve",approval).statusCode()==200&&!exportText("negative").contains("INTEGRATION_APPROVED"),"branch approval succeeds with safe retry but does not publish");
    use(division);check(post("/workflow/review/approve",form(get("/workflow/submission?id="+second).body(),"/workflow/review/approve")).statusCode()==200,"division final approval publishes operator snapshot");
    check(exportText("negative").contains("INTEGRATION_APPROVED")&&get("/details?dataset=negative&month=2026-09").body().contains("row-complete"),"approved snapshot reaches formal completion and real Excel export");
    check(!get("/?month=2026-09").body().contains("INTEGRATION_APPROVED"),"completed row leaves homepage incomplete preview");
    String audit=get("/audit?submissionId="+second).body();check(audit.contains("INTEGRATION_APPROVED &lt;核验完成&gt;")&&!audit.contains("INTEGRATION_DRAFT_ONLY")&&audit.contains("name=\"submissionId\""),"public audit link is exact and preserves scope on filtering");
    check(get("/audit?submissionId="+UUID.randomUUID()).statusCode()==403,"unknown audit submission is indistinguishable from unauthorized, never unfiltered data");
    use(root);check(get("/workflow/submission?id="+second).statusCode()==200&&get("/audit?submissionId="+second).statusCode()==200,"super authorized read-only cross-module trace");
    check(post("/workflow/review/approve",Map.of("csrf",csrf(),"submissionId",second,"requestId",UUID.randomUUID().toString())).statusCode()==403,"super cannot approve workflow");
    use(division);check(get("/workflow/edit?dataset=multi&organization=WUJIN&from=2026-09-01&through=2026-09-30").statusCode()==403,"division has no direct editor");
    use(branch);check(get("/workflow/edit?dataset=multi&organization=WUJIN").statusCode()==403,"branch manager cannot bypass initial operator stage");
    use(reviewer);check(get("/workflow/edit?dataset=multi&organization=WUJIN").statusCode()==403,"reviewer cannot edit initial records before operator submission");
    use(division);check(post("/workflow/direct/preview",Map.of("csrf",csrf(),"dataset","multi","rows","0")).statusCode()==403,"obsolete direct preview endpoint is closed");
    use(operator);fields=edit("cross");fields.put("value_0_cross_feedback","INTEGRATION_INTERNAL_APPROVED");fields.put("intent","preview");var internal=post("/workflow/draft/save",fields);String internalId=id(post("/workflow/confirm",form(internal.body(),"/workflow/confirm")).body());
    use(reviewer);check(post("/workflow/review/approve",form(get("/workflow/submission?id="+internalId).body(),"/workflow/review/approve")).statusCode()==200&&!exportText("cross").contains("INTEGRATION_INTERNAL_APPROVED"),"branch approval stages internal data for division");
    use(division);check(post("/workflow/review/approve",form(get("/workflow/submission?id="+internalId).body(),"/workflow/review/approve")).statusCode()==200&&exportText("cross").contains("INTEGRATION_INTERNAL_APPROVED"),"division final approval publishes internal data");
    use(otherClient);check(!exportText("cross").contains("INTEGRATION_INTERNAL_APPROVED"),"internal export branch isolation");
    use(operator);check(get("/workflow/drafts").statusCode()==200&&get("/workflow").body().contains("/workflow/drafts"),"all private drafts accessible from workbench");
    use(root);check(get("/workflow/drafts").statusCode()==403,"all draft list remains private");
    System.out.println("WORKFLOW_INTEGRATION_HTTP_OK assertions="+assertions+" real Main, five roles, drafts, returns, approvals, notices, scoped audit and XLSX");
  }
  static Map<String,String> edit(String dataset)throws Exception {
    String html=get("/workflow/edit?dataset="+dataset+"&organization=WUJIN&from=2026-09-01&through=2026-09-30").body();
    return form(html,html.contains("action=\"/workflow/direct/preview\"")?"/workflow/direct/preview":"/workflow/draft/save");
  }
  static Map<String,String> form(String html,String action) {
    Matcher forms=Pattern.compile("<form\\b[^>]*action=\""+Pattern.quote(action)+"\"[^>]*>(.*?)</form>",Pattern.DOTALL).matcher(html);
    if(!forms.find())throw new AssertionError("missing form "+action+"; page starts "+html.substring(0,Math.min(100,html.length())));
    return parseForm(forms.group(1));
  }
  static Map<String,String> formLast(String html,String action) {
    Matcher forms=Pattern.compile("<form\\b[^>]*action=\""+Pattern.quote(action)+"\"[^>]*>(.*?)</form>",Pattern.DOTALL).matcher(html);String content=null;
    while(forms.find())content=forms.group(1);
    if(content==null)throw new AssertionError("missing form "+action+"; page starts "+html.substring(0,Math.min(100,html.length())));
    return parseForm(content);
  }
  private static Map<String,String> parseForm(String content) {
    Map<String,String> values=HttpSmokeTest.hidden(content);
    Matcher text=Pattern.compile("<textarea[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</textarea>",Pattern.DOTALL).matcher(content);while(text.find())values.put(text.group(1),unescape(text.group(2)));
    Matcher selects=Pattern.compile("<select[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</select>",Pattern.DOTALL).matcher(content);
    while(selects.find()){String value="";Matcher options=Pattern.compile("<option value=\"([^\"]*)\"([^>]*)>").matcher(selects.group(2));while(options.find())if(options.group(2).contains("selected"))value=unescape(options.group(1));values.put(selects.group(1),value);}
    return values;
  }
  static String exportText(String dataset)throws Exception {
    var response=HttpSmokeTest.client.send(HttpRequest.newBuilder(URI.create(HttpSmokeTest.base+"/export?dataset="+dataset+"&month=2026-09")).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
    check(response.statusCode()==200,"authorized XLSX response");StringBuilder text=new StringBuilder();try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(response.body()))){for(Sheet sheet:book)for(Row row:sheet)for(Cell cell:row)text.append(cell.toString()).append('\n');}return text.toString();
  }
  static String csrf()throws Exception{return HttpSmokeTest.hidden(get("/").body()).get("csrf");}
  static String id(String html){return match(html,"单号 ([A-Za-z0-9-]{10,})");}
  static String match(String html,String regex){Matcher m=Pattern.compile(regex).matcher(html);if(!m.find())throw new AssertionError("missing "+regex);return m.group(1);}
  static String unescape(String s){return s.replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'").replace("&amp;","&");}
  static void use(HttpClient client){HttpSmokeTest.client=client;}
  static HttpResponse<String> get(String path)throws Exception{return HttpSmokeTest.get(path);}
  static HttpResponse<String> post(String path,Map<String,String> fields)throws Exception{return HttpSmokeTest.post(path,fields);}
  static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}

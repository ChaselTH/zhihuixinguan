import java.net.http.*;
import java.util.*;
import xinguan.platform.*;

/** Real Main/CSRF/HTML/XLSX paths; fixtures are generated and stay on a temporary loopback server. */
final class Feedback009HttpReviewTest {
  static int checks;
  static void verify(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
  static void run(HttpClient root,HttpClient div,HttpClient branch,HttpClient op,HttpClient review)throws Exception {
    use(root);var account=HttpSmokeTest.create("900000096","合成异支行复现账号",Role.OPERATOR,"JINTAN",csrf());
    use(HttpSmokeTest.newClient());HttpSmokeTest.loginAndChange("900000096",account.password());HttpClient other=HttpSmokeTest.client;
    for(String dataset:List.of("multi","negative","cross")){
      String marker="F009-HTTP-SECRET-"+dataset;use(div);
      var upload=HttpSmokeTest.upload(dataset,HttpSmokeTest.workbook(dataset,"WUJIN",marker),csrf());
      verify(upload.statusCode()==200,"seed preview");
      verify(post("/imports/confirm",Map.of("csrf",csrf(),"token",HttpSmokeTest.hidden(upload.body()).get("token"),"mode","preserve")).statusCode()==303,"seed confirmation");
      use(op);String html=get("/details?dataset="+dataset+"&month=2026-09&q="+marker+"&draft=new").body();
      // Focus on the generated row using the actual rendered identifier.
      // Locate by the source marker in the row rather than assuming list order.
      String rowHtml=match(html,"(?s)(<tr[^>]*>(?:(?!<tr).)*"+marker+"(?:(?!</tr>).)*</tr>)");
      String record=match(rowHtml,"name=\"id\\d+\" value=\"([^\"]+)\"");
      html=get("/workflow/edit?dataset="+dataset+"&organization=WUJIN&record="+record).body();var edit=form(html,"/workflow/draft/save");
      edit.put("value_0_"+(dataset.equals("cross")?"cross_feedback":"feedback"),marker+"-PROPOSED");edit.put("intent","save");
      var saved=post("/workflow/draft/save",edit);verify(saved.statusCode()==303,"save private draft");String draftUrl=saved.headers().firstValue("location").orElseThrow();
      edit=form(get(draftUrl).body(),"/workflow/draft/save");edit.put("intent","preview");var preview=post("/workflow/draft/save",edit);verify(preview.statusCode()==200,"preview draft");
      var confirmation=form(preview.body(),"/workflow/confirm");String oldPreview=confirmation.get("previewId");String submission=id(post("/workflow/confirm",confirmation).body());
      use(review);var approve=form(get("/workflow/submission?id="+submission).body(),"/workflow/review/approve");verify(post("/workflow/review/approve",approve).statusCode()==200,"branch review");
      verify(post("/workflow/review/approve",approve).statusCode()==200,"duplicate branch click is safe after receipt is cropped");
      for(HttpClient actor:List.of(op,branch,review)){
        use(actor);var receipt=get("/workflow/submission?id="+submission);verify(receipt.statusCode()==200&&!receipt.body().contains(marker)&&receipt.body().contains("待分行终审"),"division-stage progress must contain no business values");
        verify(!get("/workflow/submissions").body().contains(marker),"submission list omits values");
        verify(!get("/details?dataset="+dataset+"&month=2026-09&completion=all").body().contains(marker),"all filter cannot reveal division row");
        verify(!exportText(dataset).contains(marker),"XLSX excludes division row");
      }
      use(op);verify(get("/workflow/preview?id="+oldPreview).statusCode()==403,"old preview URL denied");verify(!get(draftUrl).body().contains(marker),"old consumed draft URL contains no snapshot");
      verify(!post("/workflow/confirm",confirmation).body().contains(marker),"confirmation replay returns safe receipt");
      use(other);verify(get("/workflow/submission?id="+submission).statusCode()==403,"other branch receipt denied");
      use(root);verify(get("/workflow/submission?id="+submission).body().contains(marker+"-PROPOSED"),"super can inspect but not decide");
      use(div);verify(!exportText(dataset).contains(marker+"-PROPOSED"),"formal export unchanged before final review");verify(post("/workflow/review/approve",form(get("/workflow/submission?id="+submission).body(),"/workflow/review/approve")).statusCode()==200,"division publishes");
      for(HttpClient actor:List.of(op,branch,review)){use(actor);verify(!get("/workflow/submission?id="+submission).body().contains(marker)&&!exportText(dataset).contains(marker),"published details and export remain hidden");}
      use(op);String notices=get("/notifications").body();verify(!notices.contains(marker),"notification body contains no business values");var open=form(notices,"/notifications/open");var opened=post("/notifications/open",open);verify(opened.statusCode()==303&&!get(opened.headers().firstValue("location").orElseThrow()).body().contains(marker),"notification link reaches receipt only");
      use(div);var reopen=form(get("/workflow/reopen?record="+record).body(),"/workflow/reopen");reopen.put("confirm","yes");reopen.put("reason","需支行重新核对");var reopened=post("/workflow/reopen",reopen);verify(reopened.statusCode()==303,"completed row is first routed to branch reviewer");String reopenedId=match(reopened.headers().firstValue("location").orElseThrow(),"id=([^&]+)");
      use(op);verify(get("/workflow/reconfirm?record="+record).statusCode()==403,"obsolete same-value resubmission is closed");verify(get("/workflow/edit?dataset="+dataset+"&organization=WUJIN&record="+record).statusCode()==403,"operator cannot edit while reopened task belongs to reviewer");
      use(review);var returnForm=form(get("/workflow/submission?id="+reopenedId).body(),"/workflow/review/reject");returnForm.put("reason","请原操作员补充核验");verify(post("/workflow/review/reject",returnForm).statusCode()==200,"reviewer may return reopened row to original operator");
      use(op);var restore=form(get("/workflow/submission?id="+reopenedId).body(),"/workflow/returned/restore");var restored=post("/workflow/returned/restore",restore);verify(restored.statusCode()==303&&restored.headers().firstValue("location").orElse("").contains("return=%2Fworkflow%2Fsubmission"),"operator restores returned snapshot into private draft and keeps the submission as the back target");
      var restoredForm=form(get(restored.headers().firstValue("location").orElseThrow()).body(),"/workflow/draft/save");restoredForm.put("value_0_"+(dataset.equals("cross")?"cross_feedback":"feedback"),marker+"-REVISED-AFTER-RETURN");restoredForm.put("intent","preview");var revised=post("/workflow/draft/save",restoredForm);verify(revised.statusCode()==200&&revised.body().contains(marker+"-REVISED-AFTER-RETURN"),"restored returned row accepts a real correction");
      String next=id(post("/workflow/confirm",form(revised.body(),"/workflow/confirm")).body());verify(!next.equals(submission),"returned correction creates a new submission");use(review);verify(post("/workflow/review/approve",form(get("/workflow/submission?id="+next).body(),"/workflow/review/approve")).statusCode()==200,"reviewer approves corrected row");use(div);verify(post("/workflow/review/approve",form(get("/workflow/submission?id="+next).body(),"/workflow/review/approve")).statusCode()==200,"division publishes corrected row");
      reopen=form(get("/workflow/reopen?record="+record).body(),"/workflow/reopen");reopen.put("confirm","yes");reopen.put("reason","复核员直接补充");reopened=post("/workflow/reopen",reopen);verify(reopened.statusCode()==303,"second reopen routes to reviewer");reopenedId=match(reopened.headers().firstValue("location").orElseThrow(),"id=([^&]+)");
      use(review);String reviewHtml=get("/workflow/submission?id="+reopenedId).body();String editUrl=WorkflowIntegrationHttpTest.unescape(match(reviewHtml,"href=\"([^\"]+)\">自行修改后送分行"));var reviewEdit=form(get(editUrl).body(),"/workflow/review/revise");reviewEdit.put("value_"+(dataset.equals("cross")?"cross_feedback":"feedback"),marker+"-REVIEWER-REVISED");reviewEdit.put("intent","submit");var reviewSubmitted=post("/workflow/review/revise",reviewEdit);verify(reviewSubmitted.statusCode()==200,"reviewer may correct a division-returned row and resubmit");use(div);String reviewSubmission=id(reviewSubmitted.body());verify(post("/workflow/review/approve",form(get("/workflow/submission?id="+reviewSubmission).body(),"/workflow/review/approve")).statusCode()==200,"division publishes reviewer correction");
    }
    System.out.println("FEEDBACK009_HTTP_REVIEW_OK checks="+checks+" five roles, three datasets, scoped receipts, reviewer-first returns, private draft and reviewer correction");
  }
  static void use(HttpClient client){HttpSmokeTest.client=client;}
  static HttpResponse<String> get(String path)throws Exception{return HttpSmokeTest.get(path);}
  static HttpResponse<String> post(String path,Map<String,String> fields)throws Exception{return HttpSmokeTest.post(path,fields);}
  static Map<String,String> form(String html,String action){return WorkflowIntegrationHttpTest.form(html,action);}
  static String match(String html,String regex){return WorkflowIntegrationHttpTest.match(html,regex);}
  static String id(String html){return WorkflowIntegrationHttpTest.id(html);}
  static String csrf()throws Exception{return WorkflowIntegrationHttpTest.csrf();}
  static String exportText(String dataset)throws Exception{return WorkflowIntegrationHttpTest.exportText(dataset);}
}

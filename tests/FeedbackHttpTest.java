import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import xinguan.platform.*;

final class FeedbackHttpTest {
  static int assertions;
  static void run(HttpClient root,HttpClient division,HttpClient branch,HttpClient operator,HttpClient reviewer)throws Exception{
    use(HttpSmokeTest.newClient());check(get("/deadlines").statusCode()==303,"deadline page requires login");
    use(division);
    var uploaded=ImportHttpTest.upload(List.of(HttpSmokeTest.workbook("multi","WUJIN","FEEDBACK-HTTP")),List.of("deadline-fixture.xlsx"),csrf());
    String token=HttpSmokeTest.hidden(uploaded.body()).get("token");
    check(post("/imports/confirm-bulk",Map.of("csrf",csrf(),"token",token,"revision","1","mode","preserve","confirmed","yes")).statusCode()==303,"synthetic record created");
    String period="2026-09";
    var fields=new HashMap<>(Map.of("dataset","multi","period",period,"revision","0","dueDate",LocalDate.now(FeedbackTiming.ZONE).plusDays(1).toString(),"action","save","scope","month","month","2026-09"));
    for(var client:List.of(branch,operator,reviewer)){
      use(client);check(get("/deadlines?month=2026-09").statusCode()==200&&!get("/deadlines?month=2026-09").body().contains("/deadlines/save"),"branch reads own deadlines without editing");
      fields.put("csrf",csrf());check(post("/deadlines/save",fields).statusCode()==403,"forged branch setting denied");
    }
    use(root);fields.put("csrf","forged");check(post("/deadlines/save",fields).statusCode()==403,"deadline setting requires CSRF");
    fields.put("csrf",csrf());String original=fields.get("dueDate");fields.put("dueDate","2026-02-30");check(post("/deadlines/save",fields).statusCode()==400,"invalid calendar date rejected");fields.put("dueDate",original);
    for(var manager:List.of(root,division)){
      use(manager);fields.put("csrf",csrf());
      for(int offset:List.of(-1,0)){
        fields.put("dueDate",LocalDate.now(FeedbackTiming.ZONE).plusDays(offset).toString());
        var rejected=post("/deadlines/save",fields);
        check(rejected.statusCode()==400&&rejected.body().contains("今天之后"),"both managers reject yesterday and today on server");
      }
    }
    use(root);fields.put("csrf",csrf());fields.put("dueDate",original);
    check(post("/deadlines/save",fields).statusCode()==303,"super admin may set tomorrow");
    String detail="/details?dataset=multi&scope=month&month=2026-09&period="+URLEncoder.encode(period,StandardCharsets.UTF_8)+"&completion=overdue&q=FEEDBACK-HTTP";
    use(operator);String html=get(detail).body();check(!html.contains("虚构测试企业 FEEDBACK-HTTP"),"future deadline excluded from overdue route");
    String all=get(detail.replace("completion=overdue","completion=all")).body();
    check(all.contains("虚构测试企业 FEEDBACK-HTTP")&&all.contains(original)&&!all.contains("! 超期反馈"),"future deadline remains visible without overdue marking");
    check(get("/deadlines?month=2026-09").body().contains("最早明天"),"page explains the minimum date");
    check(get("/?month=2026-09").body().contains("本期反馈时间"),"home reminder visible");
    use(division);fields.put("csrf",csrf());fields.put("dueDate","2099-12-31");check(post("/deadlines/save",fields).statusCode()==409,"stale admin version cannot overwrite");
    fields.put("revision","1");check(post("/deadlines/save",fields).statusCode()==303,"division may extend deadline");
    use(operator);check(!get(detail).body().contains("虚构测试企业 FEEDBACK-HTTP"),"extension removes row from overdue-only list");
    use(root);fields.put("csrf",csrf());fields.put("revision","2");fields.put("action","clear");
    check(post("/deadlines/save",fields).statusCode()==303,"explicit cancellation clears configuration");
    check(get("/audit?category=business&search=FEEDBACK_DEADLINE_SET").body().contains("FEEDBACK_DEADLINE_SET"),"deadline changes leave shared audit trail");
    System.out.println("FEEDBACK_HTTP_OK assertions="+assertions+" five roles, CSRF, strict dates and tomorrow minimum, stale version, filtered view, extension, clear and audit");
  }
  static void use(HttpClient c){HttpSmokeTest.client=c;}
  static HttpResponse<String> get(String path)throws Exception{return HttpSmokeTest.get(path);}
  static HttpResponse<String> post(String path,Map<String,String> fields)throws Exception{return HttpSmokeTest.post(path,fields);}
  static String csrf()throws Exception{return HttpSmokeTest.hidden(get("/").body()).get("csrf");}
  static void check(boolean v,String label){assertions++;if(!v)throw new AssertionError(label);}
}

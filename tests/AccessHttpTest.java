import java.net.http.*;
import java.util.*;
import java.util.regex.*;
import xinguan.platform.*;

/** A1 browser-independent HTTP acceptance; called with isolated HttpSmokeTest accounts. */
final class AccessHttpTest {
  static int assertions;
  static void run(HttpClient root,HttpClient division,HttpClient branch,HttpClient operator)throws Exception {
    use(HttpSmokeTest.newClient());
    check(get("/login").body().contains("href=\"/access/apply\""),"login has permission application entry");
    String apply=get("/access/apply?scope=branch").body();check(Organizations.BRANCHES.keySet().stream().allMatch(org->apply.contains("value=\""+org+"\"")),"all nine branch options");
    check(!get("/access/apply?scope=division").body().contains("<select name=\"organization\""),"division application needs no branch");
    check(post("/access/apply",Map.of("csrf","forged","number","870000001","name","虚构申请","organization","WUJIN")).statusCode()==403,"anonymous application csrf");
    apply("870000001","虚构 <script>申请</script>","WUJIN");
    use(root);String list=get("/access/requests").body();String request=request(list,"870000001");
    check(list.contains("&lt;script&gt;")&&!list.contains("<script>申请"),"application name escaped");
    check(get("/").body().contains("notice-badge"),"global header unread badge");
    String notices=get("/notifications").body();String notice=match(notices,"/notifications/open[\\s\\S]*?name=\"id\" value=\"([^\"]+)\"");
    check(get("/notifications/detail?id="+notice).body().contains("/access/request?id="+request),"notice links authorized application");
    check(get("/notifications/detail").statusCode()==403,"missing notice does not produce server error");
    use(operator);check(get("/access/requests").statusCode()==403&&get("/access/request?id="+request).statusCode()==403,"operator cannot view identity applications");
    check(get("/notifications/detail?id="+notice).statusCode()==403,"personal notice detail isolation");
    check(get("/audit?category=security").statusCode()==403,"operator cannot read account logs");
    check(get("/audit?organization=JINTAN").statusCode()==403,"audit forged branch blocked");
    String audit=get("/audit").body();check(audit.contains("HTTP 虚构反馈 &lt;script&gt;")&&!audit.contains("<script>test</script>")&&!audit.contains("OTHER"),"business before after escaped and scoped");
    check(get("/audit?from=wrong").statusCode()==400,"invalid date friendly response");
    use(branch);String form=get("/access/request?id="+request).body();Map<String,String> fields=HttpSmokeTest.hidden(form);
    check(form.contains("<option value=\"\">请选择角色</option>"),"approval requires explicit role selection");
    Map<String,String> emptyRole=new HashMap<>(fields);emptyRole.put("action","APPROVE");emptyRole.put("role","");check(post("/access/decision",emptyRole).statusCode()==400,"empty approval role rejected");
    fields.put("action","APPROVE");fields.put("role","BRANCH_ADMIN");fields.put("reason","");
    check(post("/access/decision",fields).statusCode()==403,"branch cannot approve higher role");
    fields.put("role","OPERATOR");String csrf=fields.put("csrf","bad");check(post("/access/decision",fields).statusCode()==403,"approval csrf enforced");fields.put("csrf",csrf);
    var approved=post("/access/decision",fields);check(approved.statusCode()==200&&approved.body().contains("initialPassword"),"approve returns one-time credential");
    check(approved.headers().firstValue("Cache-Control").orElse("").equals("no-store"),"credential response no-store");
    String password=match(approved.body(),"id=\"initialPassword\"[^>]+value=\"([^\"]+)\"");
    check(post("/access/decision",fields).statusCode()==303,"double submit idempotent without password redisplay");
    check(!get("/access/request?id="+request).body().contains(password)&&!get("/audit?category=security").body().contains(password)&&!get("/notifications").body().contains(password),"credentials absent from persisted pages");
    use(HttpSmokeTest.newClient());HttpSmokeTest.loginAndChange("870000001",password);
    check(get("/access/requests").statusCode()==403&&get("/audit").statusCode()==200,"approved applicant login role works after first password and safety");
    use(HttpSmokeTest.newClient());apply("870000002","虚构分行申请","CZ");
    use(root);String divisionRequest=request(get("/access/requests").body(),"870000002");
    use(division);check(get("/access/request?id="+divisionRequest).statusCode()==403,"division cannot approve division account");
    use(root);HttpSmokeTest.create("870000010","虚构金坛管理员",Role.BRANCH_ADMIN,"JINTAN",HttpSmokeTest.hidden(get("/people").body()).get("csrf"));
    use(HttpSmokeTest.newClient());apply("870000003","虚构金坛申请","JINTAN");
    use(root);String other=request(get("/access/requests").body(),"870000003");
    use(branch);check(get("/access/request?id="+other).statusCode()==403&&!get("/access/requests?all=yes").body().contains("870000003"),"branch application isolation");
    use(division);Map<String,String> rejection=HttpSmokeTest.hidden(get("/access/request?id="+other).body());rejection.put("action","REJECT");rejection.put("reason","");
    check(post("/access/decision",rejection).statusCode()==400,"rejection requires reason");rejection.put("reason","需重新核实身份 <script>说明</script>");
    check(post("/access/decision",rejection).statusCode()==303&&get("/access/request?id="+other).body().contains("&lt;script&gt;"),"rejection reason persisted and escaped");
    use(root);long unreadBefore=Long.parseLong(match(get("/notifications").body(),"未读 ([0-9]+) 条"));check(get("/notifications/detail?id="+notice).statusCode()==200&&Long.parseLong(match(get("/notifications").body(),"未读 ([0-9]+) 条"))==unreadBefore,"direct detail GET does not mutate read state");Map<String,String> read=HttpSmokeTest.hidden(get("/notifications/detail?id="+notice).body());read.put("id",notice);read.put("csrf",HttpSmokeTest.hidden(get("/notifications").body()).get("csrf"));check(post("/notifications/open",read).statusCode()==303,"personal open marks read and targets application");
    check(!get("/notifications?unread=yes").body().contains("/notifications/detail?id="+notice),"unread filter honors receipt");
    check(get("/assets/access.css").statusCode()==200,"offline A1 stylesheet available");
    use(HttpSmokeTest.newClient());check(post("/access/apply",Map.of("csrf",HttpSmokeTest.hidden(get("/access/apply?scope=branch").body()).get("csrf"),"number","870000001","name","重复已有账号","organization","WUJIN","requestedRole","OPERATOR")).statusCode()==400,"duplicate identity gives explicit generic error");apply("870000004","虚构限流前","WUJIN");
    Map<String,String> limited=HttpSmokeTest.hidden(get("/access/apply").body());limited.put("number","870000005");limited.put("name","虚构限流");limited.put("organization","WUJIN");limited.put("requestedRole","OPERATOR");
    check(post("/access/apply",limited).statusCode()==429,"anonymous requests rate limited");
    use(root);check(!get("/access/requests").body().contains("870000005"),"rate limit prevents persistence");
    System.out.println("ACCESS_HTTP_OK assertions="+assertions+" forms, csrf, scopes, one-time password, safety and anonymous throttling");
  }
  static void apply(String number,String name,String org)throws Exception {Map<String,String> f=HttpSmokeTest.hidden(get("/access/apply?scope="+(org.equals("CZ")?"division":"branch")).body());f.put("number",number);f.put("name",name);f.put("organization",org);f.put("requestedRole",org.equals("CZ")?"DIVISION_ADMIN":"OPERATOR");var result=post("/access/apply",f);check(result.statusCode()==303&&result.headers().firstValue("location").orElse("").equals("/access/received"),"generic application receipt");check(post("/access/apply",f).statusCode()==403,"application csrf is single-use");check(get("/access/received").body().contains("申请已接收"),"public receipt contains no credential or application status");}
  static String request(String html,String number){for(String row:html.split("</tr>"))if(row.contains(number))return match(row,"/access/request\\?id=([^\"]+)");throw new AssertionError("application not listed");}
  static String match(String text,String pattern){Matcher m=Pattern.compile(pattern).matcher(text);if(!m.find())throw new AssertionError("missing expected element: "+pattern);return m.group(1);}
  static void use(HttpClient client){HttpSmokeTest.client=client;}
  static HttpResponse<String> get(String path)throws Exception{return HttpSmokeTest.get(path);}
  static HttpResponse<String> post(String path,Map<String,String> fields)throws Exception{return HttpSmokeTest.post(path,fields);}
  static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}

import java.net.http.*;
import java.util.*;
import xinguan.platform.*;

/** Real HTTP forms against the isolated synthetic HttpSmokeTest server; never deletes local users. */
final class PeopleDeleteHttpTest {
  static int assertions;
  static void run(HttpClient root,HttpClient division,HttpClient branch,HttpClient operator,HttpClient reviewer)throws Exception{
    use(root);
    var own=HttpSmokeTest.create("908601001","虚构删除 <script>姓名</script>",Role.OPERATOR,"WUJIN",csrf());
    var foreign=HttpSmokeTest.create("908601002","虚构异支行",Role.OPERATOR,"JINTAN",csrf());
    var peer=HttpSmokeTest.create("908601003","虚构分行管理员",Role.DIVISION_ADMIN,"CZ",csrf());
    var sub=HttpSmokeTest.create("908601004","虚构中吴管理员",Role.BRANCH_ADMIN,"ZHONGWU",csrf());
    var review=HttpSmokeTest.create("908601005","虚构待停用复核员",Role.REVIEWER,"WUJIN",csrf());
    String path="/people/delete?id="+own.id();
    check(!row(get("/people").body(),"900000001").contains("/people/delete"),"super admin has no self delete button");
    var stale=HttpSmokeTest.hidden(get(path).body());
    for(var manager:List.of(root,division,branch)){
      use(manager);String list=get("/people").body();
      check(row(list,"908601001").contains(path)&&row(list,"908601001").contains("删除人员"),"visible direct delete button for each authorized manager");
      String confirm=get(path).body();check(confirm.contains("删除人员确认")&&confirm.contains("908601001")&&confirm.contains("武进")&&confirm.contains("保留")&&confirm.contains("&lt;script&gt;姓名&lt;/script&gt;")&&!confirm.contains("<script>姓名</script>"),"confirmation identifies target and escapes user text");
    }
    use(HttpSmokeTest.newClient());check(get(path).statusCode()==303,"anonymous confirmation requires login");
    for(var worker:List.of(operator,reviewer)){
      use(worker);check(get(path).statusCode()==403,"non-manager confirmation forbidden");
      check(post("/people/disable",Map.of("csrf",csrf(),"id",own.id(),"revision","1","confirmDisable","yes")).statusCode()==403,"non-manager forged delete forbidden");
    }
    use(branch);check(get("/people/delete?id="+foreign.id()).statusCode()==403&&!get("/people").body().contains("908601002"),"foreign branch target absent and forbidden");
    check(post("/people/disable",Map.of("csrf",csrf(),"id",foreign.id(),"revision","1","confirmDisable","yes")).statusCode()==403,"foreign branch post forbidden");
    use(division);check(get("/people/delete?id="+peer.id()).statusCode()==403,"division cannot delete peer");
    check(post("/people/disable",Map.of("csrf",csrf(),"id",peer.id(),"revision","1","confirmDisable","yes")).statusCode()==403,"division peer post forbidden");
    use(HttpSmokeTest.newClient());HttpSmokeTest.loginAndChange("908601001",own.password());HttpClient targetSession=HttpSmokeTest.client;
    check(get("/").statusCode()==200,"opening confirmation did not deactivate target");
    use(root);stale.put("csrf",csrf());check(post("/people/disable",stale).statusCode()==409,"changed target revision blocks stale confirmation");
    var current=HttpSmokeTest.hidden(get(path).body());var missing=new HashMap<>(current);missing.remove("confirmDisable");
    check(post("/people/disable",missing).statusCode()==400,"explicit confirmation required");
    var forged=new HashMap<>(current);forged.put("csrf","wrong");check(post("/people/disable",forged).statusCode()==403,"delete requires CSRF");
    use(targetSession);check(get("/").statusCode()==200,"failed deletes leave session active");
    use(branch);current.put("csrf",csrf());var removed=post("/people/disable",current);
    check(removed.statusCode()==303&&removed.headers().firstValue("location").orElse("").equals("/people?deleted=yes"),"branch manager confirms own operator deletion");
    String list=get("/people?deleted=yes").body();String deleted=row(list,"908601001");
    check(list.contains("人员已删除（账号已停用）")&&deleted.contains("已停用")&&!deleted.contains(path)&&deleted.contains("/people/edit"),"deleted user retained without repeated delete button and may be restored");
    check(get(path).body().contains("无需重复删除")&&!get(path).body().contains("action=\"/people/disable\""),"inactive target confirmation does not offer deletion again");
    check(post("/people/disable",current).statusCode()==409,"repeat confirmation cannot write twice");
    var audit=get("/audit?category=security&search="+own.id());
    check(audit.statusCode()==200&&audit.body().contains("停用人员")&&audit.body().contains(own.id()),"shared scoped audit records deletion");
    use(targetSession);check(get("/").statusCode()==303,"deletion revokes existing target session");
    use(branch);confirmDelete(review.id());use(division);confirmDelete(sub.id());use(root);confirmDelete(peer.id());
    var restore=HttpSmokeTest.hidden(get("/people/edit?id="+own.id()).body());restore.put("name","虚构恢复人员");restore.put("role","OPERATOR");restore.put("organization","WUJIN");restore.put("active","true");
    check(post("/people/update",restore).statusCode()==303&&row(get("/people").body(),"908601001").contains(path),"existing user edit can restore account and delete button");
    System.out.println("PEOPLE_DELETE_HTTP_OK assertions="+assertions+" confirmation, five-role scopes, CSRF, revisions, session revocation, retained history and restoration");
  }
  static void confirmDelete(String id)throws Exception{var f=HttpSmokeTest.hidden(get("/people/delete?id="+id).body());check(post("/people/disable",f).statusCode()==303,"authorized manager may delete subordinate role");}
  static String row(String html,String number){for(String row:html.split("</tr>"))if(row.contains("<td>"+number+"</td>"))return row;throw new AssertionError("Missing synthetic user "+number);}
  static void use(HttpClient c){HttpSmokeTest.client=c;}
  static HttpResponse<String> get(String path)throws Exception{return HttpSmokeTest.get(path);}
  static HttpResponse<String> post(String path,Map<String,String> f)throws Exception{return HttpSmokeTest.post(path,f);}
  static String csrf()throws Exception{return HttpSmokeTest.hidden(get("/").body()).get("csrf");}
  static void check(boolean v,String message){assertions++;if(!v)throw new AssertionError(message);}
}

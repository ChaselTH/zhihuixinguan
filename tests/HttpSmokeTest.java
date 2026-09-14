import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.apache.poi.ss.usermodel.*;
import xinguan.platform.*;

/** Runs an isolated real HTTP server against synthetic data; never connects to the deployment host. */
public final class HttpSmokeTest {
  static int assertions;static HttpClient client;static String base;
  public static void main(String[] args)throws Exception{
    Path app=Path.of(args[0]),data=Files.createTempDirectory("zhihuixinguan-http-test-");
    String password=UUID.randomUUID().toString(),superNumber="900000001";
    Path httpRoot=Files.createDirectory(data.resolve("app-root"));Files.createDirectories(httpRoot.resolve("web/assets"));
    Files.copy(app.resolve("VERSION"),httpRoot.resolve("VERSION"));
    try(var assets=Files.list(app.resolve("web/assets"))){for(Path file:assets.toList())Files.copy(file,httpRoot.resolve("web/assets").resolve(file.getFileName()));}
    BootstrapTest.write(httpRoot.resolve(BootstrapConfig.FILE_NAME),superNumber,password);
    int port;try(ServerSocket socket=new ServerSocket(0)){port=socket.getLocalPort();}base="http://127.0.0.1:"+port;
    client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
    Process server=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=UTF-8","-cp",app.resolve("app/zhihui-xinguan.jar")+File.pathSeparator+app.resolve("app/lib")+File.separator+"*","Main","--root",httpRoot.toString(),"--data-root",data.toString(),"--bind","127.0.0.1","--port",""+port).redirectErrorStream(true).redirectOutput(data.resolve("server.log").toFile()).start();
    try{
      boolean ready=false;for(int i=0;i<100;i++){try{if(get("/health").statusCode()==200){ready=true;break;}}catch(IOException ignored){}Thread.sleep(100);}check(ready,"server ready");
      check(get("/health").body().contains("SCHEMA=2"),"health reports migrated schema version");
      check(get("/export?dataset=multi").statusCode()==303,"anonymous export requires login");
      loginAndChange(superNumber,password,false);
      check(get("/bootstrap.local.properties").statusCode()==404&&get("/assets/bootstrap.local.properties").statusCode()==404,"local initialization file is never served by HTTP");
      HttpClient superClient=client;String superCsrf=hidden(get("/people").body()).get("csrf");
      check(get("/imports").statusCode()==403,"super cannot import");
      check(get("/template?dataset=multi").statusCode()==403,"super has no template upload entry");
      check(post("/admin/login",Map.of("password",password)).statusCode()==404,"legacy password-only login removed");
      String newPerson=get("/people/new").body();
      check(newPerson.contains("id=\"personOrganizationFields\" style=\"display:none\""),"division role initially hides branch field");
      check(newPerson.contains("/assets/identity.js")&&get("/assets/identity.js").statusCode()==200,"local dynamic role script is served");
      String allBranches=organizationOptions(newPerson);check(!allBranches.contains("value=\"CZ\"")&&Organizations.BRANCHES.keySet().stream().allMatch(id->allBranches.contains("value=\""+id+"\"")),"branch selector lists nine branches without division");
      var missingBranch=post("/people/create",Map.of("csrf",superCsrf,"authNumber","900000010","name","缺少支行","role","OPERATOR"));
      check(missingBranch.statusCode()==400&&missingBranch.body().contains("请选择所属支行")&&missingBranch.body().contains("value=\"900000010\"")&&!missingBranch.body().contains("id=\"personOrganizationFields\" style=\"display:none\""),"branch required and input retained on validation error");
      check(post("/people/create",Map.of("csrf",superCsrf,"authNumber","900000010","name","错误归属","role","OPERATOR","organization","CZ")).statusCode()==400,"branch staff cannot use division organization");
      Created division=create("900000002","测试分行",Role.DIVISION_ADMIN,"CZ",superCsrf);
      String divisionEdit=get("/people/edit?id="+division.id()).body();check(divisionEdit.contains("id=\"personOrganizationFields\" style=\"display:none\""),"division edit also hides branch field");
      Map<String,String> divisionUpdate=hidden(divisionEdit);divisionUpdate.put("name","测试分行");divisionUpdate.put("role","DIVISION_ADMIN");divisionUpdate.put("active","true");divisionUpdate.put("organization","WUJIN");
      check(post("/people/update",divisionUpdate).statusCode()==303,"server normalizes stale branch selection when role is division");
      Created branch=create("900000003","测试武进管理员",Role.BRANCH_ADMIN,"WUJIN",superCsrf);
      Created operator=create("900000004","测试武进操作员",Role.OPERATOR,"WUJIN",superCsrf);
      Created reviewer=create("900000005","测试武进复核员",Role.REVIEWER,"WUJIN",superCsrf);
      check(post("/people/create",Map.of("csrf",superCsrf,"authNumber","900000002","name","重复","role","DIVISION_ADMIN","organization","CZ")).statusCode()==400,"duplicate identity rejected");
      check(post("/people/create",Map.of("csrf",superCsrf,"authNumber","900000006","name","越权","role","SUPER_ADMIN","organization","CZ")).statusCode()==403,"cannot create extra super via form");
      client=newClient();loginAndChange("900000002",division.password());
      HttpClient divisionClient=client;
      String admin=get("/imports").body();String csrf=hidden(admin).get("csrf");
      for(String type:List.of("negative","multi","cross")){check(admin.contains("/imports/upload/"+type),"three upload entrances");check(get("/template?dataset="+type).statusCode()==200,"template download");}
      check(get("/").body().contains("营业部"),"all branch labels");
      byte[] multi=workbook("multi");
      check(upload("negative",multi,csrf).statusCode()==400,"wrong upload entrance rejected");
      check(upload("multi",multi,"bad-token").statusCode()==403,"upload csrf enforced");
      var preview=upload("multi",multi,csrf);check(preview.statusCode()==200&&preview.body().contains("尚未修改正式数据"),"preview before commit");
      check(!get("/details?dataset=multi&month=2026-09").body().contains("虚构测试企业 HTTP"),"preview has no published data");
      String token=hidden(preview.body()).get("token");check(post("/imports/confirm",Map.of("csrf",csrf,"token",token,"mode","preserve")).statusCode()==303,"confirm import");
      String details=get("/details?dataset=multi&month=2026-09").body();check(details.contains("虚构测试企业 HTTP"),"imported row visible");
      Map<String,String> fields=hidden(details);DatasetSchema schema=DatasetSchema.get("multi");for(int c=0;c<schema.width();c++)if(schema.editable(c))fields.put("v0_"+c,c==17?"HTTP 虚构反馈 <script>test</script>":"");
      check(post("/update-batch",fields).statusCode()==303,"single yellow cell saved");
      check(post("/update-batch",fields).statusCode()==303,"same browser save request is idempotent");
      String completed=get("/details?dataset=multi&month=2026-09").body();check(completed.contains("row-complete"),"row completion color");check(completed.contains("&lt;script&gt;test&lt;/script&gt;"),"saved text html escaped");
      check(!get("/?month=2026-09").body().contains("虚构测试企业 HTTP"),"completed row excluded from homepage pending list");
      check(get("/export?dataset=multi&month=2026-09").statusCode()==200,"authenticated export");
      var again=upload("multi",multi,csrf);check(again.body().contains("填报差异"),"overwrite differences shown");
      check(post("/imports/confirm",Map.of("csrf",csrf,"token",hidden(again.body()).get("token"),"mode","overwrite")).statusCode()==400,"blank overwrite needs explicit consent");
      check(post("/imports/confirm",Map.of("csrf",csrf,"token",hidden(again.body()).get("token"),"mode","preserve")).statusCode()==303,"preserve duplicate");
      check(get("/details?dataset=multi&month=2026-09").body().contains("HTTP 虚构反馈"),"duplicate preserved feedback");
      var stalePreview=upload("multi",multi,csrf);Map<String,String> next=hidden(get("/details?dataset=multi&month=2026-09").body());for(int c=0;c<schema.width();c++)if(schema.editable(c))next.put("v0_"+c,c==17?"另一次虚构保存":"");
      check(post("/update-batch",next).statusCode()==303,"newer save before import confirm");
      check(post("/imports/confirm",Map.of("csrf",csrf,"token",hidden(stalePreview.body()).get("token"),"mode","overwrite","confirmOverwrite","yes")).statusCode()==409,"stale preview cannot overwrite");
      fields.put("requestId",UUID.randomUUID().toString());check(post("/update-batch",fields).statusCode()==409,"stale editor rejected");
      for(String type:List.of("negative","cross")){var p=upload(type,workbook(type),csrf);check(p.statusCode()==200,"other module preview");check(post("/imports/confirm",Map.of("csrf",csrf,"token",hidden(p.body()).get("token"),"mode","preserve")).statusCode()==303,"other module committed");}
      check(get("/foundation").statusCode()==200,"diagnostics available");
      var other=upload("multi",workbook("multi","JINTAN","OTHER"),csrf);check(post("/imports/confirm",Map.of("csrf",csrf,"token",hidden(other.body()).get("token"),"mode","preserve")).statusCode()==303,"second branch imported");
      check(post("/people/create",Map.of("csrf",csrf,"authNumber","900000006","name","同级","role","DIVISION_ADMIN","organization","CZ")).statusCode()==403,"division cannot create peer");
      client=newClient();loginAndChange("900000003",branch.password());
      String branchHome=get("/").body();check(branchHome.contains("人员管理")&&!branchHome.contains("href=\"/imports\""),"branch role navigation");
      String branchDetails=get("/details?dataset=multi&month=2026-09").body();check(branchDetails.contains("虚构测试企业 HTTP")&&!branchDetails.contains("虚构测试企业 OTHER"),"branch data scoped");
      check(get("/branch?branch="+URLEncoder.encode("金坛",StandardCharsets.UTF_8)).statusCode()==403,"forged branch query blocked");
      check(get("/export?dataset=multi&branch="+URLEncoder.encode("金坛",StandardCharsets.UTF_8)).statusCode()==403,"cross-branch export blocked");
      String branchCsrf=hidden(get("/people").body()).get("csrf");
      String ownBranches=organizationOptions(get("/people/new").body());check(ownBranches.contains("value=\"WUJIN\"")&&!ownBranches.contains("value=\"JINTAN\"")&&!ownBranches.contains("value=\"CZ\""),"branch manager sees only own branch in new person form");
      check(post("/people/create",Map.of("csrf",branchCsrf,"authNumber","900000006","name","其他支行","role","OPERATOR","organization","JINTAN")).statusCode()==403,"branch cannot create outside scope");
      check(get("/people/edit?id="+division.id()).statusCode()==403,"branch cannot manage higher role");
      create("900000006","支行新增测试操作员",Role.OPERATOR,"WUJIN",branchCsrf);
      client=newClient();loginAndChange("900000004",operator.password());HttpClient operatorClient=client;
      String opHome=get("/").body();check(!opHome.contains("href=\"/people\"")&&!opHome.contains("href=\"/imports\""),"operator menu");
      check(get("/people").statusCode()==403&&get("/imports").statusCode()==403,"operator restricted routes");
      String opDetails=get("/details?dataset=multi&month=2026-09").body();check(!opDetails.contains("保存资料补充")&&!opDetails.contains("虚构测试企业 OTHER"),"operator read only and scoped");
      Map<String,String> forged=hidden(opDetails);for(int c=0;c<schema.width();c++)if(schema.editable(c))forged.put("v0_"+c,c==17?"forged":"");
      check(post("/update-batch",forged).statusCode()==403,"operator cannot bypass workflow using direct POST");
      client=newClient();loginAndChange("900000005",reviewer.password());HttpClient reviewerClient=client;
      check(get("/details?dataset=multi&month=2026-09").body().contains("保存资料补充"),"reviewer may directly fill own branch");
      client=superClient;
      Map<String,String> editReviewer=hidden(get("/people/edit?id="+reviewer.id()).body());editReviewer.put("name","已转金坛");editReviewer.put("role","OPERATOR");editReviewer.put("organization","JINTAN");editReviewer.put("active","true");
      check(post("/people/update",editReviewer).statusCode()==303,"super changes role and organization");
      client=reviewerClient;check(get("/").statusCode()==303,"changed role revokes existing session");
      client=superClient;
      Map<String,String> reset=hidden(get("/people/edit?id="+operator.id()).body());reset.put("confirmReset","yes");var resetResult=post("/people/reset-password",reset);check(resetResult.statusCode()==200,"reset generated password");
      client=operatorClient;check(get("/").statusCode()==303,"password reset revokes session");
      client=superClient;
      Map<String,String> disable=hidden(get("/people/edit?id="+branch.id()).body());disable.put("confirmDisable","yes");check(post("/people/disable",disable).statusCode()==303,"soft delete branch user");
      check(get("/people").body().contains("已停用"),"disabled account retained in management");
      client=divisionClient;
      check(post("/logout",Map.of("csrf",csrf)).statusCode()==303,"logout");check(get("/details?dataset=cross").statusCode()==303,"logout revokes session");
      System.out.println("HTTP_SMOKE_OK assertions="+assertions+" actual loopback server, synthetic data only");
    }finally{server.destroy();if(!server.waitFor(40,TimeUnit.SECONDS))throw new IllegalStateException("Test server did not stop; retained PID "+server.pid());}
  }
  record Created(String id,String password){}
  static HttpClient newClient(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();}
  static void loginAndChange(String number,String password)throws Exception{
    loginAndChange(number,password,true);
  }
  static void loginAndChange(String number,String password,boolean mustChange)throws Exception{
    String csrf=hidden(get("/login").body()).get("csrf");var response=post("/login",Map.of("csrf",csrf,"authNumber",number,"password",password,"role","SUPER_ADMIN"));
    check(response.statusCode()==303&&response.headers().firstValue("location").orElse("").equals(mustChange?"/account/password":"/"),"first login gate follows persisted role policy");
    if(mustChange)check(get("/people").statusCode()==303&&get("/export?dataset=multi").statusCode()==303,"first-password gate covers all business routes");
    else check(get("/people").statusCode()==200&&get("/export?dataset=multi").statusCode()==200,"super can manage and export without first password change");
    String next=UUID.randomUUID().toString(),changePage=get("/account/password").body(),changeCsrf=hidden(changePage).get("csrf");
    check(changePage.contains("readonly=\"readonly\" disabled=\"disabled\"")&&changePage.contains("value=\"********\"")&&!changePage.contains("name=\"current\"")&&!changePage.contains(password),"current password is an inert fixed mask, never the actual password");
    check(changePage.contains(number),"password page identifies read-only current account");
    check(post("/account/password",Map.of("csrf","forged","next",next,"confirm",next)).statusCode()==403,"password change requires csrf");
    check(post("/account/password",Map.of("csrf",changeCsrf,"next",password,"confirm",password)).statusCode()==400,"unchanged password rejected using stored hash without old-password input");
    check(post("/account/password",Map.of("csrf",changeCsrf,"next",next,"confirm","mismatch")).statusCode()==400,"new password confirmation enforced");
    check(post("/account/password",Map.of("csrf",changeCsrf,"next",next,"confirm",next,"authNumber","forged-other-account")).statusCode()==303,"change own password from fresh login, no old-password field");
    check(get("/account/password").statusCode()==303,"password change revokes session");
    String loginCsrf=hidden(get("/login").body()).get("csrf");
    check(post("/login",Map.of("csrf",loginCsrf,"authNumber",number,"password",next)).statusCode()==303,"login after change");
  }
  static Created create(String number,String name,Role role,String org,String csrf)throws Exception{
    Map<String,String> fields=new HashMap<>(Map.of("csrf",csrf,"authNumber",number,"name",name,"role",role.name()));if(role!=Role.DIVISION_ADMIN)fields.put("organization",org);
    var response=post("/people/create",fields);check(response.statusCode()==200,"create managed user; division requires no organization field");
    Matcher pwd=Pattern.compile("id=\"initialPassword\"[^>]*value=\"([^\"]+)\"").matcher(response.body());check(pwd.find(),"initial password shown once");
    Matcher id=Pattern.compile("<tr><td>"+number+"</td>.*?href=\"/people/edit\\?id=([^\"]+)\"",Pattern.DOTALL).matcher(get("/people").body());check(id.find(),"created user listed");
    return new Created(id.group(1),pwd.group(1));
  }
  static HttpResponse<String> get(String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofString());}
  static String organizationOptions(String html){Matcher m=Pattern.compile("<select id=\"personOrganization\"[^>]*>(.*?)</select>",Pattern.DOTALL).matcher(html);check(m.find(),"branch select exists");return m.group(1);}
  static HttpResponse<String> post(String path,Map<String,String> fields)throws Exception{List<String> parts=new ArrayList<>();fields.forEach((k,v)->parts.add(URLEncoder.encode(k,StandardCharsets.UTF_8)+"="+URLEncoder.encode(v,StandardCharsets.UTF_8)));return client.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(String.join("&",parts))).build(),HttpResponse.BodyHandlers.ofString());}
  static HttpResponse<String> upload(String type,byte[] file,String csrf)throws Exception{String boundary="SyntheticBoundary"+UUID.randomUUID();ByteArrayOutputStream out=new ByteArrayOutputStream();for(var e:Map.of("csrf",csrf,"month","2026-09").entrySet())out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+e.getKey()+"\"\r\n\r\n"+e.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"files\"; filename=\"synthetic.xlsx\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(file);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));return client.send(HttpRequest.newBuilder(URI.create(base+"/imports/upload/"+type)).timeout(Duration.ofSeconds(20)).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());}
  static byte[] workbook(String type)throws Exception{return workbook(type,"WUJIN","HTTP");}
  static byte[] workbook(String type,String org,String key)throws Exception{try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(new ExcelExporter().template(type)))){DatasetSchema schema=DatasetSchema.get(type);Row row=wb.getSheetAt(0).createRow(schema.headerRows);var values=FoundationTest.candidate(type,org,key).values();for(int c=0;c<values.size();c++)row.createCell(c).setCellValue(values.get(c));ByteArrayOutputStream out=new ByteArrayOutputStream();wb.write(out);return out.toByteArray();}}
  static Map<String,String> hidden(String html){Map<String,String> result=new HashMap<>();Matcher m=Pattern.compile("<input type=\"hidden\" name=\"([^\"]+)\" value=\"([^\"]*)\">").matcher(html);while(m.find())result.put(m.group(1),m.group(2).replace("&amp;","&").replace("&quot;","\""));return result;}
  static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
}

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import xinguan.platform.*;

/** Actual Main routes and browser-style forms, isolated server and synthetic files. */
final class ImportHttpTest {
  static int assertions;
  static void run(HttpClient root,HttpClient division,HttpClient branch,HttpClient operator,HttpClient reviewer)throws Exception{
    for(var client:List.of(root,branch,operator,reviewer)){
      use(client);for(String path:List.of("/imports","/imports/jobs","/imports/preview?token=fake","/template?dataset=multi"))check(get(path).statusCode()==403,"non-division import read denied");
      check(post("/imports/cancel",Map.of("csrf",csrf(),"token","fake")).statusCode()==403,"non-division write denied");
    }
    use(root);var peer=HttpSmokeTest.create("900000061","虚构导入同级",Role.DIVISION_ADMIN,"CZ",csrf());use(HttpSmokeTest.newClient());HttpSmokeTest.loginAndChange("900000061",peer.password());HttpClient peerClient=HttpSmokeTest.client;
    use(division);check(get("/imports").body().contains("/imports/jobs"),"resume task navigation");check(get("/assets/import.css").statusCode()==200,"local import CSS available");
    byte[] first=HttpSmokeTest.workbook("multi","WUJIN","A2-HTTP-FIRST"),second=HttpSmokeTest.workbook("multi","JINTAN","A2-HTTP-SECOND");
    check(upload(List.of(first),List.of("file.xlsx"),"forged").statusCode()==403,"upload CSRF before parse/stage");
    var invalid=upload(List.of(first,new byte[]{1,2,3}),List.of("valid.xlsx","bad<script>.xlsx"),csrf());check(invalid.statusCode()==400&&invalid.body().contains("bad&lt;script&gt;.xlsx")&&invalid.body().contains("本批全部未导入"),"multi-file failure has escaped filename and atomic message");
    check(!get("/details?dataset=multi&month=2026-09").body().contains("A2-HTTP-FIRST"),"failed batch inserts no valid subset");
    var response=upload(List.of(first,second),List.of("first.xlsx","second.xlsx"),csrf());check(response.statusCode()==200&&response.body().contains("first.xlsx")&&response.body().contains("second.xlsx"),"batch preview includes each provenance");
    String token=HttpSmokeTest.hidden(response.body()).get("token");check(token!=null&&get("/imports/jobs").body().contains(token),"persistent task list link");
    check(get("/imports/preview?token="+token).body().contains("A2-HTTP-FIRST"),"preview recoverable via GET");
    use(peerClient);check(get("/imports/preview?token="+token).statusCode()==403&&!get("/imports/jobs").body().contains(token),"another division admin cannot inspect staging");
    check(post("/imports/confirm",Map.of("csrf",csrf(),"token",token,"mode","saved","revision","1")).statusCode()==403,"another division admin cannot confirm task");
    use(division);var decisions=new HashMap<>(Map.of("csrf",csrf(),"token",token,"revision","1","choice_1","PRESERVE","choice_2","SKIP"));
    var forged=new HashMap<>(decisions);forged.put("csrf","wrong");check(post("/imports/choices",forged).statusCode()==403,"choices require CSRF");
    check(post("/imports/choices",decisions).statusCode()==303,"page decisions saved");check(post("/imports/choices",decisions).statusCode()==409,"stale choice cannot overwrite newer choices");
    var confirm=new HashMap<>(Map.of("csrf",csrf(),"token",token,"mode","saved","revision","1"));check(post("/imports/confirm",confirm).statusCode()==409,"stale confirm version blocked");
    confirm.put("revision","2");confirm.put("row_values","FORGED_CLIENT_DATA");confirm.put("dataset","negative");
    check(post("/imports/confirm",confirm).statusCode()==303&&post("/imports/confirm",confirm).statusCode()==303,"confirm and safe retry ignore replacement payloads");
    String official=get("/details?dataset=multi&month=2026-09").body();check(official.contains("A2-HTTP-FIRST")&&!official.contains("A2-HTTP-SECOND")&&!official.contains("FORGED_CLIENT_DATA"),"formal rows honor frozen staged payload and saved skip choice");
    check(get("/imports/preview?token="+token).body().contains("已导入"),"result accessible after confirm");
    confirm.put("mode","overwrite");confirm.put("confirmOverwrite","yes");check(post("/imports/confirm",confirm).statusCode()==409,"changed repeat decision blocked");
    var cancel=upload(List.of(first),List.of("cancel.xlsx"),csrf());String cancelled=HttpSmokeTest.hidden(cancel.body()).get("token");
    check(post("/imports/cancel",Map.of("csrf",csrf(),"token",cancelled,"revision","1")).statusCode()==303,"real cancel endpoint");
    check(get("/imports/preview?token="+cancelled).body().contains("已取消")&&!get("/imports/preview?token="+cancelled).body().contains("A2-HTTP-FIRST"),"cancel purges staged row content");
    check(post("/imports/confirm",Map.of("csrf",csrf(),"token",cancelled,"revision","1","mode","saved")).statusCode()==409,"cancelled task cannot publish");
    String audit=get("/audit?category=business&search="+token).body();check(audit.contains("IMPORT_CONFIRM")||audit.contains("导入确认"),"confirmation decisions in shared authorized audit");
    use(branch);check(!get("/audit?category=business&search="+token).body().contains("SKIP"),"branch audit cannot see other branch import decisions");
    use(division);check(get("/foundation").body().contains("数据库结构：4"),"diagnostic page reflects actual migrated schema");
    var unified=uploadBundle(bundleWorkbook(),"unified.xlsx",csrf());check(unified.statusCode()==200&&unified.body().contains("三表统一工作簿")&&!unified.body().contains("identity-card import-item"),"unified upload stages one compact three-sheet preview");String unifiedToken=HttpSmokeTest.hidden(unified.body()).get("token");check(unifiedToken!=null&&get("/imports/preview?token="+unifiedToken+"&details=yes").body().contains("bundle-negative"),"unified preview can expand source details on demand");check(post("/imports/confirm",Map.of("csrf",csrf(),"token",unifiedToken,"mode","saved")).statusCode()==303,"unified confirmation commits all sheets once");
    System.out.println("IMPORT_HTTP_OK assertions="+assertions+" real Main forms, bulk errors, private staging, choices, confirm, cancellation and audit");
  }
  static HttpResponse<String> upload(List<byte[]> files,List<String> names,String csrf)throws Exception{
    String boundary="SyntheticA2"+UUID.randomUUID();ByteArrayOutputStream out=new ByteArrayOutputStream();
    for(var e:Map.of("csrf",csrf,"month","2026-09").entrySet())out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+e.getKey()+"\"\r\n\r\n"+e.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));
    for(int i=0;i<files.size();i++){out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"files\"; filename=\""+names.get(i)+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(files.get(i));out.write("\r\n".getBytes(StandardCharsets.UTF_8));}
    out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));return HttpSmokeTest.client.send(HttpRequest.newBuilder(URI.create(HttpSmokeTest.base+"/imports/upload/multi")).timeout(java.time.Duration.ofSeconds(30)).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
  }
  static HttpResponse<String> uploadBundle(byte[] file,String name,String csrf)throws Exception{
    String boundary="SyntheticBundle"+UUID.randomUUID();ByteArrayOutputStream out=new ByteArrayOutputStream();for(var e:Map.of("csrf",csrf,"month","2026-09").entrySet())out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+e.getKey()+"\"\r\n\r\n"+e.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"files\"; filename=\""+name+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(file);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));return HttpSmokeTest.client.send(HttpRequest.newBuilder(URI.create(HttpSmokeTest.base+"/imports/upload")).timeout(java.time.Duration.ofSeconds(30)).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
  }
  static byte[] bundleWorkbook()throws Exception{try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(new ExcelExporter().templateBundle()))){for(var schema:DatasetSchema.all()){Row row=wb.getSheet(schema.label).createRow(schema.headerRows);var values=FoundationTest.candidate(schema.id,"WUJIN","bundle-"+schema.id).values();for(int c=0;c<values.size();c++)row.createCell(c).setCellValue(values.get(c));}ByteArrayOutputStream out=new ByteArrayOutputStream();wb.write(out);return out.toByteArray();}}
  static void use(HttpClient c){HttpSmokeTest.client=c;}
  static HttpResponse<String> get(String path)throws Exception{return HttpSmokeTest.get(path);}
  static HttpResponse<String> post(String path,Map<String,String> fields)throws Exception{return HttpSmokeTest.post(path,fields);}
  static String csrf()throws Exception{return HttpSmokeTest.hidden(get("/").body()).get("csrf");}
  static void check(boolean v,String message){assertions++;if(!v)throw new AssertionError(message);}
}

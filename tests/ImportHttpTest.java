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
    var response=upload(List.of(first,second),List.of("first.xlsx","second.xlsx"),csrf());check(response.statusCode()==200&&response.body().contains("上传有效记录 2 条")&&!response.body().contains("A2-HTTP-FIRST"),"batch preview is compact, rows only on demand");
    String token=HttpSmokeTest.hidden(response.body()).get("token");check(token!=null&&get("/imports/jobs").body().contains(token),"persistent task list link");
    check(get("/imports/preview?token="+token+"&details=yes").body().contains("A2-HTTP-FIRST"),"preview recoverable via GET");
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
    use(division);check(get("/foundation").body().contains("数据库结构：7"),"diagnostic page reflects actual migrated schema");
    var unified=uploadBundle(bundleWorkbook(),"unified.xlsx",csrf());check(unified.statusCode()==200&&unified.body().contains("交叉违约清单")&&!unified.body().contains("identity-card import-item"),"unified upload stages one compact three-sheet preview");String unifiedToken=HttpSmokeTest.hidden(unified.body()).get("token");check(unifiedToken!=null&&get("/imports/preview?token="+unifiedToken+"&details=yes").body().contains("bundle-negative"),"unified preview can expand source details on demand");check(!unified.body().contains("保存整批决定")&&!unified.body().contains("全部跳过")&&!unified.body().contains("type=\"checkbox\""),"simplified import has one mode selector without redundant save or checkboxes");
    Map<String,String> bulk=new HashMap<>(Map.of("csrf",csrf(),"token",unifiedToken,"mode","preserve","revision","1"));
    check(post("/imports/confirm-bulk",bulk).statusCode()==200,"no-JavaScript fallback asks for confirmation without writing");
    check(!get("/details?dataset=multi&month=2026-09&q=bundle-multi").body().contains("虚构测试企业 bundle-multi"),"fallback has not published data");
    bulk.put("confirmed","yes");bulk.put("csrf","forged");check(post("/imports/confirm-bulk",bulk).statusCode()==403,"new confirmation checks CSRF");bulk.put("csrf",csrf());
    check(post("/imports/confirm-bulk",bulk).statusCode()==303&&post("/imports/confirm-bulk",bulk).statusCode()==303,"new unified confirm commits once and is idempotent");
    bulk.put("mode","overwrite");check(post("/imports/confirm-bulk",bulk).statusCode()==409,"cannot change bulk mode after confirm");
    String uploadPage=get("/imports").body();check(!uploadPage.contains("name=\"month\"")&&!uploadPage.contains("name=\"period\"")&&!uploadPage.contains("旧 IE"),"simple upload has no manual period or browser notes");
    byte[] september=bundleWorkbook("20260901-20260915","cross-month"),october=bundleWorkbook("20261001-20261015","cross-month");
    var crossMonth=uploadBundleFiles(List.of(september,october),List.of("2026-09-cross-month.xlsx","2026-10-cross-month.xlsx"),csrf(),"");
    check(crossMonth.statusCode()==200&&crossMonth.body().contains("2026-09")&&crossMonth.body().contains("2026-10"),"cross-month complete workbooks keep file-level periods in one preview");
    String crossToken=HttpSmokeTest.hidden(crossMonth.body()).get("token");check(post("/imports/confirm",Map.of("csrf",csrf(),"token",crossToken,"mode","saved")).statusCode()==303,"cross-month complete workbooks commit atomically");
    String septemberDetails=get("/details?dataset=cross&month=2026-09").body(),octoberDetails=get("/details?dataset=cross&month=2026-10").body();
    check(septemberDetails.contains("cross-month-cross")&&septemberDetails.contains("2026-09"),"September cross rows stay queryable by month");check(octoberDetails.contains("cross-month-cross")&&octoberDetails.contains("2026-10"),"October cross rows stay queryable by month");
    check(exportMatches(exportBytes("/export?dataset=cross&month=2026-09").body(),"cross-month-cross")==1,"September cross export keeps one file-level row");check(exportMatches(exportBytes("/export?dataset=cross&month=2026-10").body(),"cross-month-cross")==1,"October cross export keeps one file-level row");
    var conflicting=uploadBundleFiles(List.of(october),List.of("2026-10-cross-conflict.xlsx"),csrf(),"2026-09");check(conflicting.statusCode()==200&&conflicting.body().contains("重复 3 条"),"obsolete public month ignored, row dates decide period");check(exportMatches(exportBytes("/export?dataset=cross&month=2026-10").body(),"cross-month-cross")==1,"preview leaves October official data unchanged");
    byte[] invalidDate;try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(bundleWorkbook("20260901-20260915","bad-date-bundle")))){
      wb.getSheet(DatasetSchema.get("cross").label).getRow(2).getCell(11).setCellValue("2026-02-30");
      ByteArrayOutputStream out=new ByteArrayOutputStream();wb.write(out);invalidDate=out.toByteArray();
    }
    check(uploadBundleFiles(List.of(invalidDate),List.of("2026-09.xlsx"),csrf(),null).statusCode()==400,"invalid cross date rejects whole workbook");
    check(!get("/details?dataset=multi&q=bad-date-bundle&month=2026-09").body().contains("虚构测试企业 bad-date-bundle"),"invalid cross date cannot partially import other sheets");
    sourceDates();
    System.out.println("IMPORT_HTTP_OK assertions="+assertions+" real Main forms, bulk errors, private staging, choices, confirm, cancellation and audit");
  }
  static void sourceDates()throws Exception{
    Map<String,String> months=Map.of("multi","2025-01","negative","2025-02","cross","2025-03");
    Map<String,String> dates=Map.of("multi","20250101-20250115","negative","20250216-20250228","cross","2025-03-04");
    byte[] file;
    try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(bundleWorkbook("20250901-20250915","RC8-DATES")))){
      for(var schema:DatasetSchema.all())wb.getSheet(schema.label).getRow(schema.headerRows).getCell(schema.periodColumn<0?11:schema.periodColumn).setCellValue(dates.get(schema.id));
      ByteArrayOutputStream out=new ByteArrayOutputStream();wb.write(out);file=out.toByteArray();
    }
    var response=uploadBundleFiles(List.of(file),List.of("20301201-20301215.xlsx"),csrf(),"2031-01");
    check(response.statusCode()==200&&response.body().contains("新增 3 条"),"three sheets with different source dates stage independently of filename");
    String token=HttpSmokeTest.hidden(response.body()).get("token");check(post("/imports/confirm-bulk",Map.of("csrf",csrf(),"token",token,"revision","1","mode","preserve","confirmed","yes")).statusCode()==303,"source-dated workbook committed");
    for(var schema:DatasetSchema.all()){
      String month=months.get(schema.id),key="RC8-DATES-"+schema.id,period=schema.periodColumn<0?month:Period.parse(dates.get(schema.id),"").key();
      for(String selected:months.values()){
        String html=get("/details?dataset="+schema.id+"&month="+selected+"&q="+key).body();
        check(html.contains("虚构测试企业 "+key)==selected.equals(month),"source field decides each monthly detail: "+schema.id+selected);
        check(!html.contains("期次／历史保留信息"),"no extra metadata column in any detail");
      }
      String yearly=get("/details?dataset="+schema.id+"&scope=year&year=2025&q="+key).body();check(yearly.contains("虚构测试企业 "+key),"year grouping agrees with row dates");
      var export=exportBytes("/export?dataset="+schema.id+"&month="+month+"&q="+key);check(export.statusCode()==200,"source month export authorized");
      try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(export.body()))){
        Sheet sh=wb.getSheetAt(0);Row row=sh.getRow(schema.headerRows);
        check(sh.getLastRowNum()==schema.headerRows&&row.getLastCellNum()==schema.width()&&row.getCell(schema.customerColumn).getStringCellValue().contains(key),"export has matching row and exact template width");
      }
      String deadlines=get("/deadlines?month="+month).body();check(deadlines.contains("name=\"dataset\" value=\""+schema.id+"\"")&&deadlines.contains("name=\"period\" value=\""+period+"\""),"deadline grouping uses the same template-derived period");
      byte[] missing;
      try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(bundleWorkbook("20251101-20251115","RC8-MISSING-"+schema.id)))){
        wb.getSheet(schema.label).getRow(schema.headerRows).getCell(schema.periodColumn<0?11:schema.periodColumn).setBlank();ByteArrayOutputStream out=new ByteArrayOutputStream();wb.write(out);missing=out.toByteArray();
      }
      var rejected=uploadBundleFiles(List.of(missing),List.of("20251101-20251115.xlsx"),csrf(),"2025-11");
      check(rejected.statusCode()==400&&rejected.body().contains(schema.periodColumn<0?"违约首次出现时间必填":"时间顺序必填"),"missing source date cannot be filled by filename or forged month");
      check(!get("/details?dataset=multi&month=2025-11&q=RC8-MISSING-"+schema.id).body().contains("虚构测试企业 RC8-MISSING"),"missing date rejects entire three-sheet batch");
    }
    var again=uploadBundleFiles(List.of(file),List.of("20391201-20391215.xlsx"),csrf(),"2032-01");check(again.statusCode()==200&&again.body().contains("重复 3 条"),"renaming workbook cannot create another period or duplicate records");
    String duplicateToken=HttpSmokeTest.hidden(again.body()).get("token");check(post("/imports/cancel",Map.of("csrf",csrf(),"token",duplicateToken,"revision","1")).statusCode()==303,"release synthetic duplicate preview so later suites retain import quota");
  }
  static HttpResponse<String> upload(List<byte[]> files,List<String> names,String csrf)throws Exception{
    String boundary="SyntheticA2"+UUID.randomUUID();ByteArrayOutputStream out=new ByteArrayOutputStream();
    for(var e:Map.of("csrf",csrf,"month","2026-09").entrySet())out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+e.getKey()+"\"\r\n\r\n"+e.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));
    for(int i=0;i<files.size();i++){out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"files\"; filename=\""+names.get(i)+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(files.get(i));out.write("\r\n".getBytes(StandardCharsets.UTF_8));}
    out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));return HttpSmokeTest.client.send(HttpRequest.newBuilder(URI.create(HttpSmokeTest.base+"/imports/upload/multi")).timeout(java.time.Duration.ofSeconds(30)).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
  }
  static HttpResponse<String> uploadBundle(byte[] file,String name,String csrf)throws Exception{
    return uploadBundleFiles(List.of(file),List.of(name),csrf,"2026-09");
  }
  static HttpResponse<String> uploadBundleFiles(List<byte[]> files,List<String> names,String csrf,String month)throws Exception{
    String boundary="SyntheticBundle"+UUID.randomUUID();ByteArrayOutputStream out=new ByteArrayOutputStream();Map<String,String> fields=new LinkedHashMap<>();fields.put("csrf",csrf);if(month!=null)fields.put("month",month);for(var e:fields.entrySet())out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+e.getKey()+"\"\r\n\r\n"+e.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));for(int i=0;i<files.size();i++){out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"files\"; filename=\""+names.get(i)+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(files.get(i));out.write("\r\n".getBytes(StandardCharsets.UTF_8));}out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));return HttpSmokeTest.client.send(HttpRequest.newBuilder(URI.create(HttpSmokeTest.base+"/imports/upload")).timeout(java.time.Duration.ofSeconds(30)).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
  }
  static byte[] bundleWorkbook()throws Exception{return bundleWorkbook("20260901-20260915","bundle");}
  static byte[] bundleWorkbook(String period,String keyPrefix)throws Exception{try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(new ExcelExporter().templateBundle()))){for(var schema:DatasetSchema.all()){Row row=wb.getSheet(schema.label).createRow(schema.headerRows);var values=FoundationTest.candidate(schema.id,"WUJIN",keyPrefix+"-"+schema.id).values();for(int c=0;c<values.size();c++)row.createCell(c).setCellValue(values.get(c));if(schema.periodColumn>=0)row.getCell(schema.periodColumn).setCellValue(period);else row.getCell(11).setCellValue(period.substring(0,4)+"-"+period.substring(4,6)+"-"+period.substring(6,8));}ByteArrayOutputStream out=new ByteArrayOutputStream();wb.write(out);return out.toByteArray();}}
  static HttpResponse<byte[]> exportBytes(String path)throws Exception{return HttpSmokeTest.client.send(HttpRequest.newBuilder(URI.create(HttpSmokeTest.base+path)).timeout(java.time.Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
  static int exportMatches(byte[] bytes,String customer)throws Exception{try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(bytes))){Sheet sheet=wb.getSheetAt(0);DataFormatter formatter=new DataFormatter(Locale.CHINA);int count=0;for(int r=DatasetSchema.get("cross").headerRows;r<=sheet.getLastRowNum();r++){Row row=sheet.getRow(r);if(row!=null&&formatter.formatCellValue(row.getCell(DatasetSchema.get("cross").customerColumn)).contains(customer))count++;}return count;}}
  static void use(HttpClient c){HttpSmokeTest.client=c;}
  static HttpResponse<String> get(String path)throws Exception{return HttpSmokeTest.get(path);}
  static HttpResponse<String> post(String path,Map<String,String> fields)throws Exception{return HttpSmokeTest.post(path,fields);}
  static String csrf()throws Exception{return HttpSmokeTest.hidden(get("/").body()).get("csrf");}
  static void check(boolean v,String message){assertions++;if(!v)throw new AssertionError(message);}
}

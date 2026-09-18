import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import xinguan.platform.*;

final class MaintenanceHttpTest {
  static int assertions;
  static void run(HttpClient root,HttpClient division,HttpClient branch,HttpClient operator,HttpClient reviewer)throws Exception{
    for(var actor:List.of(division,branch,operator,reviewer)){
      use(actor);check(!get("/audit").body().contains("/audit/cleanup/preview"),"cleanup button only for super");
      check(post("/audit/cleanup/preview",Map.of("csrf",csrf(),"category","business")).statusCode()==403,"cleanup preview rejects other roles");
      check(post("/audit/cleanup/confirm",Map.of("csrf",csrf(),"token","fake","confirmed","yes")).statusCode()==403,"cleanup confirm rejects other roles");
    }
    for(var actor:List.of(root,branch,operator,reviewer)){
      use(actor);check(get("/imports/delete").statusCode()==403,"month page restricted to division");
      check(post("/imports/delete/preview",Map.of("csrf",csrf(),"month","2027-12")).statusCode()==403,"month preview permission");
      check(post("/imports/delete/confirm",Map.of("csrf",csrf(),"token","fake","confirmed","yes")).statusCode()==403,"month confirm permission");
    }
    use(root);var created=HttpSmokeTest.create("908701001","RC7虚构人员",Role.OPERATOR,"WUJIN",csrf());String search="/audit?category=security&search="+created.id();
    check(get(search).body().contains("RC7虚构人员")&&get(search).body().contains("/audit/cleanup/preview"),"root audit filter and cleanup entry");
    use(division);check(!get(search).body().contains("RC7虚构人员"),"division cannot see super creation event");
    use(branch);check(!get(search).body().contains("RC7虚构人员"),"branch cannot see super creation event");
    use(root);var f=new HashMap<>(Map.of("csrf",csrf(),"category","security","search",created.id()));
    var bad=new HashMap<>(f);bad.put("csrf","wrong");check(post("/audit/cleanup/preview",bad).statusCode()==403,"cleanup preview CSRF");
    var preview=post("/audit/cleanup/preview",f);check(preview.statusCode()==200&&preview.body().contains("清理操作记录确认")&&preview.body().contains("全部分页"),"cleanup shows bounded scope and permanent effect");
    check(get(search).body().contains("RC7虚构人员"),"preview never mutates audit");
    var confirm=HttpSmokeTest.hidden(preview.body());var missing=new HashMap<>(confirm);missing.remove("confirmed");check(post("/audit/cleanup/confirm",missing).statusCode()==400,"explicit cleanup confirm required");
    bad=new HashMap<>(confirm);bad.put("csrf","wrong");check(post("/audit/cleanup/confirm",bad).statusCode()==403,"cleanup confirmation CSRF");
    check(post("/audit/cleanup/confirm",confirm).statusCode()==200&&post("/audit/cleanup/confirm",confirm).statusCode()==200,"cleanup confirm replay safe");
    check(!get(search).body().contains("RC7虚构人员")&&get("/people").body().contains("RC7虚构人员"),"cleared audit but account kept");
    use(division);check(get("/imports").body().contains("/imports/delete"),"monthly deletion accessible from data updates");
    for(String type:List.of("multi","negative","cross")){
      var imported=HttpSmokeTest.upload(type,workbook(type),csrf());check(imported.statusCode()==200,"synthetic month import preview");
      var token=HttpSmokeTest.hidden(imported.body()).get("token");check(post("/imports/confirm",Map.of("csrf",csrf(),"token",token,"mode","preserve")).statusCode()==303,"synthetic month import");
    }
    check(get("/imports/delete").body().contains("2027-12"),"month selector uses data periods");
    bad=new HashMap<>(Map.of("csrf","wrong","month","2027-12"));check(post("/imports/delete/preview",bad).statusCode()==403,"monthly preview CSRF");
    var monthPreview=post("/imports/delete/preview",Map.of("csrf",csrf(),"month","2027-12"));
    check(monthPreview.statusCode()==200&&monthPreview.body().contains("本次共 3 条")&&monthPreview.body().contains("交叉违约清单"),"monthly confirmation counts all three types");
    check(get("/details?dataset=cross&month=2027-12").body().contains("RC7-cross"),"month preview does not delete");
    confirm=HttpSmokeTest.hidden(monthPreview.body());bad=new HashMap<>(confirm);bad.put("csrf","wrong");check(post("/imports/delete/confirm",bad).statusCode()==403,"monthly confirm CSRF");
    check(post("/imports/delete/confirm",confirm).statusCode()==200&&post("/imports/delete/confirm",confirm).statusCode()==200,"monthly deletion replay safe");
    for(String type:List.of("multi","negative","cross")){
      check(!get("/details?dataset="+type+"&month=2027-12").body().contains("RC7-"+type),"removed record not in formal details");
      var export=HttpSmokeTest.client.send(HttpRequest.newBuilder(URI.create(HttpSmokeTest.base+"/export?dataset="+type+"&month=2027-12")).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
      try(var wb=WorkbookFactory.create(new ByteArrayInputStream(export.body()))){boolean found=false;for(var sheet:wb)for(var row:sheet)for(var cell:row)if(cell.toString().contains("RC7-"+type))found=true;check(!found,"removed record not in XLSX export");}
    }
    check(!get("/?month=2027-12").body().contains("RC7-cross")&&!get("/imports/delete").body().contains("2027-12"),"home and month choices exclude deleted month");
    use(branch);check(get("/audit?search=DATA_MONTH_DELETE").body().contains("按月删除数据"),"branch may view own monthly deletion trace");
    System.out.println("MAINTENANCE_HTTP_OK assertions="+assertions+" five-role scope, CSRF, confirmation, monthly lists and real XLSX exports");
  }
  static byte[] workbook(String type)throws Exception{try(var wb=WorkbookFactory.create(new ByteArrayInputStream(HttpSmokeTest.workbook(type,"WUJIN","RC7-"+type)))){var schema=DatasetSchema.get(type);var row=wb.getSheetAt(0).getRow(schema.headerRows);row.getCell(type.equals("cross")?11:schema.periodColumn).setCellValue(type.equals("cross")?"2027-12-03":"20271201-20271215");var out=new ByteArrayOutputStream();wb.write(out);return out.toByteArray();}}
  static void use(HttpClient c){HttpSmokeTest.client=c;}
  static HttpResponse<String> get(String path)throws Exception{return HttpSmokeTest.get(path);}
  static HttpResponse<String> post(String path,Map<String,String> f)throws Exception{return HttpSmokeTest.post(path,f);}
  static String csrf()throws Exception{return HttpSmokeTest.hidden(get("/").body()).get("csrf");}
  static void check(boolean v,String message){assertions++;if(!v)throw new AssertionError(message);}
}

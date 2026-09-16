import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import xinguan.platform.*;
import static xinguan.platform.ImportPlatform.*;

/** GET-only synthetic visual fixture, never a production authentication bypass. */
public final class ImportVisualFixture {
  public static void main(String[] args)throws Exception {
    var user=new UserAccount("visual-import","000000001","虚构分行管理员",Role.DIVISION_ADMIN,"CZ",true,false,1);
    var session=new AuthService.Session("synthetic","synthetic",Instant.now().getEpochSecond(),user);session.safetyVersion=AccessPlatform.SAFETY_VERSION;session.unreadCount=12;
    var schema=DatasetSchema.get("multi");var values=new ArrayList<>(Collections.nCopies(schema.width(),""));values.set(0,"1");values.set(1,"武进");values.set(2,"虚构长名称企业集团有限公司（仅用于页面验证）");values.set(3,"00000012345");values.set(17,"原有反馈：经核实尚待补充材料。".repeat(12));values.set(22,"20260901-20260915");
    var period=xinguan.platform.Period.parse("20260901-20260915","");var old=new BusinessRecord("synthetic",1,"multi",period,"WUJIN",values,"虚构文件.xlsx","2026-09-16T00:00:00Z","",Map.of());
    var next=new ArrayList<>(values);next.set(17,"本次填报：已对相关事项进行核验，补充说明较长文字，检查单元格换行及不同屏幕宽度下的显示情况。".repeat(10));next.set(18,"否");
    var incoming=new BusinessRecord("",0,"multi",period,"WUJIN",next,"2026年9月虚构测试多重预警.xlsx","2026-09-16T00:00:00Z","",Map.of());
    Job job=new Job("synthetic-import-job","multi","PREVIEW",1,"2026-09-16T00:00:00Z","2026-09-16T00:30:00Z",2,0,1,"");
    var pages=new ImportJobPages("0.3.0-a2.1 · 虚构页面",session);
    Map<String,String> html=Map.of("/",pages.preview(new Preview(job,List.of(new Item(1,new SourceRow(incoming,"多重预警清单",3),old,0,2,Choice.PRESERVE),new Item(2,new SourceRow(incoming,"多重预警清单",4),null,1,0,Choice.SKIP))),0),"/imports",new ImportPages("0.3.0-a2.1",session).imports(session,"",false),"/imports/jobs",pages.history(List.of(job),0),"/errors",pages.errors(List.of(new WorkbookImporter.Issue("虚构文件.xlsx","多重预警清单",3,"B","机构不在字典，请核对"),new WorkbookImporter.Issue("虚构文件.xlsx","多重预警清单",4,"W","时间顺序无法识别，请使用正确起止日期"))));
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",x->{try{if(!x.getRequestMethod().equals("GET")){HttpSupport.text(x,405,"Read-only fixture","text/plain");return;}String path=x.getRequestURI().getPath();if(path.startsWith("/assets/")){String name=path.substring(8);if(!Set.of("style.css","foundation.css","access.css","import.css","html5shiv.js").contains(name)){HttpSupport.text(x,404,"Not found","text/plain");return;}HttpSupport.text(x,200,Files.readString(Path.of("web/assets").resolve(name)),name.endsWith("css")?"text/css":"application/javascript");}else HttpSupport.text(x,200,html.getOrDefault(path,html.get("/")),"text/html; charset=utf-8");}finally{x.close();}});
    Runtime.getRuntime().addShutdownHook(new Thread(()->server.stop(0)));server.start();System.out.println("SYNTHETIC_IMPORT_VISUAL=http://127.0.0.1:"+server.getAddress().getPort());
  }
}

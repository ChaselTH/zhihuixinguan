import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import xinguan.platform.*;

/** Read-only, synthetic visual fixture. Not included in the production JAR or install bundle. */
public final class AccessVisualFixture {
  public static void main(String[] args)throws Exception {
    var user=new UserAccount("visual-user","000000001","虚构测试管理员",Role.DIVISION_ADMIN,"CZ",true,false,1);
    var session=new AuthService.Session("synthetic-session","synthetic-csrf",Instant.now().getEpochSecond(),user);session.safetyVersion=AccessPlatform.SAFETY_VERSION;session.unreadCount=3;
    var app=new AccessPlatform.Application("synthetic-request","000000002","虚构申请人","WUJIN","PENDING",1,"BRANCH","2026-09-15T02:00:00Z",null,"",null);
    var notice=new NotificationService.Notice("synthetic-notice","ACCESS_REQUEST","WUJIN","有新的权限申请","请核验申请人身份并审批；角色由管理员指定","",Instant.parse("2026-09-15T02:00:00Z"),null);
    var before=new ArrayList<>(Collections.nCopies(DatasetSchema.get("multi").width(),""));var after=new ArrayList<>(before);after.set(17,"虚构测试反馈：经核查，相关情况已落实。此处用于检查较长反馈文字的换行和修改前后对照，所有内容均为测试样例。");
    var audit=new AccessPlatform.AuditEntry("synthetic-event","2026-09-15T02:00:00Z","虚构客户经理","WUJIN","DIRECT_EDIT","synthetic-record","synthetic-request","multi","虚构企业","",before,after,"虚构操作记录，仅用于页面检查");
    var pages=new AccessPages("0.3.0-a1.1 · 虚构页面测试",session);
    Map<String,String> html=Map.of("/",pages.applications(List.of(app),true,0),"/access/request",pages.application(app,true),"/access/apply",new AccessPages("0.3.0-a1.1 · 虚构页面测试",null).apply("synthetic","branch"),"/security",pages.safety(),"/notifications",new NotificationPages("0.3.0-a1.1 · 虚构页面测试",session).inbox(List.of(notice),false,0),"/audit",new AuditPages("0.3.0-a1.1 · 虚构页面测试",session).audit(List.of(audit),Map.of(),0));
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",x->{try{if(!x.getRequestMethod().equals("GET")){HttpSupport.text(x,405,"Read-only fixture","text/plain");return;}String path=x.getRequestURI().getPath();if(path.startsWith("/assets/")){String name=path.substring(8);if(!Set.of("style.css","foundation.css","access.css","html5shiv.js").contains(name)){HttpSupport.text(x,404,"Not found","text/plain");return;}HttpSupport.text(x,200,Files.readString(Path.of("web/assets").resolve(name)),name.endsWith("css")?"text/css":"application/javascript");}else HttpSupport.text(x,200,html.getOrDefault(path,html.get("/")),"text/html; charset=utf-8");}finally{x.close();}});
    Runtime.getRuntime().addShutdownHook(new Thread(()->server.stop(0)));server.start();System.out.println("SYNTHETIC_VISUAL_URL=http://127.0.0.1:"+server.getAddress().getPort());
  }
}

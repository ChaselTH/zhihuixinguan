import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import xinguan.platform.*;

/** Loopback-only synthetic UI fixture; never packaged or connected to the user's data. */
public class CompletionRuleVisualFixture {
  public static void main(String[] args)throws Exception {
    DataStore data=new DataStore(Files.createTempDirectory("xinguan-required-visual-"));var store=data.platform;
    String password=UUID.randomUUID().toString();store.bootstrapSuperAdmin("907000001",password);var root=store.authenticateUser("907000001",password).actor();
    var div=FeedbackViewTest.user(store,root,"907000002",Role.DIVISION_ADMIN,"CZ");
    for(var schema:DatasetSchema.all()){
      var row=FoundationTest.candidate(schema.id,"WUJIN","必填功能虚构测试企业");var values=new ArrayList<>(row.values());values.set(schema.index(schema.id.equals("cross")?"cross_feedback":"feedback"),"已核查经营情况，尚待补充其他反馈。");
      store.importRows(div,schema.id,List.of(new BusinessRecord("",0,schema.id,row.period(),row.organizationId(),values,"synthetic.xlsx",Instant.now().toString(),"",Map.of())),false,UUID.randomUUID().toString());
    }
    var session=new AuthService.Session("fixture","fixture-csrf",Instant.now().getEpochSecond(),store.sessionUser(div.userId()));session.safetyVersion=AccessPlatform.SAFETY_VERSION;
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",x->{try{
      String path=x.getRequestURI().getPath();
      if(path.startsWith("/assets/")){
        String file=path.substring(8);if(!Set.of("style.css","foundation.css","access.css","business.css","business.js","workflow.js","html5shiv.js").contains(file)){HttpSupport.text(x,404,"Not found","text/plain");return;}
        HttpSupport.text(x,200,Files.readString(Path.of("web/assets").resolve(file)),file.endsWith("css")?"text/css":"application/javascript");return;
      }
      var routes=new CompletionRuleRoutes(store,"rc.10 · 虚构测试");
      if(x.getRequestMethod().equals("POST")){
        var form=HttpSupport.decodeForm(HttpSupport.readLimited(x.getRequestBody(),8192));if(!session.csrf.equals(form.get("csrf")))throw new SecurityException("Invalid fixture CSRF");
        if(routes.post(x,session,form))return;
      }else if(x.getRequestMethod().equals("GET")){
        var q=HttpSupport.query(x.getRequestURI());if(routes.get(x,session,q))return;if(new BusinessRoutes(data,"rc.10 · 虚构测试").get(x,session,q))return;
      }
      HttpSupport.text(x,404,"Synthetic rules fixture only","text/plain");
    }catch(Exception e){HttpSupport.text(x,400,e.getMessage(),"text/plain; charset=utf-8");}finally{x.close();}});
    Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);try{data.close();}catch(Exception ignored){}}));
    server.start();System.out.println("COMPLETION_UI=http://127.0.0.1:"+server.getAddress().getPort()+"/completion-rules PID="+ProcessHandle.current().pid());
  }
}

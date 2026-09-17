import com.sun.net.httpserver.*;
import java.io.*;
import java.nio.file.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import xinguan.platform.*;

public final class Main extends HttpSupport {
  private final Path root;private final DataStore store;private final AuthService auth;private final String version;
  private final WorkflowRoutes workflow;
  private final ImportRoutes importing;
  private Main(Path root,Path data)throws Exception{this.root=root.toAbsolutePath().normalize();store=new DataStore(data);try{BootstrapConfig.initialize(store.platform,this.root.resolve(BootstrapConfig.FILE_NAME));auth=new AuthService(store.platform);version=readVersion(root);workflow=new WorkflowRoutes(store.platform,version);importing=new ImportRoutes(store.platform,auth,version);}catch(Exception e){store.close();throw e;}}
  public static void main(String[] args)throws Exception {
    Map<String,String> options=arguments(args);Path root=Path.of(options.getOrDefault("root",".")).toAbsolutePath().normalize();Path data=Path.of(options.getOrDefault("data-root",root.resolve("data").toString()));
    if(options.containsKey("migrate-only")){try(DataStore s=new DataStore(data)){System.out.println("数据库检查及迁移完成："+s.platform.diagnostics());}return;}
    if(options.containsKey("write-templates")){Path out=Path.of(options.get("write-templates"));Files.createDirectories(out);ExcelExporter exporter=new ExcelExporter();for(DatasetSchema s:DatasetSchema.all())Files.write(out.resolve(s.id+"-template.xlsx"),exporter.template(s.id));return;}
    int port=Integer.parseInt(options.getOrDefault("port","2874"));if(port<1024||port>65535)throw new IllegalArgumentException("端口应为 1024～65535");
    Main app=new Main(root,data);HttpServer server;
    try{server=HttpServer.create(new InetSocketAddress(options.getOrDefault("bind","0.0.0.0"),port),64);}catch(Exception e){app.store.close();throw e;}
    ExecutorService workers=Executors.newFixedThreadPool(4);server.setExecutor(workers);server.createContext("/",app::handle);
    Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(2);workers.shutdown();try{workers.awaitTermination(30,TimeUnit.SECONDS);app.store.close();}catch(Exception e){e.printStackTrace();}System.out.println("服务状态：已停止");}));
    server.start();System.out.println("智慧信管 V"+app.version+"（公共基础测试版）\n服务状态：运行中\n本机地址：http://127.0.0.1:"+port+"\n登录入口：http://127.0.0.1:"+port+"/login\n按 Ctrl+C 关闭服务");
  }
  private void handle(HttpExchange x)throws IOException {
    AuthService.Session session=null;
    try{
      String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
      if(method.equals("GET")&&path.equals("/health")){text(x,200,"RUNNING V"+version+" SCHEMA="+store.platform.schemaVersion()+"\n","text/plain; charset=utf-8");return;}
      if(method.equals("GET")&&path.startsWith("/assets/")){asset(x,path);return;}
      if(method.equals("POST")&&path.equals("/login")){login(x);return;}
      session=auth.session(x);IdentityPages identity=new IdentityPages(version,session);
      AccessPages access=new AccessPages(version,session);
      if(path.equals("/access/apply")) {
        if(method.equals("GET")){sendHtml(x,200,access.apply(auth.applicationCsrf(x),query(x.getRequestURI()).get("scope")));return;}
        if(method.equals("POST")){requireForm(x);Map<String,String> f=decodeForm(readLimited(x.getRequestBody(),8192));if(!auth.consumeApplicationCsrf(x,f.get("csrf")))throw new SecurityException("申请页面已失效，请重新打开申请页");if(!auth.allowApplication(x.getRemoteAddress().getAddress().getHostAddress())){sendHtml(x,429,access.error(429,"申请过于频繁，请十分钟后再试或联系管理员"));return;}try{String roleText=f.get("requestedRole");if(roleText==null||roleText.isBlank())throw new IllegalArgumentException("请选择拟申请角色");store.platform.access().apply(f.get("number"),f.get("name"),f.get("organization"),Role.valueOf(roleText));}catch(IllegalArgumentException|IllegalStateException e){sendHtml(x,400,access.error(400,e.getMessage()));return;}redirect(x,"/access/received");return;}
      }
      if(method.equals("GET")&&path.equals("/access/received")){sendHtml(x,200,access.receipt());return;}
      if(method.equals("GET")&&(path.equals("/admin")||path.equals("/admin/"))){redirect(x,session==null?"/login":"/");return;}
      if(path.startsWith("/admin")){sendHtml(x,404,identity.error(404,"旧管理入口已停用，请从统一登录入口进入"));return;}
      if(session==null){if(method.equals("GET")&&path.equals("/login"))sendHtml(x,200,identity.login("",auth.loginCsrf(x)));else redirect(x,"/login");return;}
      if(session.mustChangePassword&&!path.equals("/account/password")&&!path.equals("/logout")){redirect(x,"/account/password");return;}
      if(!session.safetyAccepted()&&!Set.of("/security","/security/ack","/account/password","/logout").contains(path)){redirect(x,"/security");return;}
      if(session.safetyAccepted())session.unreadCount=store.platform.notifications().unreadCount(session.actor);
      Map<String,String> q=query(x.getRequestURI());
      ImportPages imports=new ImportPages(version,session);
      if(method.equals("GET")){
        if(importing.get(x,session,q))return;
        if(path.equals("/export")){var result=new AuthorizedExportService(store).export(session.actor,q);sendDownload(x,result.bytes(),result.filename());return;}
        if(workflow.get(x,session,q))return;
        if(new BusinessRoutes(store,version).get(x,session,q))return;
        if(path.equals("/security")){sendHtml(x,200,access.safety());return;}
        if(path.equals("/access/requests")){boolean only=!"yes".equals(q.get("all"));int offset=integer(q.get("offset"),0);sendHtml(x,200,access.applications(store.platform.access().applications(session.actor,only,offset,25),only,offset));return;}
        if(path.equals("/access/request")){var request=store.platform.access().application(session.actor,q.get("id"));sendHtml(x,200,access.application(request,store.platform.access().canDecide(session.actor,request)));return;}
        if(path.equals("/notifications")){boolean unread="yes".equals(q.get("unread"));int offset=integer(q.get("offset"),0);sendHtml(x,200,new NotificationPages(version,session).inbox(store.platform.notifications().inbox(session.actor,unread,offset,25),unread,offset));return;}
        if(path.equals("/notifications/detail")){var notice=store.platform.access().notice(session.actor,q.get("id"));sendHtml(x,200,new NotificationPages(version,session).detail(notice,store.platform.access().noticeApplication(session.actor,notice.id())));return;}
        if(path.equals("/audit")){int offset=integer(q.get("offset"),0);var filter=new AccessPlatform.AuditFilter(q.get("category"),q.get("organization"),q.get("dataset"),q.get("search"),auditDate(q.get("from")),auditDate(q.get("through")));String submissionId=limit(q.get("submissionId"),80);var rows=submissionId.isEmpty()?store.platform.access().audit(session.actor,filter,offset,25):store.platform.access().auditForSubmission(session.actor,submissionId,filter,offset,25);sendHtml(x,200,new AuditPages(version,session).audit(rows,q,offset));return;}
        if(path.equals("/login")){redirect(x,"/");return;}
        if(path.equals("/account/password")){if(auth.passwordChangeExpired(session)){passwordRelogin(x);return;}sendHtml(x,200,identity.password(""));return;}
        if(path.startsWith("/people")){if(!PlatformStore.isManager(session.actor))throw new SecurityException("没有人员管理权限");
          if(path.equals("/people")){sendHtml(x,200,identity.people(store.platform.listUsers(session.actor),""));return;}
          if(path.equals("/people/new")){sendHtml(x,200,identity.userForm(null,Map.of(),""));return;}
          if(path.equals("/people/edit")){sendHtml(x,200,identity.userForm(store.platform.managedUser(session.actor,q.get("id")),Map.of(),""));return;}
        }
        if(path.equals("/foundation")){if(!AccessPolicy.all(session.actor))throw new SecurityException("没有全行基础状态查看权限");sendHtml(x,200,imports.diagnostics(store.platform.schemaVersion(),store.platform.diagnostics(),store.platform.auditEvents(session.actor,100)));return;}
      }
      if(method.equals("POST")&&(path.equals("/imports/upload")||path.startsWith("/imports/upload/"))){importing.upload(x,session,path.equals("/imports/upload")?"bundle":path.substring("/imports/upload/".length()));return;}
      if(method.equals("POST")){
        requireForm(x);Map<String,String> f=decodeForm(readLimited(x.getRequestBody(),2*1024*1024));if(!auth.csrf(session,f.get("csrf")))throw new SecurityException("页面校验已失效，请刷新后重试");
        if(importing.post(x,session,f))return;
        if(workflow.post(x,session,f))return;
        switch(path){
          case "/security/ack" -> {auth.acknowledgeSafety(session,f.get("noticeVersion"));redirect(x,"/");return;}
          case "/notifications/read" -> {store.platform.notifications().markRead(session.actor,f.get("id"));session.unreadCount=store.platform.notifications().unreadCount(session.actor);redirect(x,"/notifications");return;}
          case "/notifications/open" -> {var notice=store.platform.access().notice(session.actor,f.get("id"));String application=store.platform.access().noticeApplication(session.actor,notice.id());store.platform.notifications().markRead(session.actor,notice.id());session.unreadCount=store.platform.notifications().unreadCount(session.actor);if(!application.isEmpty()){redirect(x,"/access/request?id="+url(application));return;}if(!notice.submissionId().isEmpty()){store.platform.workflow().submission(session.actor,notice.submissionId());redirect(x,"/workflow/submission?id="+url(notice.submissionId()));return;}redirect(x,"/notifications");return;}
          case "/access/decision" -> {String action=f.get("action");Role role="APPROVE".equals(action)?Role.valueOf(f.getOrDefault("role","")):null;var result=store.platform.access().decide(session.actor,f.get("id"),Long.parseLong(f.get("revision")),action,role,f.get("reason"),f.get("requestId"));if(result.created()!=null)sendHtml(x,200,identity.created(result.created()));else redirect(x,"/access/request?id="+url(result.application().id()));return;}
          case "/logout" -> {auth.logout(x);x.getResponseHeaders().add("Set-Cookie",auth.clearCookie());redirect(x,"/login");return;}
          case "/account/password" -> {if(auth.passwordChangeExpired(session)){passwordRelogin(x);return;}try{if(!Objects.equals(f.get("next"),f.get("confirm")))throw new IllegalArgumentException("两次新密码输入不一致");auth.changePassword(session,f.get("next"));}catch(IllegalArgumentException e){sendHtml(x,400,identity.password(e.getMessage()));return;}x.getResponseHeaders().add("Set-Cookie",auth.clearCookie());redirect(x,"/login");return;}
          case "/people/create" -> {if(!PlatformStore.isManager(session.actor))throw new SecurityException("没有人员管理权限");try{sendHtml(x,200,identity.created(store.platform.createUser(session.actor,f.get("authNumber"),f.get("name"),Role.valueOf(f.getOrDefault("role","")),userOrganization(f))));}catch(IllegalArgumentException e){sendHtml(x,400,identity.userForm(null,f,e.getMessage()));}return;}
          case "/people/update" -> {UserAccount old=store.platform.managedUser(session.actor,f.get("id"));try{store.platform.updateUser(session.actor,old.id(),Long.parseLong(f.get("revision")),f.get("name"),Role.valueOf(f.getOrDefault("role","")),userOrganization(f),"true".equals(f.get("active")));}catch(IllegalArgumentException e){sendHtml(x,400,identity.userForm(old,f,e.getMessage()));return;}redirect(x,"/people");return;}
          case "/people/reset-password" -> {if(!"yes".equals(f.get("confirmReset")))throw new IllegalArgumentException("请先勾选重置密码确认");sendHtml(x,200,identity.created(store.platform.resetUserPassword(session.actor,f.get("id"),Long.parseLong(f.get("revision")))));return;}
          case "/people/initial-password" -> {UserAccount user=store.platform.managedUser(session.actor,f.get("id"));sendHtml(x,200,identity.initialPassword(user,store.platform.initialPassword(session.actor,user.id())));return;}
          case "/people/disable" -> {if(!"yes".equals(f.get("confirmDisable")))throw new IllegalArgumentException("请先勾选停用确认");UserAccount old=store.platform.managedUser(session.actor,f.get("id"));store.platform.updateUser(session.actor,old.id(),Long.parseLong(f.get("revision")),old.name(),old.role(),old.organizationId(),false);redirect(x,"/people");return;}
          case "/update-batch" -> {save(x,session,f);return;}
          default -> {}
        }
      }
      sendHtml(x,404,identity.error(404,"页面不存在"));
    }catch(SecurityException e){sendHtml(x,403,new PageLayout(version,session).error(403,e.getMessage()));}
    catch(WorkflowContracts.WorkflowException e){int status=switch(e.code()){case NOT_FOUND->404;case INVALID_INPUT,NO_REVIEWER,OWNER_CHANGED->400;case TRANSACTION_FAILED->500;default->409;};sendHtml(x,status,new PageLayout(version,session).error(status,e.getMessage()));}
    catch(ConcurrentModificationException e){sendHtml(x,409,new PageLayout(version,session).error(409,e.getMessage()));}
    catch(IllegalStateException e){sendHtml(x,400,new PageLayout(version,session).error(400,e.getMessage()));}
    catch(IllegalArgumentException|WorkbookImportException e){sendHtml(x,400,new PageLayout(version,session).error(400,e.getMessage()));}
    catch(RequestTooLargeException e){sendHtml(x,413,new PageLayout(version,session).error(413,"请求内容过大：普通表单最多 2 MB，上传批次最多 50 MB，请分批处理"));}
    catch(Exception e){e.printStackTrace();sendHtml(x,500,new PageLayout(version,session).error(500,"处理失败，请查看启动终端；未确认的操作不会写入"));}
    finally{x.close();}
  }
  private static LocalDate auditDate(String text){if(text==null||text.isBlank())return null;try{return LocalDate.parse(text);}catch(java.time.format.DateTimeParseException e){throw new IllegalArgumentException("日期请使用 YYYY-MM-DD 格式");}}
  private static String userOrganization(Map<String,String> fields){
    if(Role.DIVISION_ADMIN.name().equals(fields.get("role")))return Organizations.DIVISION;
    String org=fields.get("organization");if(org==null||org.isBlank())throw new IllegalArgumentException("请选择所属支行");return org;
  }
  private void passwordRelogin(HttpExchange x)throws IOException{auth.logout(x);x.getResponseHeaders().add("Set-Cookie",auth.clearCookie());sendHtml(x,200,new IdentityPages(version,null).login("距上次登录已超过 15 分钟，请重新登录后再打开“修改密码”。",auth.loginCsrf(x)));}
  private void login(HttpExchange x)throws IOException{requireForm(x);Map<String,String> f=decodeForm(readLimited(x.getRequestBody(),8192));if(!auth.consumeLoginCsrf(x,f.get("csrf")))throw new SecurityException("登录页面已失效，请重新打开登录页");AuthService.Session session=auth.authenticate(x.getRemoteAddress().getAddress().getHostAddress(),f.get("authNumber"),f.get("password"));if(session==null){sendHtml(x,401,new IdentityPages(version,null).login("账号或密码错误、账号停用或尝试过于频繁，请稍后重试",auth.loginCsrf(x)));return;}x.getResponseHeaders().add("Set-Cookie",auth.setCookie(session));redirect(x,session.mustChangePassword?"/account/password":"/security");}
  private void save(HttpExchange x,AuthService.Session session,Map<String,String> f)throws Exception {
    DatasetSchema schema=DatasetSchema.get(f.get("dataset"));int count=integer(f.get("rows"),-1);if(count<1||count>50)throw new IllegalArgumentException("保存记录数无效");
    List<RecordChange> changes=new ArrayList<>();
    for(int i=0;i<count;i++){
      BusinessRecord old=store.platform.find(session.actor,f.get("id"+i));if(!old.dataset().equals(schema.id))throw new IllegalArgumentException("表类型与记录不一致");
      Map<String,String> values=new TreeMap<>();for(int c=0;c<schema.width();c++)if(schema.editable(c)){
        String value=f.get("v"+i+"_"+c);if(value==null)throw new IllegalArgumentException("缺少填报字段，请刷新后重试");values.put(schema.fields.get(c).key(),value);
      }
      changes.add(new RecordChange(old.id(),Long.parseLong(f.get("version"+i)),values));
    }
    var preview=store.platform.workflow().previewDirect(session.actor,schema.id,changes);
    sendHtml(x,200,new WorkflowPages(version,session).preview(preview,"请核对本次修改后确认；正式数据尚未改变。"));
  }
  private void asset(HttpExchange x,String path)throws IOException{String name=path.substring(8);if(!Set.of("style.css","foundation.css","access.css","workflow.css","import.css","business.css","html5shiv.js","identity.js").contains(name)){text(x,404,"Not found","text/plain");return;}Path file=root.resolve("web/assets").resolve(name);byte[] bytes=Files.readAllBytes(file);security(x.getResponseHeaders());x.getResponseHeaders().set("Content-Type",name.endsWith(".css")?"text/css; charset=utf-8":"application/javascript; charset=utf-8");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);}
}

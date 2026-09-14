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
  private final WorkbookImporter importer=new WorkbookImporter();private final ExcelExporter exporter=new ExcelExporter();
  private final Map<String,PendingImport> pending=new ConcurrentHashMap<>();
  private record PendingImport(String sessionToken,String dataset,List<BusinessRecord> rows,String baseline,long expires){}
  private Main(Path root,Path data)throws Exception{this.root=root.toAbsolutePath().normalize();store=new DataStore(data);try{BootstrapConfig.initialize(store.platform,this.root.resolve(BootstrapConfig.FILE_NAME));auth=new AuthService(store.platform);version=readVersion(root);}catch(Exception e){store.close();throw e;}}
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
      if(method.equals("GET")&&path.equals("/health")){text(x,200,"RUNNING V"+version+" SCHEMA=1\n","text/plain; charset=utf-8");return;}
      if(method.equals("GET")&&path.startsWith("/assets/")){asset(x,path);return;}
      if(method.equals("POST")&&path.equals("/login")){login(x);return;}
      session=auth.session(x);IdentityPages identity=new IdentityPages(version,session);
      if(method.equals("GET")&&(path.equals("/admin")||path.equals("/admin/"))){redirect(x,session==null?"/login":"/");return;}
      if(path.startsWith("/admin")){sendHtml(x,404,identity.error(404,"旧管理入口已停用，请从统一登录入口进入"));return;}
      if(session==null){if(method.equals("GET")&&path.equals("/login"))sendHtml(x,200,identity.login("",auth.loginCsrf(x)));else redirect(x,"/login");return;}
      if(session.mustChangePassword&&!path.equals("/account/password")&&!path.equals("/logout")){redirect(x,"/account/password");return;}
      Map<String,String> q=query(x.getRequestURI());
      ImportPages imports=new ImportPages(version,session);
      if(method.equals("GET")){
        if(path.equals("/login")){redirect(x,"/");return;}
        if(path.equals("/account/password")){if(auth.passwordChangeExpired(session)){passwordRelogin(x);return;}sendHtml(x,200,identity.password(""));return;}
        if(path.startsWith("/people")){if(!PlatformStore.isManager(session.actor))throw new SecurityException("没有人员管理权限");
          if(path.equals("/people")){sendHtml(x,200,identity.people(store.platform.listUsers(session.actor),""));return;}
          if(path.equals("/people/new")){sendHtml(x,200,identity.userForm(null,Map.of(),""));return;}
          if(path.equals("/people/edit")){sendHtml(x,200,identity.userForm(store.platform.managedUser(session.actor,q.get("id")),Map.of(),""));return;}
        }
        if(path.equals("/imports")){AccessPolicy.require(session.actor,AccessPolicy.Action.UPLOAD,Organizations.DIVISION);sendHtml(x,200,imports.imports(session,limit(q.get("notice"),800),"1".equals(q.get("error"))));return;}
        if(path.equals("/foundation")){if(!AccessPolicy.all(session.actor))throw new SecurityException("没有全行基础状态查看权限");sendHtml(x,200,imports.diagnostics(store.platform.diagnostics(),store.platform.auditEvents(session.actor,100)));return;}
        if(path.equals("/template")){AccessPolicy.require(session.actor,AccessPolicy.Action.UPLOAD,Organizations.DIVISION);String type=q.get("dataset");sendDownload(x,exporter.template(type),DatasetSchema.get(type).label+"_空白模板.xlsx");return;}
        DashboardData d=dashboard(q,session.actor);RiskPages risk=new RiskPages(version,session);String type=q.getOrDefault("dataset","multi");String branch=limit(q.get("branch"),100),search=limit(q.get("q"),100);
        if(!branch.isBlank()){String org=Organizations.resolve(branch);AccessPolicy.require(session.actor,AccessPolicy.Action.VIEW,org);branch=Organizations.label(org);}
        if(path.equals("/")){sendHtml(x,200,risk.dashboard(d));return;}
        if(path.equals("/branch")){sendHtml(x,200,risk.branch(d,branch));return;}
        if(path.equals("/details")){sendHtml(x,200,risk.details(d,type,search,branch,integer(q.get("page"),1),session));return;}
        if(path.equals("/export")){sendDownload(x,exporter.export(type,d.range.label,d.filtered(type,search,branch)),DatasetSchema.get(type).label+"_"+d.range.start+"_"+d.range.end+".xlsx");return;}
      }
      if(method.equals("POST")&&path.startsWith("/imports/upload/")){upload(x,session,path.substring("/imports/upload/".length()));return;}
      if(method.equals("POST")){
        requireForm(x);Map<String,String> f=decodeForm(readLimited(x.getRequestBody(),2*1024*1024));if(!auth.csrf(session,f.get("csrf")))throw new SecurityException("页面校验已失效，请刷新后重试");
        switch(path){
          case "/logout" -> {auth.logout(x);x.getResponseHeaders().add("Set-Cookie",auth.clearCookie());redirect(x,"/login");return;}
          case "/account/password" -> {if(auth.passwordChangeExpired(session)){passwordRelogin(x);return;}try{if(!Objects.equals(f.get("next"),f.get("confirm")))throw new IllegalArgumentException("两次新密码输入不一致");auth.changePassword(session,f.get("next"));}catch(IllegalArgumentException e){sendHtml(x,400,identity.password(e.getMessage()));return;}x.getResponseHeaders().add("Set-Cookie",auth.clearCookie());redirect(x,"/login");return;}
          case "/people/create" -> {if(!PlatformStore.isManager(session.actor))throw new SecurityException("没有人员管理权限");try{sendHtml(x,200,identity.created(store.platform.createUser(session.actor,f.get("authNumber"),f.get("name"),Role.valueOf(f.getOrDefault("role","")),userOrganization(f))));}catch(IllegalArgumentException e){sendHtml(x,400,identity.userForm(null,f,e.getMessage()));}return;}
          case "/people/update" -> {UserAccount old=store.platform.managedUser(session.actor,f.get("id"));try{store.platform.updateUser(session.actor,old.id(),Long.parseLong(f.get("revision")),f.get("name"),Role.valueOf(f.getOrDefault("role","")),userOrganization(f),"true".equals(f.get("active")));}catch(IllegalArgumentException e){sendHtml(x,400,identity.userForm(old,f,e.getMessage()));return;}redirect(x,"/people");return;}
          case "/people/reset-password" -> {if(!"yes".equals(f.get("confirmReset")))throw new IllegalArgumentException("请先勾选重置密码确认");sendHtml(x,200,identity.created(store.platform.resetUserPassword(session.actor,f.get("id"),Long.parseLong(f.get("revision")))));return;}
          case "/people/disable" -> {if(!"yes".equals(f.get("confirmDisable")))throw new IllegalArgumentException("请先勾选停用确认");UserAccount old=store.platform.managedUser(session.actor,f.get("id"));store.platform.updateUser(session.actor,old.id(),Long.parseLong(f.get("revision")),old.name(),old.role(),old.organizationId(),false);redirect(x,"/people");return;}
          case "/update-batch" -> {save(x,session,f);return;}
          case "/imports/confirm" -> {confirmImport(x,session,f);return;}
          default -> {}
        }
      }
      sendHtml(x,404,identity.error(404,"页面不存在"));
    }catch(SecurityException e){sendHtml(x,403,new PageLayout(version,session).error(403,e.getMessage()));}
    catch(ConcurrentModificationException e){sendHtml(x,409,new PageLayout(version,session).error(409,e.getMessage()));}
    catch(IllegalArgumentException|WorkbookImportException e){sendHtml(x,400,new PageLayout(version,session).error(400,e.getMessage()));}
    catch(RequestTooLargeException e){sendHtml(x,413,new PageLayout(version,session).error(413,"上传批次超过 50 MB，请分批上传"));}
    catch(Exception e){e.printStackTrace();sendHtml(x,500,new PageLayout(version,session).error(500,"处理失败，请查看启动终端；未确认的操作不会写入"));}
    finally{x.close();}
  }
  private static String userOrganization(Map<String,String> fields){
    if(Role.DIVISION_ADMIN.name().equals(fields.get("role")))return Organizations.DIVISION;
    String org=fields.get("organization");if(org==null||org.isBlank())throw new IllegalArgumentException("请选择所属支行");return org;
  }
  private void passwordRelogin(HttpExchange x)throws IOException{auth.logout(x);x.getResponseHeaders().add("Set-Cookie",auth.clearCookie());sendHtml(x,200,new IdentityPages(version,null).login("距上次登录已超过 15 分钟，请重新登录后再打开“修改密码”。",auth.loginCsrf(x)));}
  private DashboardData dashboard(Map<String,String> q,ActorContext a){List<String> months=store.months(a);RangeSelection r=RangeSelection.from(q,months);DashboardData d=new DashboardData(r,months,store.readAll(a),store.readRange(r,a));if(!AccessPolicy.all(a))d.branches.entrySet().removeIf(e->!e.getKey().equals(Organizations.label(a.organizationId())));return d;}
  private void login(HttpExchange x)throws IOException{requireForm(x);Map<String,String> f=decodeForm(readLimited(x.getRequestBody(),8192));if(!auth.consumeLoginCsrf(x,f.get("csrf")))throw new SecurityException("登录页面已失效，请重新打开登录页");AuthService.Session session=auth.authenticate(x.getRemoteAddress().getAddress().getHostAddress(),f.get("authNumber"),f.get("password"));if(session==null){sendHtml(x,401,new IdentityPages(version,null).login("账号或密码错误、账号停用或尝试过于频繁，请稍后重试",auth.loginCsrf(x)));return;}x.getResponseHeaders().add("Set-Cookie",auth.setCookie(session));redirect(x,session.mustChangePassword?"/account/password":"/");}
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
    store.platform.publishDirect(session.actor,changes,f.get("requestId"));RangeSelection r=RangeSelection.from(f,store.months(session.actor));redirect(x,"/details?"+r.queryString()+"&dataset="+url(schema.id)+"&branch="+url(f.getOrDefault("branch",""))+"&q="+url(f.getOrDefault("q",""))+"&page="+Math.max(1,integer(f.get("page"),1)));
  }
  private void upload(HttpExchange x,AuthService.Session session,String dataset)throws Exception {
    DatasetSchema.get(dataset);AccessPolicy.require(session.actor,AccessPolicy.Action.UPLOAD,Organizations.DIVISION);
    String type=x.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.toLowerCase().startsWith("multipart/form-data"))throw new IllegalArgumentException("上传格式错误");String boundary=boundary(type);if(boundary.isBlank()||boundary.length()>200)throw new IllegalArgumentException("上传边界错误");
    Multipart form=parseMultipart(readLimited(x.getRequestBody(),50*1024*1024),boundary);if(!auth.csrf(session,form.fields.get("csrf")))throw new SecurityException("页面校验已失效");
    if(form.files.isEmpty()||form.files.size()>10)throw new IllegalArgumentException("请选择 1～10 个文件");
    List<BusinessRecord> rows=new ArrayList<>();int skipped=0;
    Path tempDir=store.dataRoot().resolve("uploads");Files.createDirectories(tempDir);
    for(Part part:form.files){if(part.data.length==0)continue;if(part.data.length>20*1024*1024)throw new IllegalArgumentException("单文件不能超过 20 MB");Path temp=Files.createTempFile(tempDir,"parse-",".tmp");
      try{Files.write(temp,part.data);WorkbookImporter.Parsed parsed=importer.read(temp,part.filename,form.fields.get("month"),form.fields.get("period"),dataset);rows.addAll(parsed.rows());skipped+=parsed.skippedExamples();if(rows.size()>20000)throw new IllegalArgumentException("一批最多 20000 条，请分批上传");}finally{Files.deleteIfExists(temp);}
    }
    if(rows.isEmpty()){adminRedirect(x,"格式校验通过，未发现业务数据；跳过示例／说明行 "+skipped+" 条。空模板未写入正式数据。",false);return;}
    Map<String,List<String>> batchSources=new HashMap<>();for(BusinessRecord row:rows){List<String> prior=batchSources.putIfAbsent(PlatformStore.fingerprint(row),row.values());if(prior!=null&&!prior.equals(row.values()))throw new IllegalArgumentException("本批上传文件中，同一来源出现不同填报内容，请先统一这些重复记录后再上传；本次未写入");}
    pending.entrySet().removeIf(e->e.getValue().expires<System.currentTimeMillis());pending.entrySet().removeIf(e->e.getValue().sessionToken.equals(session.token));
    if(pending.size()>=3)throw new IllegalArgumentException("已有多个上传待确认，请稍后再试");
    List<BusinessRecord> existing=store.platform.list(session.actor,dataset,null,null);String token=UUID.randomUUID().toString();pending.put(token,new PendingImport(session.token,dataset,List.copyOf(rows),PlatformStore.baseline(existing),System.currentTimeMillis()+15*60*1000));sendHtml(x,200,new ImportPages(version,session).importPreview(session,token,dataset,rows,existing,skipped));
  }
  private void confirmImport(HttpExchange x,AuthService.Session s,Map<String,String> f)throws Exception {
    String token=f.get("token");PendingImport p=token==null?null:pending.get(token);if(p==null||!p.sessionToken.equals(s.token)||p.expires<System.currentTimeMillis())throw new IllegalArgumentException("导入预览已过期，请重新选择文件");
    boolean overwrite="overwrite".equals(f.get("mode"));if(overwrite&&!"yes".equals(f.get("confirmOverwrite")))throw new IllegalArgumentException("覆盖可能清空已有填报值，请勾选确认，或保留原填报内容");
    var result=store.platform.importRows(s.actor,p.dataset,p.rows,overwrite,"import-"+token,p.baseline);pending.remove(token);adminRedirect(x,"导入完成：新增 "+result.added()+" 条，重复 "+result.duplicates()+" 条，保留已有填写 "+result.preserved()+" 条。",false);
  }
  private void asset(HttpExchange x,String path)throws IOException{String name=path.substring(8);if(!Set.of("style.css","foundation.css","html5shiv.js","identity.js").contains(name)){text(x,404,"Not found","text/plain");return;}Path file=root.resolve("web/assets").resolve(name);byte[] bytes=Files.readAllBytes(file);security(x.getResponseHeaders());x.getResponseHeaders().set("Content-Type",name.endsWith(".css")?"text/css; charset=utf-8":"application/javascript; charset=utf-8");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);}
}

import com.sun.net.httpserver.HttpExchange;
import java.time.*;
import java.util.*;
import xinguan.platform.*;

/** Main checks persisted identity, first login, security notice and CSRF before these handlers. */
final class MaintenanceRoutes extends HttpSupport {
  private final MaintenancePlatform maintenance;private final String version;
  MaintenanceRoutes(PlatformStore store,String version){maintenance=store.maintenance();this.version=version;}
  boolean get(HttpExchange x,AuthService.Session s,Map<String,String> q)throws Exception{
    if(!x.getRequestURI().getPath().equals("/imports/delete"))return false;
    sendHtml(x,200,new MaintenancePages(version,s).months(maintenance.months(s.actor)));return true;
  }
  boolean post(HttpExchange x,AuthService.Session s,Map<String,String> f)throws Exception{
    String path=x.getRequestURI().getPath();var pages=new MaintenancePages(version,s);
    switch(path){
      case "/audit/cleanup/preview" -> sendHtml(x,200,pages.preview(maintenance.previewAudit(s.actor,filter(f),limit(f.get("submissionId"),80))));
      case "/imports/delete/preview" -> sendHtml(x,200,pages.preview(maintenance.previewMonth(s.actor,f.get("month"))));
      case "/audit/cleanup/confirm","/imports/delete/confirm" -> {
        if(!"yes".equals(f.get("confirmed")))throw new IllegalArgumentException("请先核对删除范围并确认");
        String kind=path.startsWith("/audit/")?"audit":"month";
        sendHtml(x,200,pages.result(kind,maintenance.confirm(s.actor,f.get("token"),kind)));
      }
      default -> {return false;}
    }return true;
  }
  static AccessPlatform.AuditFilter filter(Map<String,String> f){return new AccessPlatform.AuditFilter(f.get("category"),f.get("organization"),f.get("dataset"),f.get("search"),date(f.get("from")),date(f.get("through")));}
  private static LocalDate date(String value){if(value==null||value.isBlank())return null;try{return LocalDate.parse(value);}catch(RuntimeException e){throw new IllegalArgumentException("日期请使用 YYYY-MM-DD 格式");}}
}

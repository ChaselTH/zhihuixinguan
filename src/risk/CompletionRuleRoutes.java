import com.sun.net.httpserver.HttpExchange;
import java.util.*;
import xinguan.platform.*;

final class CompletionRuleRoutes extends HttpSupport {
  private final PlatformStore store;private final String version;
  CompletionRuleRoutes(PlatformStore store,String version){this.store=store;this.version=version;}
  boolean get(HttpExchange x,AuthService.Session session,Map<String,String> q)throws Exception {
    if(!x.getRequestURI().getPath().equals("/completion-rules"))return false;
    String dataset=q.getOrDefault("dataset","multi");DatasetSchema.get(dataset);
    sendHtml(x,200,new CompletionRulePages(version,session).returnTo(q.get("return")).settings(store.completionRules().visible(session.actor).get(dataset),"yes".equals(q.get("saved"))));return true;
  }
  boolean post(HttpExchange x,AuthService.Session session,Map<String,String> form)throws Exception {
    if(!x.getRequestURI().getPath().equals("/completion-rules/save"))return false;
    if(session.actor.role()!=Role.DIVISION_ADMIN)throw new SecurityException("仅分行管理员可设置填报必填列");
    DatasetSchema schema=DatasetSchema.get(form.get("dataset"));Set<String> required=new LinkedHashSet<>();
    for(String key:form.keySet())if(key.startsWith("required_")&&!schema.editable(schema.index(key.substring(9))))throw new IllegalArgumentException("只能设置本清单的黄色填报列");
    for(var field:schema.fields)if(field.editable()){
      String value=form.get("required_"+field.key());
      if(!Set.of("true","false").contains(value==null?"":value))throw new IllegalArgumentException("请为每个黄色填报列选择是否必填");
      if(value.equals("true"))required.add(field.key());
    }
    store.completionRules().save(session.actor,schema.id,required,Long.parseLong(form.getOrDefault("revision","-1")));
    redirect(x,ReturnNavigation.link("/completion-rules?dataset="+schema.id+"&saved=yes",ReturnNavigation.safe(form.get("return"),"/")));return true;
  }
}

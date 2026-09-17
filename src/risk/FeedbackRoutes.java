import com.sun.net.httpserver.HttpExchange;
import java.util.*;
import xinguan.platform.*;

final class FeedbackRoutes extends HttpSupport {
  private final DataStore store;private final String version;
  FeedbackRoutes(DataStore store,String version){this.store=store;this.version=version;}
  boolean get(HttpExchange x,AuthService.Session session,Map<String,String> q)throws Exception{
    if(!x.getRequestURI().getPath().equals("/deadlines"))return false;
    var months=store.months(session.actor);var range=RangeSelection.from(q,months);
    var data=new DashboardData(range,months,List.of(),store.readRange(range,session.actor));
    sendHtml(x,200,new FeedbackPages(version,session).settings(data,"yes".equals(q.get("saved"))?"反馈截止日期已更新":""));return true;
  }
  boolean post(HttpExchange x,AuthService.Session session,Map<String,String> f)throws Exception{
    if(!x.getRequestURI().getPath().equals("/deadlines/save"))return false;
    if(!AccessPolicy.all(session.actor))throw new SecurityException("仅超级管理员和分行管理员可设置反馈截止日期");
    String action=f.getOrDefault("action","");
    if(!Set.of("save","clear").contains(action))throw new IllegalArgumentException("请选择保存或取消截止日期");
    String date=action.equals("clear")?"":f.getOrDefault("dueDate","").strip();
    if(action.equals("save")&&date.isEmpty())throw new IllegalArgumentException("请填写截止日期；需要取消时使用“取消截止日期”按钮");
    store.platform.deadlines().save(session.actor,f.get("dataset"),f.get("period"),date,Long.parseLong(f.getOrDefault("revision","-1")));
    var range=RangeSelection.from(f,store.months(session.actor));
    redirect(x,"/deadlines?"+range.queryString()+"&saved=yes");return true;
  }
}

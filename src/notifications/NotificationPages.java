import java.util.*;
import xinguan.platform.*;

final class NotificationPages extends PageLayout {
  NotificationPages(String version,AuthService.Session session){super(version,session);}
  String inbox(List<NotificationService.Notice> rows,boolean unread,int offset){
    String source="/notifications?unread="+(unread?"yes":"no")+"&offset="+offset;
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\"><h1>通知中心</h1><div class=\"notice-toolbar\"><span>未读 ").append(currentSession.unreadCount).append(" 条 · <a href=\"/notifications\">全部通知</a> · <a href=\"/notifications?unread=yes\">只看未读</a></span>");
    if(currentSession.unreadCount>0)b.append("<form method=\"post\" action=\"/notifications/read-all\" class=\"inline-form\">").append(hidden("csrf",currentSession.csrf)).append(hidden("return",source)).append("<button class=\"btn btn-light\" type=\"submit\">全部已读</button></form>");
    b.append("</div><div class=\"notice-list\">");
    for(var n:rows)b.append("<div class=\"identity-card notice-item ").append(n.readAt()==null?"notice-unread":"").append("\"><span class=\"eyebrow\">").append(e(time(n.createdAt().toString()))).append(" · ").append(n.readAt()==null?"未读":"已读").append("</span><h2><form class=\"inline-form\" method=\"post\" action=\"/notifications/open\">").append(hidden("csrf",currentSession.csrf)).append(hidden("id",n.id())).append(hidden("return",source)).append("<button class=\"link-button notice-link\" type=\"submit\">").append(e(n.title())).append("</button></form></h2><p>").append(e(n.summary())).append("</p></div>");
    if(rows.isEmpty())b.append("<div class=\"identity-card\">暂无通知</div>");
    return page("通知中心",b.append("</div>").append(AccessPages.pager("/notifications?unread="+(unread?"yes":"no"),offset,rows.size())).append("</div>").toString());
  }
  String detail(NotificationService.Notice n,String application,String source){
    String back=ReturnNavigation.safe(source,"/notifications");
    String here=ReturnNavigation.link("/notifications/detail?id="+u(n.id()),back);
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\">").append(backButton(back)).append("<section class=\"identity-card\"><h1>").append(e(n.title())).append("</h1><p>").append(e(time(n.createdAt().toString()))).append(" · ").append(e(Organizations.label(n.organizationId()))).append("</p><p class=\"access-pre\">").append(e(n.summary())).append("</p>");
    if(!application.isEmpty())b.append("<p><a class=\"btn btn-primary\" href=\"").append(e(ReturnNavigation.link("/access/request?id="+u(application),here))).append("\">查看权限申请</a></p>");
    else if(!n.submissionId().isEmpty())b.append("<p>工作流单据编号：<code>").append(e(n.submissionId())).append("</code></p><p><a class=\"btn btn-primary\" href=\"").append(e(ReturnNavigation.link("/workflow/submission?id="+u(n.submissionId()),here))).append("\">查看工作流单据</a></p>");
    return page("通知详情",b.append("<p class=\"field-note\">从通知列表打开时会先通过安全表单更新已读状态；本页直接访问不会改变已读状态。</p></section></div>").toString());
  }
}

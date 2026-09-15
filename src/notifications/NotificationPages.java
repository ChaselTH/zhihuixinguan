import java.util.*;
import xinguan.platform.*;

final class NotificationPages extends PageLayout {
  NotificationPages(String version,AuthService.Session session){super(version,session);}
  String inbox(List<NotificationService.Notice> rows,boolean unread,int offset){
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\"><h1>通知中心</h1><p>未读 ").append(currentSession.unreadCount).append(" 条 · <a href=\"/notifications\">全部通知</a> · <a href=\"/notifications?unread=yes\">只看未读</a></p><div class=\"notice-list\">");
    for(var n:rows)b.append("<div class=\"identity-card notice-item ").append(n.readAt()==null?"notice-unread":"").append("\"><span class=\"eyebrow\">").append(e(time(n.createdAt().toString()))).append(" · ").append(n.readAt()==null?"未读":"已读").append("</span><h2><a href=\"/notifications/detail?id=").append(u(n.id())).append("\">").append(e(n.title())).append("</a></h2><p>").append(e(n.summary())).append("</p></div>");
    if(rows.isEmpty())b.append("<div class=\"identity-card\">暂无通知</div>");
    return page("通知中心",b.append("</div>").append(AccessPages.pager("/notifications?unread="+(unread?"yes":"no"),offset,rows.size())).append("</div>").toString());
  }
  String detail(NotificationService.Notice n,String application){return page("通知详情",header()+"<div class=\"page-shell\"><section class=\"identity-card\"><h1>"+e(n.title())+"</h1><p>"+e(time(n.createdAt().toString()))+" · "+e(Organizations.label(n.organizationId()))+"</p><p class=\"access-pre\">"+e(n.summary())+"</p>"+(!application.isEmpty()?"<p><a class=\"btn btn-primary\" href=\"/access/request?id="+u(application)+"\">查看权限申请</a></p>":!n.submissionId().isEmpty()?"<p>工作流单据编号：<code>"+e(n.submissionId())+"</code></p><p><a class=\"btn btn-primary\" href=\"/workflow/submission?id="+u(n.submissionId())+"\">查看工作流单据</a></p>":"")+"<form method=\"post\" action=\"/notifications/read\">"+hidden("csrf",currentSession.csrf)+hidden("id",n.id())+"<button class=\"btn btn-light\" type=\"submit\">标记已读并返回</button></form></section></div>");}
}

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import xinguan.platform.*;

class PageLayout {
  final String version;
  final AuthService.Session currentSession;
  PageLayout(String version){this(version,null);}
  PageLayout(String version,AuthService.Session session){this.version=version;this.currentSession=session;}
  String page(String title,String body){return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"robots\" content=\"noindex,nofollow\"><title>"+e(title)+" - 智慧信管</title><link rel=\"stylesheet\" href=\"/assets/style.css\"><link rel=\"stylesheet\" href=\"/assets/foundation.css\"><link rel=\"stylesheet\" href=\"/assets/access.css\"><!--[if lt IE 9]><script src=\"/assets/html5shiv.js\"></script><![endif]--></head><body>"+body+"<footer><span>智慧信管</span><span>V"+e(version)+" · 联调测试版</span></footer><script src=\"/assets/business.js\"></script></body></html>";}
  String header(){StringBuilder b=new StringBuilder("<div class=\"simple-header\"><div class=\"simple-inner clearfix\"><a class=\"simple-brand\" href=\"/\"><span>信</span><strong>智慧信管</strong></a><div class=\"foundation-nav\">");if(currentSession!=null){ActorContext a=currentSession.actor;if(!currentSession.mustChangePassword&&currentSession.safetyAccepted()){b.append("<a href=\"/\">风险预警</a><a href=\"/internal\">行内数据</a>");if(PlatformStore.isManager(a))b.append("<a href=\"/people\">人员管理</a><a href=\"/access/requests\">权限审批</a>");b.append("<a href=\"/workflow\">工作流</a><a href=\"/audit\">操作记录</a><a href=\"/notifications\">通知");if(currentSession.unreadCount>0)b.append("<span class=\"notice-badge\">").append(currentSession.unreadCount>99?"99+":currentSession.unreadCount).append("</span>");b.append("</a>");if(a.role()==Role.DIVISION_ADMIN)b.append("<a href=\"/imports\">数据更新</a>");if(AccessPolicy.all(a))b.append("<a href=\"/foundation\">基础状态</a>");}b.append("<a href=\"/account/password\">修改密码</a><form class=\"nav-logout\" method=\"post\" action=\"/logout\">").append(hidden("csrf",currentSession.csrf)).append("<button type=\"submit\">退出</button></form>");}else b.append("<a href=\"/login\">登录</a>");b.append("<span>V").append(e(version)).append("</span></div></div></div>");if(currentSession!=null)b.append("<div class=\"identity-strip\">").append(e(currentSession.actor.name())).append(" · ").append(e(currentSession.authNumber)).append(" · ").append(roleName(currentSession.actor.role())).append(" · ").append(e(Organizations.label(currentSession.actor.organizationId()))).append("</div>");return b.toString();}
  String notice(){return "<div class=\"foundation-notice\">业务流程：操作员填写并提交 → 支行复核员复核或退回 → 分行管理员终审发布。保存的私人草稿、待复核内容和未确认导入均不进入正式统计与导出。</div>";}
  static String roleName(Role r){return switch(r){case SUPER_ADMIN->"超级管理员";case DIVISION_ADMIN->"分行管理员";case BRANCH_ADMIN->"支行管理员";case OPERATOR->"客户经理操作员";case REVIEWER->"客户经理复核员";};}
  String error(int status,String message){return page("访问提示",header()+"<div class=\"page-shell\"><h1>"+status+" · 操作未完成</h1><div class=\"alert alert-error\">"+e(message)+"</div><a class=\"btn btn-light\" href=\"/\">返回首页</a></div>");}
  String rangeForm(String action,DashboardData data,String extras){
    RangeSelection r=data.range;StringBuilder b=new StringBuilder("<form class=\"toolbar range-toolbar clearfix\" method=\"get\" action=\"").append(e(action)).append("\">").append(extras).append(hidden("scope",r.scope));
    b.append("<div class=\"month-copy\"><span class=\"eyebrow\">当前统计范围</span><strong>").append(e(r.label)).append("</strong></div><div class=\"range-controls\"><div class=\"range-tabs\">");
    String[] scopes={"month","quarter","year","custom"},labels={"月度","季度","年度","自定义"};
    for(int i=0;i<scopes.length;i++)b.append("<button class=\"range-tab").append(scopes[i].equals(r.scope)?" active":"").append("\" type=\"submit\" name=\"scopeMode\" value=\"").append(scopes[i]).append("\">").append(labels[i]).append("</button>");
    b.append("</div><div class=\"range-active-fields\">");
    if(r.scope.equals("custom"))b.append("<label>开始月份<input name=\"start\" value=\"").append(e(r.start)).append("\"></label><label>结束月份<input name=\"end\" value=\"").append(e(r.end)).append("\"></label>");
    else if(r.scope.equals("year")||r.scope.equals("quarter")){
      b.append("<label>年度<select name=\"year\">");for(String year:RangeSelection.availableYears(data.months))b.append(option(year,year+"年",r.year));b.append("</select></label>");
      if(r.scope.equals("quarter")){b.append("<label>季度<select name=\"quarter\">");for(int i=1;i<=4;i++)b.append(option(""+i,"第"+i+"季度",""+r.quarter));b.append("</select></label>");}
    }else{b.append("<label>月份<select name=\"month\">");if(data.months.isEmpty())b.append(option(r.month,r.month,r.month));for(String month:data.months)b.append(option(month,month,r.month));b.append("</select></label>");}
    // Retain inactive choices without duplicate parameter names.
    if(!r.scope.equals("month"))b.append(hidden("month",r.month));
    if(!r.scope.equals("year")&&!r.scope.equals("quarter"))b.append(hidden("year",r.year));
    if(!r.scope.equals("quarter"))b.append(hidden("quarter",""+r.quarter));
    if(!r.scope.equals("custom"))b.append(hidden("start",r.start)).append(hidden("end",r.end));
    return b.append("<button class=\"btn btn-dark\" type=\"submit\">查看</button></div></div></form>").toString();
  }
  static String backButton(String fallback){return backButton(fallback,true);}
  static String backButton(String fallback,boolean exact){return "<p class=\"business-back\"><a class=\"btn btn-light\" data-back=\""+(exact?"fixed":"yes")+"\" href=\""+e(fallback)+"\">← 返回上一页</a></p>";}
  static String rangeHidden(RangeSelection r){StringBuilder b=new StringBuilder();r.asParameters().forEach((k,v)->b.append(hidden(k,v)));return b.toString();}
  static String hidden(String name,String value){return "<input type=\"hidden\" name=\""+e(name)+"\" value=\""+e(value)+"\">";}
  static String option(String value,String label,String selected){return "<option value=\""+e(value)+"\""+(value.equals(selected)?" selected=\"selected\"":"")+">"+e(label)+"</option>";}
  static String e(String text){return text==null?"":text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
  static String u(String text){return URLEncoder.encode(text==null?"":text,StandardCharsets.UTF_8);}
  static String time(String value){try{return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.parse(value));}catch(Exception e){return value==null||value.isBlank()?"尚未更新":value;}}
  static String detailUrl(RangeSelection r,String dataset,String branch){return "/details?"+e(r.queryString())+"&amp;dataset="+u(dataset)+"&amp;branch="+u(branch);}
  static String detailUrl(RangeSelection r,String dataset,String branch,String source){return detailUrl(r,dataset,branch)+"&amp;return="+u(source);}
}

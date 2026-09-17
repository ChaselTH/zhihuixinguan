import java.util.*;
import xinguan.platform.*;

final class FeedbackPages extends PageLayout {
  FeedbackPages(String version,AuthService.Session session){super(version,session);}
  String home(DashboardData data){
    var periods=data.feedbackPeriods();boolean manager=AccessPolicy.all(currentSession.actor);
    StringBuilder b=new StringBuilder("<section class=\"panel feedback-panel\"><div class=\"panel-head\"><h2>本期反馈时间</h2><a class=\"btn btn-light\" href=\"/deadlines?").append(e(data.range.queryString())).append("\">").append(manager?"设置反馈截止日期":"查看全部期次").append("</a></div>");
    if(periods.isEmpty())b.append("<p class=\"business-empty\">当前范围暂无数据期次，导入后可设置反馈日期。</p>");
    else{
      b.append("<div class=\"feedback-list\">");
      for(var p:periods.stream().limit(9).toList()){
        b.append("<div class=\"feedback-item ").append(p.overdue()?"feedback-late":"").append("\"><strong>").append(e(DatasetSchema.get(p.dataset).label)).append("</strong><span>").append(e(p.period)).append("</span><span>截止：").append(p.due==null?"未设置":e(p.due.toString())).append("</span><strong class=\"feedback-remaining\">").append(e(p.reminder())).append("</strong><a href=\"").append(link(data,p,p.overdue()?"overdue":"incomplete")).append("\">").append(p.overdue()?"超期反馈 ":"未完成 ").append(p.total-p.completed).append(" 条</a></div>");
      }
      b.append("</div>");if(periods.size()>9)b.append("<p class=\"feedback-note\">当前范围共 ").append(periods.size()).append(" 个清单期次，以上显示最近 9 项；其余可进入全部期次查看。</p>");
    }
    return b.append("<p class=\"feedback-note\">按北京时间计算，截止当日结束前有效；以正式填报完成为准，刷新页面更新剩余时间。</p></section>").toString();
  }
  String settings(DashboardData data,String notice){
    boolean manager=AccessPolicy.all(currentSession.actor);
    StringBuilder b=new StringBuilder(backButton("/?"+data.range.queryString())).append("<h1>反馈截止日期</h1>");
    if(!notice.isEmpty())b.append("<p class=\"alert alert-success\">").append(e(notice)).append("</p>");
    b.append(rangeForm("/deadlines",data,"")).append("<p>按清单和来源期次分别设置，对全部支行生效。截止当天结束前有效；超期不阻止继续填写和复核。</p>");
    if(!manager)b.append("<p>仅超级管理员和分行管理员可以调整日期；以下只展示本支行涉及的期次。</p>");
    b.append("<div class=\"table-scroll\"><table class=\"data-table feedback-table\"><thead><tr><th>清单</th><th>来源期次</th><th>已完成／总数</th><th>截止日期与剩余时间</th><th>").append(manager?"设置":"明细").append("</th></tr></thead><tbody>");
    for(var p:data.feedbackPeriods()){
      b.append("<tr class=\"").append(p.overdue()?"row-overdue":"").append("\"><td>").append(e(DatasetSchema.get(p.dataset).label)).append("</td><td>").append(e(p.period)).append("</td><td>").append(p.completed).append(" / ").append(p.total).append("</td><td>").append(p.due==null?"未设置":e(p.due.toString())).append("<br>").append(e(p.reminder())).append("</td><td>");
      if(manager){
        b.append("<form method=\"post\" action=\"/deadlines/save\" class=\"deadline-form\">").append(hidden("csrf",currentSession.csrf)).append(hidden("dataset",p.dataset)).append(hidden("period",p.period)).append(hidden("revision",""+p.revision)).append(rangeHidden(data.range)).append("<label>截止日期 <input type=\"text\" name=\"dueDate\" maxlength=\"10\" placeholder=\"YYYY-MM-DD\" value=\"").append(p.due==null?"":e(p.due.toString())).append("\"></label> <button class=\"btn btn-primary\" type=\"submit\" name=\"action\" value=\"save\">保存</button>");
        if(p.due!=null)b.append(" <button class=\"btn btn-light\" type=\"submit\" name=\"action\" value=\"clear\">取消截止日期</button>");
        b.append("</form>");
      }
      b.append("<a href=\"").append(link(data,p,"all")).append("\">查看本期明细</a></td></tr>");
    }
    if(data.records.isEmpty())b.append("<tr><td colspan=\"5\">暂无数据期次，请先导入相应清单。</td></tr>");
    return new RiskPages(version,currentSession).shell("反馈截止日期",b.append("</tbody></table></div>").toString());
  }
  private String link(DashboardData data,DashboardData.FeedbackPeriod p,String completion){
    return "/details?"+e(new BusinessFilter(p.dataset,"","",completion,20,p.period).query(data.range));
  }
}

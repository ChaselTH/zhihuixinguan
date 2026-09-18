import java.util.*;
import xinguan.platform.*;

final class MaintenancePages extends PageLayout {
  MaintenancePages(String version,AuthService.Session session){super(version,session);}
  String months(List<String> months){
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\"><p><a class=\"btn btn-light\" href=\"/imports\">← 返回数据更新</a></p><section class=\"identity-card identity-narrow\"><h1>按月份删除数据</h1><p>选择月份后，先核对三类清单的记录数。删除范围包括该月全部支行、多次导入的数据。</p>");
    if(months.isEmpty())b.append("<p>当前没有可删除的数据。</p>");
    else{b.append("<form method=\"post\" action=\"/imports/delete/preview\">").append(hidden("csrf",currentSession.csrf)).append("<label>数据月份<select name=\"month\">");for(String m:months)b.append(option(m,m,null));b.append("</select></label><button class=\"btn btn-delete-person\" type=\"submit\">预览删除范围</button></form>");}
    return page("按月份删除数据",b.append("</section></div>").toString());
  }
  String preview(MaintenancePlatform.Preview p){
    boolean audit=p.kind().equals("audit");String back=audit?"/audit":"/imports/delete";
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\"><section class=\"identity-card identity-narrow\"><h1>").append(audit?"清理操作记录确认":"删除月份数据确认").append("</h1><p class=\"access-break\">范围：").append(e(p.scope())).append("</p><h2>本次共 ").append(p.count()).append(" 条</h2><ul>");
    p.counts().forEach((k,v)->b.append("<li>").append(e(k)).append("：").append(v).append(" 条</li>"));b.append("</ul>");
    if(audit)b.append("<p>清理的是当前筛选结果的全部分页，不只是当前页。明细及其工作流审计链接将永久删除，不能通过网页恢复；业务数据、填报和复核单不变。系统保留一条清理摘要，该摘要不参与后续清理。</p><p>仅清理这次预览冻结的记录，预览后新增的操作不会被删除。</p>");
    else{
      b.append("<p>删除后，这些记录不再出现在首页、明细、统计和导出中。账号、历史审批快照与操作记录保留。旧草稿不能再提交，需重新导入后重新填写；旧导入预览也需重新上传。</p>");
      if(p.crossMonth()>0)b.append("<div class=\"alert alert-error\">注意：其中 ").append(p.crossMonth()).append(" 条的期次跨月，整条删除也会从其他关联月份消失。请确认这是你要删除的范围。</div>");
      if(p.pending()>0)b.append("<div class=\"alert alert-error\">涉及 ").append(p.pending()).append(" 个待复核单，暂不能删除。请先处理这些提交单，再重新预览。</div>");
    }
    if(p.pending()==0)b.append("<form method=\"post\" action=\"").append(audit?"/audit/cleanup/confirm":"/imports/delete/confirm").append("\">").append(hidden("csrf",currentSession.csrf)).append(hidden("token",p.token())).append(hidden("confirmed","yes")).append("<button class=\"btn btn-delete-person\" type=\"submit\">").append(audit?"确认清理这些操作记录":"确认删除该月数据").append("</button></form>");
    b.append("<p><a class=\"btn btn-light\" href=\"").append(back).append("\">取消，返回</a></p><p class=\"field-note\">确认有效期为 15 分钟；服务重启后需重新预览。</p></section></div>");return page("删除确认",b.toString());
  }
  String result(String kind,int count){boolean audit=kind.equals("audit");return page("处理完成",header()+"<div class=\"page-shell\"><section class=\"identity-card\"><h1>处理完成</h1><p>"+(audit?"已清理操作记录":"已删除正式数据")+" "+count+" 条。</p><a class=\"btn btn-primary\" href=\""+(audit?"/audit":"/imports")+"\">返回</a></section></div>");}
}

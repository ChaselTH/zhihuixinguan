import java.util.*;
import xinguan.platform.*;

final class RiskPages extends PageLayout {
  RiskPages(String version,AuthService.Session session){super(version,session);}
  String dashboard(DashboardData d){
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\">").append(notice()).append("<div class=\"foundation-heading clearfix\"><h1>风险预警</h1><span>最近更新：").append(e(time(d.latestUpdate))).append("</span></div>").append(rangeForm("/",d,""));
    b.append("<div class=\"metric-grid clearfix\">").append(metric("多重预警",d.multiRows.size()+"","条记录")).append(metric("负面闭环",d.negativeRows.size()+"","条记录")).append(metric("资料补充进度",d.completionPercent()+"%",d.completedCount()+" / "+d.totalCount()+" 条，含交叉违约")).append(metric("交叉违约",d.crossRows.size()+"","行内数据")).append("</div>");
    b.append("<section class=\"panel\"><div class=\"panel-head\"><h2>机构数据与填报进度</h2></div><div class=\"foundation-branches clearfix\">");
    for(var branch:d.branches.values())b.append("<a class=\"foundation-branch\" href=\"/branch?").append(e(d.range.queryString())).append("&amp;branch=").append(u(branch.name)).append("\"><strong>").append(e(branch.name)).append("</strong><span class=\"completion-icon ").append(branch.total>0&&branch.completed==branch.total?"done":"pending").append("\">").append(branch.total>0&&branch.completed==branch.total?"✓":"!").append("</span><p>").append(branch.completed).append(" / ").append(branch.total).append(" 条已补充</p><div class=\"bar-track\"><i class=\"bar-fill bar-red\" style=\"width:").append(branch.percent()).append("%\"></i></div></a>");
    b.append("</div></section>");
    for(String dataset:List.of("multi","negative")){
      List<RowRef> all=d.rows(dataset),pending=new ArrayList<>();for(RowRef r:all)if(!DashboardData.rowComplete(dataset,r.values))pending.add(r);
      b.append("<section class=\"panel\"><div class=\"panel-head\"><h2>").append(e(DatasetSchema.get(dataset).label)).append(" · 未完成</h2><a class=\"btn btn-light\" href=\"").append(detailUrl(d.range,dataset,"")).append("\">查看全部 ").append(all.size()).append(" 条</a></div>").append(preview(dataset,pending.subList(0,Math.min(8,pending.size())))).append("</section>");
    }
    return page("风险预警",b.append("</div>").toString());
  }
  String details(DashboardData d,String dataset,String q,String branch,int pageNo,AuthService.Session session){
    DatasetSchema s=DatasetSchema.get(dataset);List<RowRef> all=d.filtered(dataset,q,branch);int pages=Math.max(1,(all.size()+49)/50);pageNo=Math.max(1,Math.min(pages,pageNo));
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell details-shell\">").append(notice()).append(rangeForm("/details",d,hidden("dataset",dataset)+hidden("branch",branch)+hidden("q",q)));
    b.append("<div class=\"details-title clearfix\"><h1>").append(e(s.label)).append(branch.isBlank()?"":" · "+e(branch)).append("</h1><p>共 ").append(all.size()).append(" 条；任意一个黄色填报单元格有内容即算完成。来源字段不参与完成判断。</p><a class=\"btn btn-export\" href=\"/export?").append(e(d.range.queryString())).append("&amp;dataset=").append(u(dataset)).append("&amp;branch=").append(u(branch)).append("&amp;q=").append(u(q)).append("\">导出 Excel</a></div>");
    b.append("<form class=\"search-form\" method=\"get\" action=\"/details\">").append(rangeHidden(d.range)).append(hidden("dataset",dataset)).append(hidden("branch",branch)).append("<input name=\"q\" value=\"").append(e(q)).append("\" placeholder=\"搜索企业或预警信息\"><button class=\"btn btn-dark\" type=\"submit\">检索</button></form>");
    int start=(pageNo-1)*50;b.append(table(dataset,all.subList(start,Math.min(start+50,all.size())),session,d.range,branch,q,pageNo));
    b.append("<div class=\"pagination\">");if(pageNo>1)b.append("<a href=\"").append(detailUrl(d.range,dataset,branch)).append("&amp;q=").append(u(q)).append("&amp;page=").append(pageNo-1).append("\">上一页</a>");
    b.append("<span>第 ").append(pageNo).append(" / ").append(pages).append(" 页</span>");if(pageNo<pages)b.append("<a href=\"").append(detailUrl(d.range,dataset,branch)).append("&amp;q=").append(u(q)).append("&amp;page=").append(pageNo+1).append("\">下一页</a>");
    return page(s.label,b.append("</div></div>").toString());
  }
  String branch(DashboardData d,String branch){StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\">").append(rangeForm("/branch",d,hidden("branch",branch))).append("<h1>").append(e(branch)).append("</h1>");for(DatasetSchema s:DatasetSchema.all()){List<RowRef> rows=d.filtered(s.id,"",branch);b.append("<section class=\"panel\"><div class=\"panel-head\"><h2>").append(e(s.label)).append("</h2><a class=\"btn btn-dark\" href=\"").append(detailUrl(d.range,s.id,branch)).append("\">查看及填写 ").append(rows.size()).append(" 条</a></div>").append(preview(s.id,rows.subList(0,Math.min(8,rows.size())))).append("</section>");}return page(branch,b.append("</div>").toString());}
  private String preview(String dataset,List<RowRef> refs){DatasetSchema s=DatasetSchema.get(dataset);StringBuilder b=new StringBuilder("<div class=\"table-scroll\"><table class=\"data-table\"><thead><tr><th>企业名称</th><th>支行</th><th>期次</th><th>填报情况</th></tr></thead><tbody>");for(RowRef ref:refs)b.append("<tr><td>").append(e(s.value(ref.values,s.customerColumn))).append("</td><td>").append(e(s.value(ref.values,s.branchColumn))).append("</td><td>").append(e(ref.record.period)).append("</td><td>").append(s.complete(ref.values)?"已完成":"未完成").append("</td></tr>");if(refs.isEmpty())b.append("<tr><td colspan=\"4\" class=\"table-empty\">暂无记录</td></tr>");return b.append("</tbody></table></div>").toString();}
  private String table(String dataset,List<RowRef> rows,AuthService.Session session,RangeSelection range,String branch,String q,int pageNo){
    DatasetSchema s=DatasetSchema.get(dataset);boolean canEdit=AccessPolicy.can(session.actor,AccessPolicy.Action.DIRECT_EDIT,session.actor.organizationId());
    StringBuilder b=new StringBuilder("<form method=\"post\" action=\"/update-batch\">").append(hidden("csrf",session.csrf)).append(hidden("requestId",UUID.randomUUID().toString())).append(rangeHidden(range)).append(hidden("dataset",dataset)).append(hidden("branch",branch)).append(hidden("q",q)).append(hidden("page",""+pageNo)).append(hidden("rows",""+rows.size()));
    if(!rows.isEmpty()&&canEdit)b.append("<div class=\"batch-edit-bar clearfix\"><span>统一保存当前页填写内容；黄色区域以外不能修改</span><button class=\"btn btn-primary\" type=\"submit\">保存资料补充</button></div>");
    b.append("<div class=\"table-scroll\"><table class=\"data-table detail-table\"><thead><tr>");for(var f:s.fields)b.append("<th class=\"").append(f.editable()?"editable-head ":"").append(f.title().length()>18?"col-long":"col-standard").append("\">").append(e(f.title())).append("</th>");b.append("<th>期次／历史保留信息</th></tr></thead><tbody>");
    for(int item=0;item<rows.size();item++){RowRef ref=rows.get(item);b.append("<tr class=\"").append(s.complete(ref.values)?"row-complete":"row-pending").append("\">");
      for(int c=0;c<s.width();c++){
        var field=s.fields.get(c);String value=s.value(ref.values,c);b.append("<td class=\"").append(field.editable()?"editable-cell ":"").append(field.title().length()>18?"col-long":"col-standard").append("\">");
        if(c==0)b.append(hidden("id"+item,ref.record.id)).append(hidden("version"+item,""+ref.record.versions.get(ref.rowIndex)));
        if(field.editable()&&canEdit){String name="v"+item+"_"+c;if(!field.options().isEmpty()){b.append("<select name=\"").append(name).append("\">").append(option("","请选择",value));if(!value.isBlank()&&!field.options().contains(value))b.append(option(value,value+"（历史值）",value));for(String o:field.options())b.append(option(o,o,value));b.append("</select>");}else b.append("<textarea rows=\"3\" name=\"").append(name).append("\">").append(e(value)).append("</textarea>");}
        else b.append(e(value));b.append("</td>");
      }
      b.append("<td class=\"col-long\">").append(e(ref.record.period));for(var entry:ref.record.legacyExtras.entrySet())b.append("<br>").append(e(entry.getKey())).append("：").append(e(entry.getValue()));b.append("</td></tr>");
    }
    if(rows.isEmpty())b.append("<tr><td colspan=\"").append(s.width()+1).append("\" class=\"table-empty\">暂无数据，请从对应入口上传表格</td></tr>");
    return b.append("</tbody></table></div></form>").toString();
  }
  private String metric(String title,String value,String note){return "<div class=\"metric-card\"><div class=\"metric-body\"><span class=\"metric-label\">"+e(title)+"</span><strong>"+e(value)+"</strong><small>"+e(note)+"</small></div></div>";}
}

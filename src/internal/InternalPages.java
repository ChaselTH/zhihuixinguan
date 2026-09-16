import java.util.*;
import xinguan.platform.*;

/** Only configured internal datasets are actionable; never reuse another schema for unknown templates. */
final class InternalPages extends PageLayout {
  InternalPages(String version,AuthService.Session session){super(version,session);}
  String overview(DashboardData data,BusinessFilter filter,BusinessWorkflowState states){
    RiskPages shared=new RiskPages(version,currentSession);var rows=filter.rows(data);long complete=rows.stream().filter(r->DatasetSchema.get("cross").complete(r.values)).count();
    StringBuilder b=new StringBuilder("<div class=\"foundation-heading clearfix\"><h1>行内数据</h1><span>最近更新：").append(e(time(data.latestUpdate))).append("</span></div>").append(rangeForm("/internal",data,filter.hidden()));
    b.append(shared.filterForm("/internal",data,filter)).append("<section class=\"internal-summary\"><h2>交叉违约清单</h2><strong>").append(rows.size()).append(" 条</strong><p>当前筛选已完成 ").append(complete).append(" 条，未完成 ").append(rows.size()-complete).append(" 条；仅展示当前账号授权的正式数据。</p><a class=\"btn btn-primary\" href=\"/details?").append(e(filter.query(data.range))).append("\">查看全部及填写</a> <a class=\"btn btn-export\" href=\"/export?").append(e(filter.query(data.range))).append("\">导出当前筛选 Excel</a></section>");
    b.append(RiskPages.legend()).append(shared.preview("cross",rows.stream().limit(8).toList(),states,data.range));
    b.append("<section class=\"internal-pending\"><h2>其他行内报表 · 待配置</h2><p>尚未提供对应模板，暂不开放上传和导出；不会将现有交叉违约格式用于其他报表。</p></section>");
    return shared.shell("行内数据",b.toString());
  }
}

import java.util.*;
import xinguan.platform.*;

/** Only configured internal datasets are actionable; never reuse another schema for unknown templates. */
final class InternalPages extends PageLayout {
  InternalPages(String version,AuthService.Session session){super(version,session);}
  String overview(DashboardData data,BusinessFilter filter,BusinessWorkflowState states){
    return new RiskPages(version,currentSession).shell("行内数据","<h1>行内数据</h1><section class=\"internal-pending\"><h2>行内报表 · 待配置</h2><p>暂无已配置模板。</p><a class=\"btn btn-light\" href=\"/\">返回风险预警</a></section>");
  }
}

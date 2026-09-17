import java.util.*;
import xinguan.platform.*;

/** The same official-data selection is used by list pages and authorized exports. */
final class BusinessFilter {
  final String dataset,branch,search,completion,period;final int pageSize;
  BusinessFilter(String dataset,String branch,String search,String completion){this(dataset,branch,search,completion,20);}
  BusinessFilter(String dataset,String branch,String search,String completion,int pageSize){this(dataset,branch,search,completion,pageSize,"");}
  BusinessFilter(String dataset,String branch,String search,String completion,int pageSize,String period){this.period=period;this.pageSize=pageSize;this.dataset=dataset;this.branch=branch;this.search=search;this.completion=completion;}
  static BusinessFilter from(ActorContext actor,Map<String,String> query){
    if(actor==null)throw new SecurityException("请先登录");
    String dataset=query.getOrDefault("dataset","multi");DatasetSchema.get(dataset);
    String branch=HttpSupport.limit(query.get("branch"),100);
    if(!branch.isBlank()){String org=Organizations.resolve(branch);AccessPolicy.require(actor,AccessPolicy.Action.VIEW,org);branch=Organizations.label(org);}
    if(!AccessPolicy.all(actor))branch=Organizations.label(actor.organizationId());
    return new BusinessFilter(dataset,branch,HttpSupport.limit(query.get("q"),100),completion(query.get("completion")),pageSize(query.get("pageSize")),HttpSupport.limit(query.get("period"),100));
  }
  static int pageSize(String value){if(value==null||value.isBlank())return 20;if(!Set.of("10","20","50").contains(value))throw new IllegalArgumentException("每页条数请选择 10、20 或 50");return Integer.parseInt(value);}
  static String completion(String value){String v=value==null||value.isBlank()?"all":value;if(!Set.of("all","complete","incomplete","overdue").contains(v))throw new IllegalArgumentException("完成状态筛选无效");return v;}
  String query(RangeSelection range){return range.queryString()+"&dataset="+PageLayout.u(dataset)+"&branch="+PageLayout.u(branch)+"&q="+PageLayout.u(search)+"&period="+PageLayout.u(period)+"&pageSize="+pageSize+"&completion="+completion;}
  String hidden(){return PageLayout.hidden("dataset",dataset)+PageLayout.hidden("branch",branch)+PageLayout.hidden("q",search)+PageLayout.hidden("completion",completion)+PageLayout.hidden("pageSize",""+pageSize)+PageLayout.hidden("period",period);}
  List<RowRef> rows(DashboardData data){return data.filtered(dataset,search,branch,completion).stream().filter(r->period.isBlank()||period.equals(r.record.period)).toList();}
}

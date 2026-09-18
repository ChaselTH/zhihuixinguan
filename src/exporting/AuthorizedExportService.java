import java.util.*;
import xinguan.platform.*;

/** Export is selected from the authorized official repository, never caller-supplied rows or drafts. */
final class AuthorizedExportService {
  record Result(byte[] bytes,String filename,int count) {}
  private final DataStore store;
  AuthorizedExportService(DataStore store){this.store=store;}
  Result exportProgress(ActorContext actor,Map<String,String> query)throws Exception{
    if(actor==null||!AccessPolicy.all(actor))throw new SecurityException("仅分行管理员和超级管理员可导出填报进度");
    BusinessFilter filter=BusinessFilter.from(actor,query);List<String> months=store.months(actor);RangeSelection range=RangeSelection.from(query,months);
    DashboardData data=store.dashboard(range,months,actor);
    return new Result(new ExcelExporter().progress(data,filter.branch),"填报进度_"+range.start+"_"+range.end+".xlsx",filter.branch.isBlank()?27:3);
  }
  Result export(ActorContext actor,Map<String,String> query)throws Exception{
    BusinessFilter filter=BusinessFilter.from(actor,query);DatasetSchema schema=DatasetSchema.get(filter.dataset);
    List<String> months=store.months(actor);RangeSelection range=RangeSelection.from(query,months);
    DashboardData data=store.dashboard(range,months,actor);
    var rows=filter.rows(data);
    return new Result(new ExcelExporter().export(schema.id,range.label,rows),schema.label+"_"+range.start+"_"+range.end+".xlsx",rows.size());
  }
}

import java.util.*;
import xinguan.platform.*;

/** Export is selected from the authorized official repository, never caller-supplied rows or drafts. */
final class AuthorizedExportService {
  record Result(byte[] bytes,String filename,int count) {}
  private final DataStore store;
  AuthorizedExportService(DataStore store){this.store=store;}
  Result export(ActorContext actor,Map<String,String> query)throws Exception{
    BusinessFilter filter=BusinessFilter.from(actor,query);DatasetSchema schema=DatasetSchema.get(filter.dataset);
    List<String> months=store.months(actor);RangeSelection range=RangeSelection.from(query,months);
    DashboardData data=new DashboardData(range,months,List.of(),store.readRange(range,actor));
    var rows=filter.rows(data);
    return new Result(new ExcelExporter().export(schema.id,range.label,rows),schema.label+"_"+range.start+"_"+range.end+".xlsx",rows.size());
  }
}

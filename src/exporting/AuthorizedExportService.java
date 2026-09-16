import java.util.*;
import xinguan.platform.*;

/** Export is selected from the authorized official repository, never caller-supplied rows or drafts. */
final class AuthorizedExportService {
  record Result(byte[] bytes,String filename,int count) {}
  private final DataStore store;
  AuthorizedExportService(DataStore store){this.store=store;}
  Result export(ActorContext actor,Map<String,String> query)throws Exception{
    DatasetSchema schema=DatasetSchema.get(query.getOrDefault("dataset","multi"));
    List<String> months=store.months(actor);RangeSelection range=RangeSelection.from(query,months);
    String branch=HttpSupport.limit(query.get("branch"),100),search=HttpSupport.limit(query.get("q"),100);
    if(!branch.isBlank()){String org=Organizations.resolve(branch);AccessPolicy.require(actor,AccessPolicy.Action.VIEW,org);branch=Organizations.label(org);}
    DashboardData data=new DashboardData(range,months,List.of(),store.readRange(range,actor));
    var rows=data.filtered(schema.id,search,branch);
    return new Result(new ExcelExporter().export(schema.id,range.label,rows),schema.label+"_"+range.start+"_"+range.end+".xlsx",rows.size());
  }
}

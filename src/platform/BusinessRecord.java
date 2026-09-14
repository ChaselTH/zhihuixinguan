package xinguan.platform;
import java.util.*;
public record BusinessRecord(String id,long version,String dataset,Period period,String organizationId,List<String> values,String filename,String importedAt,String updatedAt,Map<String,String> legacyExtras) {
  public BusinessRecord { values=List.copyOf(values);legacyExtras=Map.copyOf(legacyExtras); }
  public boolean complete(){return DatasetSchema.get(dataset).complete(values);}
}

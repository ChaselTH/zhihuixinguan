package xinguan.platform;
import java.util.*;
public record BusinessRecord(String id,long version,String dataset,Period period,String organizationId,List<String> values,String filename,String importedAt,String updatedAt,Map<String,String> legacyExtras) {
  public BusinessRecord { values=List.copyOf(values);legacyExtras=Map.copyOf(legacyExtras); }
  /** Legacy/default-policy helper for detached input records; live views must supply current completion rules. */
  public boolean complete(){return DatasetSchema.get(dataset).complete(values);}
  public boolean complete(CompletionRules.Setting rule){if(!dataset.equals(rule.dataset()))throw new IllegalArgumentException("填报规则与清单不一致");return rule.complete(values);}
}

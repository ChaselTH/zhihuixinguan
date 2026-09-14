package xinguan.platform;
import java.util.*;
public record RecordChange(String recordId,long expectedVersion,Map<String,String> values) {
  public RecordChange {values=Map.copyOf(values);}
}

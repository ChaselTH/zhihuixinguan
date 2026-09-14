package xinguan.platform;

import java.time.LocalDate;
import java.util.*;
import static xinguan.platform.WorkflowContracts.*;

/** Versioned Base64 fields using the platform codec, not executable Java serialization. */
final class WorkflowCodec {
  static String changes(List<RecordChange> changes) {
    List<String> result=new ArrayList<>();
    for(var change:changes) result.add(Codec.encode(List.of(change.recordId(),Long.toString(change.expectedVersion()),map(change.values()))));
    return Codec.encode(result);
  }
  static String rows(List<SnapshotRow> rows) {
    List<String> result=new ArrayList<>();result.add("workflow-snapshot-v1");
    for(var row:rows) {
      BusinessRecord r=row.before();
      result.add(Codec.encode(List.of(r.id(),Long.toString(r.version()),r.dataset(),r.period().key(),r.period().start().toString(),
        r.period().end().toString(),r.organizationId(),Codec.encode(r.values()),r.filename(),r.importedAt(),r.updatedAt(),map(r.legacyExtras()),map(row.change().values()))));
    }
    return Codec.encode(result);
  }
  static List<SnapshotRow> rows(String data) {
    List<String> encoded=Codec.decode(data);
    if(encoded.isEmpty()||!encoded.get(0).equals("workflow-snapshot-v1")||encoded.size()>201) throw new IllegalStateException("工作流快照格式不匹配");
    List<SnapshotRow> result=new ArrayList<>();
    for(int i=1;i<encoded.size();i++) {
      List<String> v=Codec.decode(encoded.get(i));
      if(v.size()!=13) throw new IllegalStateException("工作流快照损坏");
      BusinessRecord before=new BusinessRecord(v.get(0),Long.parseLong(v.get(1)),v.get(2),new Period(v.get(3),LocalDate.parse(v.get(4)),LocalDate.parse(v.get(5))),
        v.get(6),Codec.decode(v.get(7)),v.get(8),v.get(9),v.get(10),map(v.get(11)));
      result.add(new SnapshotRow(before,new RecordChange(before.id(),before.version(),map(v.get(12)))));
    }
    return List.copyOf(result);
  }
  private static String map(Map<String,String> map) {
    List<String> fields=new ArrayList<>();new TreeMap<>(map).forEach((k,v)->{fields.add(k);fields.add(v);});return Codec.encode(fields);
  }
  private static Map<String,String> map(String data) {
    if(data.isEmpty())return Map.of();
    List<String> fields=Codec.decode(data);if(fields.size()%2!=0)throw new IllegalStateException("工作流字段损坏");
    Map<String,String> result=new LinkedHashMap<>();for(int i=0;i<fields.size();i+=2)result.put(fields.get(i),fields.get(i+1));return result;
  }
  private WorkflowCodec() {}
}

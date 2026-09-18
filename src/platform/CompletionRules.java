package xinguan.platform;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Live completion policy, not a workflow validation gate. Empty required set preserves legacy any-yellow semantics. */
public final class CompletionRules {
  public record Setting(String dataset,Set<String> requiredFields,long revision) {
    public Setting { requiredFields=Set.copyOf(requiredFields); }
    public boolean complete(List<String> values){return DatasetSchema.get(dataset).complete(values,requiredFields);}
  }
  private final PlatformStore store;private final Connection db;private final Clock clock;private final Consumer<String> checkpoint;
  CompletionRules(PlatformStore store,Connection db,Clock clock,Consumer<String> checkpoint){this.store=store;this.db=db;this.clock=clock;this.checkpoint=checkpoint;}
  public Map<String,Setting> visible(ActorContext actor){
    return store.workflowTransaction(actor,()->{
      Map<String,Setting> result=new LinkedHashMap<>();
      for(var schema:DatasetSchema.all())result.put(schema.id,load(schema.id));
      return Collections.unmodifiableMap(result);
    });
  }
  public Setting save(ActorContext actor,String dataset,Set<String> required,long revision){
    return store.workflowTransaction(actor,()->{
      if(actor.role()!=Role.DIVISION_ADMIN)throw new SecurityException("仅分行管理员可设置填报必填列");
      DatasetSchema schema=DatasetSchema.get(dataset);
      if(required==null||revision<0)throw new IllegalArgumentException("必填设置或版本无效");
      for(String key:required)if(!schema.editable(schema.index(key)))throw new IllegalArgumentException("只能设置本清单的黄色填报列");
      Setting previous=load(dataset);
      if(previous.revision()!=revision)throw new ConcurrentModificationException("填报规则已被其他管理员修改，请刷新核对后重试");
      if(previous.requiredFields().equals(required))return previous;
      List<String> ordered=schema.fields.stream().filter(f->required.contains(f.key())).map(DatasetSchema.Field::key).toList();
      try(var st=db.prepareStatement("MERGE INTO completion_rules(dataset,required_fields,revision,updated_by,updated_at) KEY(dataset) VALUES(?,?,?,?,?)")){
        st.setString(1,dataset);st.setString(2,Codec.encode(ordered));st.setLong(3,revision+1);st.setString(4,actor.userId());st.setString(5,clock.instant().toString());st.executeUpdate();
      }
      checkpoint.accept("completion-rule-written");
      store.workflowAudit(actor,Organizations.DIVISION,"completion-rule:"+dataset,"COMPLETION_RULE_SET",UUID.randomUUID().toString(),
        summary(schema,previous.requiredFields()),summary(schema,required),schema.label+"；全部支行、所有期次按当前规则计算完成；允许未填齐提交，不更改业务数据");
      checkpoint.accept("completion-rule-audited");
      return new Setting(dataset,required,revision+1);
    });
  }
  private Setting load(String dataset)throws SQLException {
    try(var st=db.prepareStatement("SELECT * FROM completion_rules WHERE dataset=?")){
      st.setString(1,dataset);try(var rs=st.executeQuery()){
        if(!rs.next())return new Setting(dataset,Set.of(),0);
        String encoded=rs.getString("required_fields");Set<String> fields=encoded.isEmpty()?Set.of():new LinkedHashSet<>(Codec.decode(encoded));
        for(String key:fields)if(!DatasetSchema.get(dataset).editable(DatasetSchema.get(dataset).index(key)))throw new IllegalStateException("存储的填报规则包含未知字段，请联系维护人");
        return new Setting(dataset,fields,rs.getLong("revision"));
      }
    }
  }
  private static String summary(DatasetSchema schema,Set<String> required){return Codec.encode(schema.fields.stream().filter(DatasetSchema.Field::editable).map(f->f.title()+"："+(required.contains(f.key())?"必填":"选填")).toList());}
}

package xinguan.platform;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Per-dataset source-period configuration; never alters official records or workflow snapshots. */
public final class FeedbackDeadlines {
  public record Key(String dataset,String period) {}
  public record Setting(Key key,LocalDate dueDate,long revision) {}
  private final PlatformStore store;
  private final Connection db;
  private final Clock clock;
  private final Consumer<String> checkpoint;
  FeedbackDeadlines(PlatformStore store,Connection db,Clock clock,Consumer<String> checkpoint){
    this.store=store;this.db=db;this.clock=clock;this.checkpoint=checkpoint;
  }
  public Map<Key,Setting> visible(ActorContext actor){
    return store.workflowTransaction(actor,()->{
      String sql="SELECT d.* FROM feedback_deadlines d WHERE EXISTS (SELECT 1 FROM official_records r WHERE r.dataset=d.dataset AND r.period_key=d.period_key";
      if(!AccessPolicy.all(actor))sql+=" AND r.organization_id=?";
      sql+=")";
      Map<Key,Setting> result=new LinkedHashMap<>();
      try(var st=db.prepareStatement(sql)){
        if(!AccessPolicy.all(actor))st.setString(1,actor.organizationId());
        try(var rs=st.executeQuery()){while(rs.next()){Setting s=read(rs);result.put(s.key(),s);}}
      }
      return Collections.unmodifiableMap(result);
    });
  }
  public Setting save(ActorContext actor,String dataset,String period,String date,long revision){
    return store.workflowTransaction(actor,()->{
      if(!AccessPolicy.all(actor))throw new SecurityException("仅超级管理员和分行管理员可设置反馈截止日期");
      DatasetSchema.get(dataset);
      if(period==null||period.isBlank()||period.length()>100||revision<0)throw new IllegalArgumentException("期次或设置版本无效");
      LocalDate due=parseDate(date);
      try(var st=db.prepareStatement("SELECT COUNT(*) FROM official_records WHERE dataset=? AND period_key=?")){
        st.setString(1,dataset);st.setString(2,period);try(var rs=st.executeQuery()){rs.next();if(rs.getInt(1)==0)throw new IllegalArgumentException("该清单期次不存在，请先导入数据");}
      }
      Setting previous=null;
      try(var st=db.prepareStatement("SELECT * FROM feedback_deadlines WHERE dataset=? AND period_key=?")){
        st.setString(1,dataset);st.setString(2,period);try(var rs=st.executeQuery()){if(rs.next())previous=read(rs);}
      }
      long current=previous==null?0:previous.revision();
      if(revision!=current)throw new ConcurrentModificationException("截止日期已被其他管理员修改，请刷新核对后重试");
      if(Objects.equals(previous==null?null:previous.dueDate(),due))return previous==null?new Setting(new Key(dataset,period),null,0):previous;
      try(var st=db.prepareStatement("MERGE INTO feedback_deadlines(dataset,period_key,due_date,revision,updated_by,updated_at) KEY(dataset,period_key) VALUES(?,?,?,?,?,?)")){
        st.setString(1,dataset);st.setString(2,period);st.setObject(3,due);st.setLong(4,current+1);st.setString(5,actor.userId());st.setString(6,clock.instant().toString());st.executeUpdate();
      }
      checkpoint.accept("feedback-deadline-written");
      store.workflowAudit(actor,Organizations.DIVISION,"deadline:"+dataset+":"+period,"FEEDBACK_DEADLINE_SET",UUID.randomUUID().toString(),
        Codec.encode(List.of(previous==null||previous.dueDate()==null?"":previous.dueDate().toString())),
        Codec.encode(List.of(due==null?"":due.toString())),DatasetSchema.get(dataset).label+"；期次 "+period+"；"+(due==null?"取消截止日期":"北京时间 "+due+" 当日结束前反馈"));
      checkpoint.accept("feedback-deadline-audited");
      return new Setting(new Key(dataset,period),due,current+1);
    });
  }
  public static LocalDate parseDate(String value){
    if(value==null||value.isBlank())return null;
    try{
      if(!value.matches("20\\d{2}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();
      return LocalDate.parse(value);
    }catch(RuntimeException e){throw new IllegalArgumentException("反馈截止日期请填写有效的 YYYY-MM-DD 日期");}
  }
  private static Setting read(ResultSet rs)throws SQLException{
    java.sql.Date date=rs.getDate("due_date");
    return new Setting(new Key(rs.getString("dataset"),rs.getString("period_key")),date==null?null:date.toLocalDate(),rs.getLong("revision"));
  }
}

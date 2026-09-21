package xinguan.platform;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Bounded, identity-bound confirmations. No mutation from a GET or a preview. */
public final class MaintenancePlatform {
  public record Preview(String token,String kind,String scope,int count,Map<String,Integer> counts,int pending,int crossMonth) {
    public Preview { counts=Collections.unmodifiableMap(new LinkedHashMap<>(counts)); }
  }
  private static final class Ticket {
    final Preview preview;final String owner,baseline;final long revision;final Instant expires;final List<String> ids;
    int completed=-1;
    Ticket(Preview p,ActorContext a,Instant now,List<String> ids,String baseline){preview=p;owner=a.userId();revision=a.identityRevision();expires=now.plusSeconds(900);this.ids=List.copyOf(ids);this.baseline=baseline;}
  }
  private final PlatformStore store;private final Connection db;private final Clock clock;private final Consumer<String> checkpoint;
  private final Map<String,Ticket> tickets=new LinkedHashMap<>();
  MaintenancePlatform(PlatformStore store,Connection db,Clock clock,Consumer<String> checkpoint){this.store=store;this.db=db;this.clock=clock;this.checkpoint=checkpoint;}
  public synchronized Preview previewAudit(ActorContext a,AccessPlatform.AuditFilter filter,String submission){
    return call(a,()->{
      require(a,"audit");var ids=store.access().auditIds(a,filter,submission);bounded(ids.size());
      String category=switch(Objects.toString(filter.category(),"business")){case "all"->"全部类别";case "security"->"账号与安全";default->"业务修改";};
      String organization=filter.organization()==null||filter.organization().isBlank()?"全部机构":Organizations.label(filter.organization());
      String dataset=filter.dataset()==null||filter.dataset().isBlank()?"全部清单":DatasetSchema.get(filter.dataset()).label;
      String scope="类别："+category+"；机构："+organization+"；表类型："+dataset+"；日期："+Objects.toString(filter.from(),"不限")+" 至 "+Objects.toString(filter.through(),"不限")+"；搜索："+Objects.toString(filter.search(),"")+"；提交单："+Objects.toString(submission,"");
      var p=new Preview(UUID.randomUUID().toString(),"audit",scope,ids.size(),Map.of("操作记录",ids.size()),0,0);
      remember(new Ticket(p,a,clock.instant(),ids,""));return p;
    });
  }
  public synchronized List<String> months(ActorContext a){
    return call(a,()->{require(a,"month");Set<String> result=new TreeSet<>(Comparator.reverseOrder());
      for(var r:store.list(a,null,null,null))for(var m=YearMonth.from(r.period().start());!m.isAfter(YearMonth.from(r.period().end()));m=m.plusMonths(1))result.add(m.toString());
      return List.copyOf(result);
    });
  }
  public synchronized Preview previewMonth(ActorContext a,String month){
    return call(a,()->{
      require(a,"month");var ym=month(month);var rows=rows(a,ym);bounded(rows.size());
      Map<String,Integer> counts=new LinkedHashMap<>();for(var schema:DatasetSchema.all())counts.put(schema.label,0);
      List<String> ids=new ArrayList<>();int cross=0;
      for(var r:rows){ids.add(r.id());counts.merge(DatasetSchema.get(r.dataset()).label,1,Integer::sum);if(r.period().start().isBefore(ym.atDay(1))||r.period().end().isAfter(ym.atEndOfMonth()))cross++;}
      var p=new Preview(UUID.randomUUID().toString(),"month",ym.toString(),rows.size(),counts,pending(ids),cross);
      remember(new Ticket(p,a,clock.instant(),ids,PlatformStore.baseline(rows)));return p;
    });
  }
  public synchronized int confirm(ActorContext a,String token,String kind){
    Ticket t=tickets.get(token);
    int result=call(a,()->{
      require(a,kind);
      if(t==null||!t.owner.equals(a.userId())||t.revision!=a.identityRevision()||!t.preview.kind().equals(kind))throw new SecurityException("删除确认不存在或不属于当前身份，请重新预览");
      if(!clock.instant().isBefore(t.expires))throw new ConcurrentModificationException("确认已过期，请重新筛选并预览");
      if(t.completed>=0)return t.completed;
      if(kind.equals("audit")){
        for(String id:t.ids)if(count("SELECT COUNT(*) FROM audit_events WHERE id=? AND action<>'AUDIT_PURGE'",id)!=1)throw new ConcurrentModificationException("预览中的操作记录已变化，请重新筛选");
        for(String id:t.ids){exec("DELETE FROM workflow_audit_links WHERE event_id=?",id);exec("DELETE FROM audit_events WHERE id=?",id);}
        checkpoint.accept("audit-purge-written");
        store.workflowAudit(a,Organizations.DIVISION,token,"AUDIT_PURGE",token,"","","清理操作记录 "+t.ids.size()+" 条；冻结目标摘要 "+Codec.hash(String.join("|",t.ids))+"；"+t.preview.scope());
      }else{
        var rows=rows(a,month(t.preview.scope()));
        if(!PlatformStore.baseline(rows).equals(t.baseline))throw new ConcurrentModificationException("该月数据已导入、填写或删除，请重新预览；本次未删除");
        if(pending(t.ids)>0)throw new ConcurrentModificationException("该月有待支行复核或待分行终审数据，请先处理相关提交单，再重新预览删除；本次整批未删除");
        for(var r:rows){
          exec("INSERT INTO record_deletions VALUES(?,?,?,?)",r.id(),a.userId(),clock.instant().toString(),token);
          store.workflowAudit(a,r.organizationId(),r.id(),"DATA_MONTH_DELETE",token,Codec.encode(r.values()),"","按月份 "+t.preview.scope()+" 删除正式数据；历史快照保留");
        }
        checkpoint.accept("month-delete-written");
      }
      checkpoint.accept("maintenance-complete");return t.ids.size();
    });
    t.completed=result;return result; // Only mark consumed after the DB transaction committed.
  }
  private List<BusinessRecord> rows(ActorContext a,YearMonth m){return store.list(a,null,m.atDay(1),m.atEndOfMonth());}
  private int pending(List<String> ids)throws SQLException{
    Set<String> target=new HashSet<>(ids),submissions=new HashSet<>();
    try(var st=statement("SELECT record_id,submission_id FROM pending_submission_records UNION SELECT record_id,submission_id FROM workflow_record_state WHERE stage IN ('BRANCH_REVIEW','DIVISION_REVIEW')");var rs=st.executeQuery()){while(rs.next())if(target.contains(rs.getString(1)))submissions.add(rs.getString(2));}
    return submissions.size();
  }
  private void remember(Ticket t){tickets.values().removeIf(x->!clock.instant().isBefore(x.expires));if(tickets.size()>=50)throw new IllegalStateException("待确认删除过多，请稍后重试");tickets.put(t.preview.token(),t);}
  private static void bounded(int count){if(count==0)throw new IllegalArgumentException("当前条件下没有可删除记录");if(count>20000)throw new IllegalArgumentException("单次最多删除 20000 条，请缩小范围或联系维护人处理");}
  private static YearMonth month(String text){try{if(text==null||!text.matches("20\\d{2}-\\d{2}"))throw new IllegalArgumentException();return YearMonth.parse(text);}catch(RuntimeException e){throw new IllegalArgumentException("请选择有效的 YYYY-MM 月份");}}
  private static void require(ActorContext a,String kind){if(kind.equals("audit")){if(a.role()!=Role.SUPER_ADMIN)throw new SecurityException("仅超级管理员可清理操作记录");}else if(kind.equals("month")){if(a.role()!=Role.DIVISION_ADMIN)throw new SecurityException("仅分行管理员可按月删除数据");}else throw new IllegalArgumentException("删除类型无效");}
  private <T>T call(ActorContext a,PlatformStore.WorkflowWork<T> action){if(a==null||a.identityRevision()<1)throw new SecurityException("需要有效登录身份");return store.workflowTransaction(a,action);}
  private PreparedStatement statement(String sql,Object...args)throws SQLException{var st=db.prepareStatement(sql);for(int i=0;i<args.length;i++)st.setObject(i+1,args[i]);return st;}
  private int count(String sql,Object...args)throws SQLException{try(var st=statement(sql,args);var rs=st.executeQuery()){rs.next();return rs.getInt(1);}}
  private void exec(String sql,Object...args)throws SQLException{try(var st=statement(sql,args)){st.executeUpdate();}}
}

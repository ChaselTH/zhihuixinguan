package xinguan.platform;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Persistent, owner-bound import previews. All official writes and decisions commit together. */
public final class ImportPlatform {
  public enum Choice { PRESERVE, OVERWRITE, SKIP }
  public record SourceRow(BusinessRecord record,String sheet,int row) {}
  public record Item(int number,SourceRow source,BusinessRecord previous,int similar,int pending,Choice choice) {}
  public record Job(String id,String dataset,String state,long revision,String createdAt,String expiresAt,int count,int examples,int repeated,String resultId) {}
  public record Preview(Job job,List<Item> items) {public Preview{items=List.copyOf(items);}}
  private final PlatformStore store;private final Connection db;private final Clock clock;private final Consumer<String> checkpoint;
  private static final java.time.format.DateTimeFormatter TIMESTAMP=new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter();
  ImportPlatform(PlatformStore store,Connection db,Clock clock,Consumer<String> checkpoint){this.store=store;this.db=db;this.clock=clock;this.checkpoint=checkpoint;}

  public Job stage(ActorContext a,String dataset,List<SourceRow> input,int examples) {
    return call(a,()->stageInternal(a,dataset,input,examples));
  }
  /** Stage one workbook containing all three supported data sheets as one atomic job. */
  public Job stageBundle(ActorContext a,List<SourceRow> input,int examples) {
    return call(a,()->stageInternal(a,"bundle",input,examples));
  }
  private Job stageInternal(ActorContext a,String dataset,List<SourceRow> input,int examples)throws SQLException {
      boolean bundle="bundle".equals(dataset);if(!bundle)DatasetSchema.get(dataset);
      if(input.isEmpty()||input.size()>20000||examples<0)throw new IllegalArgumentException("一批需要 1～20000 条业务记录");
      expire();
      if(count("SELECT COUNT(*) FROM import_jobs WHERE state='PREVIEW' AND owner_id=?",a.userId())>=3||count("SELECT COUNT(*) FROM import_jobs WHERE state='PREVIEW'")>=20)throw new IllegalArgumentException("待确认导入任务过多，请先确认或取消已有任务");
      Map<String,SourceRow> unique=new LinkedHashMap<>();int repeated=0;long characters=0;
      for(var source:input){
        var r=source.record();DatasetSchema rowSchema=DatasetSchema.get(r.dataset());if(!bundle&&!dataset.equals(r.dataset()))throw new IllegalArgumentException("导入表种、机构或列数无效");validate(rowSchema,r);for(String value:r.values())characters+=value.length();if(characters>8_000_000)throw new IllegalArgumentException("本批单元格文字合计超过 800 万字，请分批上传");if(source.row()<1||source.sheet()==null||source.sheet().length()>200)throw new IllegalArgumentException("来源位置无效");
        String fp=PlatformStore.fingerprint(r);var old=unique.putIfAbsent(fp,source);
        if(old!=null){if(!old.record().values().equals(r.values()))throw new IllegalArgumentException("同批来源重复但填写不同："+location(old)+" 与 "+location(source)+"；请统一后重传，本批未写入");repeated++;}
      }
      List<BusinessRecord> existing=store.list(a,bundle?null:dataset,null,null);Map<String,BusinessRecord> bySource=new HashMap<>();Map<String,Integer> similar=new HashMap<>();Set<String> ambiguous=new HashSet<>();
      for(var r:existing){String fp=PlatformStore.fingerprint(r);if(bySource.putIfAbsent(fp,r)!=null)ambiguous.add(fp);similar.merge(customerKey(r),1,Integer::sum);}
      for(var r:unique.values())similar.merge(customerKey(r.record()),1,Integer::sum);
      Map<String,Integer> pending=new HashMap<>();try(var st=statement("SELECT record_id,COUNT(*) FROM pending_submission_records GROUP BY record_id");var rs=st.executeQuery()){while(rs.next())pending.put(rs.getString(1),rs.getInt(2));}
      String id=UUID.randomUUID().toString(),created=TIMESTAMP.format(clock.instant()),expires=TIMESTAMP.format(clock.instant().plus(Duration.ofMinutes(30)));
      exec("INSERT INTO import_jobs VALUES(?,?,?,?,'PREVIEW',1,?,?,?,?,?,?,NULL,NULL)",id,a.userId(),a.identityRevision(),dataset,created,expires,PlatformStore.baseline(existing),examples,repeated,unique.size());
      int n=0;for(var source:unique.values()){
        var r=source.record();String fp=PlatformStore.fingerprint(r);if(ambiguous.contains(fp))throw new IllegalArgumentException("存在多条相同历史来源，请先联系管理员核对");var before=bySource.get(fp);
        int candidates=before==null?Math.max(0,similar.get(customerKey(r))-1):0;
        exec("INSERT INTO import_job_rows VALUES(?,?,?,?,?,?,?,?,?)",id,++n,encode(r),before==null?"":encode(before),source.sheet(),source.row(),candidates,before==null?0:pending.getOrDefault(before.id(),0),Choice.PRESERVE.name());
      }
      checkpoint.accept("import-stage-written");return job(a,id);
  }
  public List<Job> jobs(ActorContext a,int offset,int limit){return call(a,()->{page(offset,limit);expire();List<Job> result=new ArrayList<>();try(var st=statement("SELECT * FROM import_jobs WHERE owner_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",a.userId(),limit,offset);var rs=st.executeQuery()){while(rs.next())result.add(readJob(rs));}return List.copyOf(result);});}
  public Preview preview(ActorContext a,String id,int offset,int limit){return call(a,()->{page(offset,limit);Job job=job(a,id);return new Preview(job,items(id,offset,limit));});}
  public Job choices(ActorContext a,String id,long revision,Map<Integer,Choice> changes,Choice all){return call(a,()->{
    Job j=job(a,id);editable(j,revision);if(changes==null||changes.size()>30)throw new IllegalArgumentException("每页最多调整 30 条");
    if(all!=null&&!changes.isEmpty())throw new IllegalArgumentException("不能同时使用逐条和整批选择");
    if(all!=null)exec("UPDATE import_job_rows SET choice=? WHERE job_id=?",all.name(),id);
    for(var entry:changes.entrySet()){if(entry.getKey()<1||entry.getKey()>j.count()||entry.getValue()==null)throw new IllegalArgumentException("选择的导入行无效");exec("UPDATE import_job_rows SET choice=? WHERE job_id=? AND row_number=?",entry.getValue().name(),id,entry.getKey());}
    exec("UPDATE import_jobs SET revision=revision+1 WHERE id=?",id);return job(a,id);
  });}
  public Job cancel(ActorContext a,String id,long revision){return call(a,()->{Job j=job(a,id);if(j.state().equals("CANCELLED"))return j;editable(j,revision);exec("UPDATE import_jobs SET state='CANCELLED',revision=revision+1 WHERE id=?",id);exec("DELETE FROM import_job_rows WHERE job_id=?",id);return job(a,id);});}
  /** mode=saved uses persisted row decisions; preserve/overwrite are explicit legacy bulk choices. */
  public PlatformStore.ImportOutcome confirm(ActorContext a,String id,long revision,String mode,boolean allowClear,boolean allowSimilar) {
    return call(a,()->{
      if(mode==null||!Set.of("saved","preserve","overwrite").contains(mode))throw new IllegalArgumentException("请选择有效的导入处理方式");
      String hash=Codec.hash(revision+"|"+mode+"|"+allowClear+"|"+allowSimilar);Job j=job(a,id);
      if(j.state().equals("COMMITTED")){
        if(!hash.equals(scalar("SELECT confirm_hash FROM import_jobs WHERE id=?",id)))throw new ConcurrentModificationException("此任务已导入，不能变更决定后重复提交");
        return outcome(j.resultId());
      }
      editable(j,revision);
      if(!scalar("SELECT baseline FROM import_jobs WHERE id=?",id).equals(currentBaseline(a,j.dataset())))throw new ConcurrentModificationException("预览期间正式数据已变化，请取消本任务并重新上传核对；本次未导入");
      List<BusinessRecord> rows=new ArrayList<>();Set<String> replace=new HashSet<>();List<String> decisions=new ArrayList<>();
      for(var item:items(id,0,20000)){
        Choice choice=mode.equals("saved")?item.choice():mode.equals("overwrite")?Choice.OVERWRITE:Choice.PRESERVE;
        decisions.add(item.number()+":"+choice);
        if(choice==Choice.SKIP)continue;
        if(item.similar()>0&&!allowSimilar)throw new IllegalArgumentException("本批有同客户同期但来源不同的候选，请核对并确认按新记录新增，或将其设为跳过");
        if(choice==Choice.OVERWRITE){if(!allowClear)throw new IllegalArgumentException("覆盖包括上传空白清空已有填写，请勾选覆盖确认");replace.add(PlatformStore.fingerprint(item.source().record()));}
        rows.add(item.source().record());
      }
      if(rows.isEmpty())throw new IllegalArgumentException("全部记录均跳过，请取消任务或至少选择一条导入");
      var result=store.applyImport(a,j.dataset(),rows,replace,"import-"+id);
      checkpoint.accept("import-official-written");
      // Store the exact decisions even when a legacy caller chose a bulk action.
      if(!mode.equals("saved"))exec("UPDATE import_job_rows SET choice=? WHERE job_id=?",mode.equals("overwrite")?"OVERWRITE":"PRESERVE",id);
      store.workflowAudit(a,Organizations.DIVISION,id,"IMPORT_CONFIRM","import-"+id,"","","导入任务 "+id+"；表种 "+j.dataset()+"；逐条决定 "+String.join(",",decisions));
      exec("UPDATE import_jobs SET state='COMMITTED',revision=revision+1,result_id=?,confirm_hash=? WHERE id=?",result.batchId(),hash,id);
      checkpoint.accept("import-confirm-written");return result;
    });
  }
  private Job job(ActorContext a,String id)throws SQLException {
    try(var st=statement("SELECT * FROM import_jobs WHERE id=?",id);var rs=st.executeQuery()){
      if(!rs.next()||!a.userId().equals(rs.getString("owner_id")))throw new SecurityException("导入任务不存在或无权访问");
      if(a.identityRevision()!=rs.getLong("identity_revision"))throw new SecurityException("任务创建后账号身份已变化，请重新上传");
      Job j=readJob(rs);if(j.state().equals("PREVIEW")&&!clock.instant().isBefore(Instant.parse(j.expiresAt())))return new Job(j.id(),j.dataset(),"EXPIRED",j.revision(),j.createdAt(),j.expiresAt(),j.count(),j.examples(),j.repeated(),j.resultId());return j;
    }
  }
  private void editable(Job j,long revision){if(!j.state().equals("PREVIEW"))throw new ConcurrentModificationException("导入任务已确认、取消或过期，请查看状态并重新上传");if(j.revision()!=revision)throw new ConcurrentModificationException("覆盖选择已在另一页面修改，请刷新核对；本次未导入");}
  private List<Item> items(String id,int offset,int limit)throws SQLException {
    List<Item> result=new ArrayList<>();try(var st=statement("SELECT * FROM import_job_rows WHERE job_id=? ORDER BY row_number LIMIT ? OFFSET ?",id,limit,offset);var rs=st.executeQuery()){
      while(rs.next()){String old=rs.getString("previous");result.add(new Item(rs.getInt("row_number"),new SourceRow(decode(rs.getString("incoming")),rs.getString("source_sheet"),rs.getInt("source_row")),old.isEmpty()?null:decode(old),rs.getInt("similar_count"),rs.getInt("pending_count"),Choice.valueOf(rs.getString("choice"))));}
    }return List.copyOf(result);
  }
  private static Job readJob(ResultSet rs)throws SQLException{return new Job(rs.getString("id"),rs.getString("dataset"),rs.getString("state"),rs.getLong("revision"),rs.getString("created_at"),rs.getString("expires_at"),rs.getInt("row_count"),rs.getInt("skipped_examples"),rs.getInt("repeated_rows"),Objects.toString(rs.getString("result_id"),""));}
  private PlatformStore.ImportOutcome outcome(String id)throws SQLException{try(var st=statement("SELECT * FROM import_batches WHERE id=?",id);var rs=st.executeQuery()){if(!rs.next())throw new IllegalStateException("导入结果缺失");return new PlatformStore.ImportOutcome(id,rs.getInt("added_count"),rs.getInt("duplicate_count"),rs.getInt("preserved_count"));}}
  private void expire()throws SQLException {String now=TIMESTAMP.format(clock.instant());exec("DELETE FROM import_job_rows WHERE job_id IN (SELECT id FROM import_jobs WHERE state='PREVIEW' AND expires_at<=?)",now);exec("UPDATE import_jobs SET state='EXPIRED' WHERE state='PREVIEW' AND expires_at<=?",now);}
  private static String location(SourceRow r){return r.record().filename()+" / "+r.sheet()+" / 第 "+r.row()+" 行";}
  private String currentBaseline(ActorContext a,String dataset)throws SQLException{return PlatformStore.baseline(store.list(a,"bundle".equals(dataset)?null:dataset,null,null));}
  private static String customerKey(BusinessRecord r){var s=DatasetSchema.get(r.dataset());String code=s.value(r.values(),s.codeColumn).strip();return Codec.encode(List.of(r.dataset(),r.organizationId(),r.period().key(),code.isEmpty()?"name:"+s.value(r.values(),s.customerColumn).strip():"code:"+code));}
  private static void validate(DatasetSchema s,BusinessRecord r){if(!s.id.equals(r.dataset())||r.values().size()!=s.width()||!Organizations.BRANCHES.containsKey(r.organizationId())||r.period()==null||r.filename().length()>200)throw new IllegalArgumentException("导入表种、机构或列数无效");for(int c=0;c<s.width();c++){if(r.values().get(c).length()>10000)throw new IllegalArgumentException("单元格不能超过 10000 字");if(s.editable(c))s.validateEdit(c,r.values().get(c));}}
  private static String encode(BusinessRecord r){return Codec.encode(List.of(r.id(),Long.toString(r.version()),r.dataset(),r.period().key(),r.period().start().toString(),r.period().end().toString(),r.organizationId(),Codec.encode(r.values()),r.filename(),r.importedAt(),r.updatedAt()));}
  private static BusinessRecord decode(String raw){var v=Codec.decode(raw);return new BusinessRecord(v.get(0),Long.parseLong(v.get(1)),v.get(2),new Period(v.get(3),LocalDate.parse(v.get(4)),LocalDate.parse(v.get(5))),v.get(6),Codec.decode(v.get(7)),v.get(8),v.get(9),v.get(10),Map.of());}
  private static void page(int offset,int limit){if(offset<0||offset>20000||limit<1||limit>30)throw new IllegalArgumentException("导入分页范围无效");}
  private <T>T call(ActorContext a,PlatformStore.WorkflowWork<T> work){return store.workflowTransaction(a,()->{AccessPolicy.require(a,AccessPolicy.Action.UPLOAD,Organizations.DIVISION);return work.run();});}
  private PreparedStatement statement(String sql,Object...args)throws SQLException{var st=db.prepareStatement(sql);for(int i=0;i<args.length;i++)st.setObject(i+1,args[i]);return st;}
  private void exec(String sql,Object...args)throws SQLException{try(var st=statement(sql,args)){st.executeUpdate();}}
  private int count(String sql,Object...args)throws SQLException{try(var st=statement(sql,args);var rs=st.executeQuery()){rs.next();return rs.getInt(1);}}
  private String scalar(String sql,Object...args)throws SQLException{try(var st=statement(sql,args);var rs=st.executeQuery()){return rs.next()?rs.getString(1):null;}}
}

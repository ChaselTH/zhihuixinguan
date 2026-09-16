package xinguan.platform;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Single-process embedded store. All official mutations and their audit rows commit together. */
public final class PlatformStore implements RecordRepository, OfficialDataWriter, AutoCloseable {
  public record ImportOutcome(String batchId,int added,int duplicates,int preserved) {}
  public record LegacyItem(String key,BusinessRecord record,String sourceHash) {}
  public record AuditEvent(String at,String actor,String organization,String action,String recordId,String requestId,String before,String after) {}
  private final Connection db;
  private final WorkflowEngine workflow;
  private final AccessPlatform access;
  private final ImportPlatform importing;
  private final java.util.function.Consumer<String> checkpoint;
  public record CreatedUser(UserAccount user,String initialPassword){}
  private static final Passwords.Encoded DUMMY=Passwords.encode(UUID.randomUUID().toString());
  public synchronized boolean hasUsers(){try(Statement st=db.createStatement();ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM users")){rs.next();return rs.getInt(1)>0;}catch(SQLException e){throw failure(e);}}
  public synchronized void prepareSuperAdminPolicy(){transaction(()->{exec("UPDATE users SET must_change_password=FALSE,revision=revision+1 WHERE role=? AND must_change_password=TRUE",Role.SUPER_ADMIN.name());return null;});}
  public synchronized void bootstrapSuperAdmin(String number,String password){
    transaction(()->{
      // Upgrade existing accounts without resetting credentials; invalidate only affected sessions.
      exec("UPDATE users SET must_change_password=FALSE,revision=revision+1 WHERE role=? AND must_change_password=TRUE",Role.SUPER_ADMIN.name());
      try(Statement st=db.createStatement();ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM users")){rs.next();if(rs.getInt(1)>0)return null;}
      validateUser(number,"超级管理员",Role.SUPER_ADMIN,Organizations.DIVISION);Passwords.validate(password);insertUser("user-"+UUID.randomUUID(),number,"超级管理员",Role.SUPER_ADMIN,Organizations.DIVISION,Passwords.encode(password));return null;});
  }
  public synchronized UserAccount authenticateUser(String number,String password){
    try(PreparedStatement st=statement("SELECT * FROM users WHERE auth_number=?",number==null?"":number.strip());ResultSet rs=st.executeQuery()){
      if(!rs.next()){Passwords.matches(password,DUMMY.salt(),DUMMY.hash());return null;}
      boolean matched=Passwords.matches(password,rs.getString("password_salt"),rs.getString("password_hash"));return matched&&rs.getBoolean("active")?readUser(rs):null;
    }catch(SQLException e){throw failure(e);}
  }
  public synchronized UserAccount sessionUser(String id){try{return loadUser(id);}catch(SQLException e){throw failure(e);}}
  private UserAccount loadUser(String id)throws SQLException{try(PreparedStatement st=statement("SELECT * FROM users WHERE id=?",id);ResultSet rs=st.executeQuery()){return rs.next()?readUser(rs):null;}}
  private UserAccount readUser(ResultSet rs)throws SQLException{return new UserAccount(rs.getString("id"),rs.getString("auth_number"),rs.getString("display_name"),Role.valueOf(rs.getString("role")),rs.getString("organization_id"),rs.getBoolean("active"),rs.getBoolean("must_change_password"),rs.getLong("revision"));}
  private void currentIdentity(ActorContext a){
    if(a==null)throw new SecurityException("请先登录");
    if(a.identityRevision()<0)return; // Trusted service-test identity; never accepted from HTTP fields.
    UserAccount u=sessionUser(a.userId());if(u==null||!u.active()||u.revision()!=a.identityRevision()||u.role()!=a.role()||!u.organizationId().equals(a.organizationId()))throw new SecurityException("账号权限已变化，请重新登录");
    if(u.mustChangePassword())throw new SecurityException("请先修改初始密码");
  }
  public synchronized List<UserAccount> listUsers(ActorContext a){currentIdentity(a);if(!isManager(a))throw new SecurityException("没有人员管理权限");
    try(Statement st=db.createStatement();ResultSet rs=st.executeQuery("SELECT * FROM users ORDER BY organization_id,auth_number")){List<UserAccount> list=new ArrayList<>();while(rs.next()){UserAccount u=readUser(rs);if(a.role()==Role.SUPER_ADMIN||AccessPolicy.canManage(a,u.role(),u.organizationId()))list.add(u);}return list;}catch(SQLException e){throw failure(e);}}
  public static boolean isManager(ActorContext a){return a!=null&&(a.role()==Role.SUPER_ADMIN||a.role()==Role.DIVISION_ADMIN||a.role()==Role.BRANCH_ADMIN);}
  public synchronized UserAccount managedUser(ActorContext a,String id){currentIdentity(a);try{UserAccount u=loadUser(id);requireManage(a,u);return u;}catch(SQLException e){throw failure(e);}}
  private void requireManage(ActorContext a,UserAccount u){if(u==null||a.userId().equals(u.id())||!AccessPolicy.canManage(a,u.role(),u.organizationId()))throw new SecurityException("不能管理该人员或同级、上级账号");}
  public synchronized CreatedUser createUser(ActorContext a,String number,String name,Role role,String org){return transaction(()->createUserInTransaction(a,number,name,role,org));}
  CreatedUser createUserInTransaction(ActorContext a,String number,String name,Role role,String org)throws SQLException {
    currentIdentity(a);validateUser(number,name,role,org);if(!AccessPolicy.canManage(a,role,org))throw new SecurityException("不能创建该角色或其他支行的账号");
    try(PreparedStatement st=statement("SELECT id FROM users WHERE auth_number=?",number.strip());ResultSet rs=st.executeQuery()){if(rs.next())throw new IllegalArgumentException("统一认证号已存在（包括已停用账号），请修改或恢复原账号");}
    String id="user-"+UUID.randomUUID(),password=Passwords.temporary();insertUser(id,number.strip(),name.strip(),role,org,Passwords.encode(password));UserAccount u=loadUser(id);audit(a,org,id,"USER_CREATE",UUID.randomUUID().toString(),"",userSummary(u),"初始密码仅显示一次，不记入日志");return new CreatedUser(u,password);
  }
  public synchronized void updateUser(ActorContext a,String id,long revision,String name,Role role,String org,boolean active){transaction(()->{
    currentIdentity(a);UserAccount old=loadUser(id);requireManage(a,old);validateUser(old.authNumber(),name,role,org);if(!AccessPolicy.canManage(a,role,org))throw new SecurityException("不能转移到该机构或提升到该角色");if(old.revision()!=revision)throw new ConcurrentModificationException("人员信息已变化，请刷新后再操作");
    exec("UPDATE users SET display_name=?,role=?,organization_id=?,active=?,revision=revision+1 WHERE id=?",name.strip(),role.name(),org,active,id);audit(a,old.organizationId(),id,active?"USER_UPDATE":"USER_DISABLE",UUID.randomUUID().toString(),userSummary(old),userSummary(loadUser(id)),"原会话失效；删除按停用处理，保留历史");return null;
  });}
  public synchronized CreatedUser resetUserPassword(ActorContext a,String id,long revision){return transaction(()->{currentIdentity(a);UserAccount old=loadUser(id);requireManage(a,old);if(old.revision()!=revision)throw new ConcurrentModificationException("人员信息已变化，请刷新后重试");String password=Passwords.temporary();Passwords.Encoded hash=Passwords.encode(password);exec("UPDATE users SET password_hash=?,password_salt=?,must_change_password=TRUE,revision=revision+1 WHERE id=?",hash.hash(),hash.salt(),id);audit(a,old.organizationId(),id,"USER_PASSWORD_RESET",UUID.randomUUID().toString(),"","","要求下次登录改密；不记录密码");return new CreatedUser(loadUser(id),password);});}
  /** The HTTP caller must verify CSRF and a recent login through AuthService first. */
  public synchronized void changeOwnPassword(ActorContext a,String next){transaction(()->{
    if(a==null)throw new SecurityException("请先登录");UserAccount old=loadUser(a.userId());if(old==null||!old.active()||old.revision()!=a.identityRevision()||old.role()!=a.role()||!old.organizationId().equals(a.organizationId()))throw new SecurityException("账号状态已变化，请重新登录");Passwords.validate(next);if(authenticateUser(old.authNumber(),next)!=null)throw new IllegalArgumentException("新密码不能与当前密码相同");Passwords.Encoded encoded=Passwords.encode(next);exec("UPDATE users SET password_hash=?,password_salt=?,must_change_password=FALSE,revision=revision+1 WHERE id=?",encoded.hash(),encoded.salt(),old.id());audit(a,old.organizationId(),old.id(),"PASSWORD_CHANGE",UUID.randomUUID().toString(),"","","本人修改密码，会话失效");return null;
  });}
  private void insertUser(String id,String number,String name,Role role,String org,Passwords.Encoded hash)throws SQLException{exec("INSERT INTO users(id,auth_number,display_name,role,organization_id,active,password_hash,password_salt,must_change_password,revision) VALUES(?,?,?,?,?,TRUE,?,?,?,1)",id,number,name,role.name(),org,hash.hash(),hash.salt(),role!=Role.SUPER_ADMIN);}
  private static void validateUser(String number,String name,Role role,String org){if(number==null||!number.strip().matches("[0-9]{6,20}"))throw new IllegalArgumentException("统一认证号应为 6～20 位数字，保留前导零");if(name==null||name.strip().isEmpty()||name.strip().length()>100)throw new IllegalArgumentException("姓名需要 1～100 个字符");new ActorContext("validation",name,role,org);}
  private static String userSummary(UserAccount u){return Codec.encode(List.of(u.authNumber(),u.name(),u.role().name(),u.organizationId(),Boolean.toString(u.active())));}
  public PlatformStore(Path dataRoot) throws Exception { this(dataRoot,Clock.systemUTC(),point->{}); }
  /** Package-private clock/fault seam for isolated tests; never configured through HTTP or environment. */
  PlatformStore(Path dataRoot,Clock clock,java.util.function.Consumer<String> checkpoint) throws Exception {
    this.checkpoint=Objects.requireNonNull(checkpoint);
    Path dir=dataRoot.toAbsolutePath().normalize().resolve("platform");
    Files.createDirectories(dir);
    if(Files.isSymbolicLink(dir))throw new IOException("数据目录不能是符号链接");
    String path=dir.resolve("records").toString().replace('\\','/');
    if(path.contains(";"))throw new IOException("数据目录不能包含分号");
    Class.forName("org.h2.Driver");
    db=DriverManager.getConnection("jdbc:h2:file:"+path+";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000","sa","");
    try {initialize();}catch(Exception e){db.close();throw e;}
    workflow=new WorkflowEngine(this,db,clock,checkpoint);
    access=new AccessPlatform(this,db,workflow,clock,checkpoint);
    importing=new ImportPlatform(this,db,clock,checkpoint);
  }
  public ImportPlatform importing() { return importing; }
  public AccessPlatform access() { return access; }
  public WorkflowContracts.WorkflowService workflow() { return workflow; }
  public NotificationService notifications() { return workflow; }
  public int schemaVersion() { return SchemaMigrations.CURRENT_VERSION; }
  private void initialize() throws Exception {
    SchemaMigrations.apply(db,checkpoint);
    exec("MERGE INTO organizations KEY(id) VALUES(?,?,NULL)",Organizations.DIVISION,"分行");
    exec("MERGE INTO organizations KEY(id) VALUES(?,?,?)",Organizations.UNASSIGNED,"待确认机构",Organizations.DIVISION);
    for(var e:Organizations.BRANCHES.entrySet())exec("MERGE INTO organizations KEY(id) VALUES(?,?,?)",e.getKey(),e.getValue(),Organizations.DIVISION);
  }
  @Override public synchronized List<BusinessRecord> list(ActorContext actor,String dataset,LocalDate from,LocalDate through) {
    currentIdentity(actor);
    if(actor==null)throw new SecurityException("请先登录");
    String sql="SELECT * FROM official_records WHERE 1=1";List<Object> args=new ArrayList<>();
    if(!AccessPolicy.all(actor)){sql+=" AND organization_id=?";args.add(actor.organizationId());}
    if(dataset!=null&&!dataset.isBlank()){DatasetSchema.get(dataset);sql+=" AND dataset=?";args.add(dataset);}
    if(from!=null){sql+=" AND period_end>=?";args.add(java.sql.Date.valueOf(from));}
    if(through!=null){sql+=" AND period_start<=?";args.add(java.sql.Date.valueOf(through));}
    sql+=" ORDER BY period_end DESC,dataset,imported_at,id";
    try(PreparedStatement st=statement(sql,args.toArray());ResultSet rs=st.executeQuery()) {
      List<BusinessRecord> rows=new ArrayList<>();while(rs.next())rows.add(read(rs));return rows;
    }catch(SQLException e){throw failure(e);}
  }
  @Override public synchronized BusinessRecord find(ActorContext actor,String id) {
    currentIdentity(actor);
    if(actor==null)throw new SecurityException("请先登录");
    try {BusinessRecord r=load(id,false);if(r==null)throw new IllegalArgumentException("记录不存在或无权访问");AccessPolicy.require(actor,AccessPolicy.Action.VIEW,r.organizationId());return r;}catch(SQLException e){throw failure(e);}
  }
  @Override public synchronized String publishDirect(ActorContext actor,List<RecordChange> changes,String requestId) {
    if(actor==null)throw new SecurityException("请先登录");
    if(changes.isEmpty()||changes.size()>200)throw new IllegalArgumentException("每次保存 1～200 条记录");
    List<String> canonical=new ArrayList<>();
    for(RecordChange c:changes){canonical.add(c.recordId());canonical.add(Long.toString(c.expectedVersion()));canonical.add(Integer.toString(c.values().size()));for(var e:new TreeMap<>(c.values()).entrySet()){canonical.add(e.getKey());canonical.add(e.getValue());}}
    String payload=Codec.hash(Codec.encode(canonical));
    return transaction(()->{
      currentIdentity(actor);
      String result=repeat(actor,requestId,payload);if(result!=null)return result;
      String eventId=UUID.randomUUID().toString();
      applyOfficialChanges(actor,changes,AccessPolicy.Action.DIRECT_EDIT,"DIRECT_EDIT",requestId,"");
      remember(actor,requestId,payload,eventId);return eventId;
    });
  }
  /** Same connection and transaction as the caller; not a public workflow bypass. */
  List<String> applyOfficialChanges(ActorContext actor,List<RecordChange> changes,AccessPolicy.Action capability,String action,String requestId,String details)throws SQLException {
      Set<String> seen=new HashSet<>();List<String> auditIds=new ArrayList<>();
      for(RecordChange change:changes) {
        if(!seen.add(change.recordId()))throw new IllegalArgumentException("本次提交包含重复记录");
        BusinessRecord old=load(change.recordId(),true);
        if(old==null)throw new IllegalArgumentException("记录不存在");
        AccessPolicy.require(actor,capability,old.organizationId());
        if(old.version()!=change.expectedVersion())throw new ConcurrentModificationException("记录已被其他保存或导入更新，请刷新后核对；本次全部未保存");
        DatasetSchema schema=DatasetSchema.get(old.dataset());List<String> values=new ArrayList<>(old.values());
        for(var e:change.values().entrySet()){int index=schema.index(e.getKey());if(!schema.editable(index))throw new IllegalArgumentException("不允许修改来源字段");String value=e.getValue()==null?"":e.getValue().strip();if(value.equals(old.values().get(index)))continue;schema.validateEdit(index,e.getValue());values.set(index,value);}
        if(values.equals(old.values()))continue;
        String now=Instant.now().toString();
        exec("UPDATE official_records SET revision=revision+1,cell_data=?,updated_at=? WHERE id=?",Codec.encode(values),now,old.id());
        auditIds.add(audit(actor,old.organizationId(),old.id(),action,requestId,Codec.encode(old.values()),Codec.encode(values),details));
        checkpoint.accept("official-row-written");
      }
      return List.copyOf(auditIds);
  }
  public synchronized ImportOutcome importRows(ActorContext actor,String dataset,List<BusinessRecord> incoming,boolean overwrite,String requestId) {
    return importRows(actor,dataset,incoming,overwrite,requestId,null);
  }
  /** Preview baseline is checked under the same lock and transaction as the actual write. */
  public synchronized ImportOutcome importRows(ActorContext actor,String dataset,List<BusinessRecord> incoming,boolean overwrite,String requestId,String expectedBaseline) {
    AccessPolicy.require(actor,AccessPolicy.Action.UPLOAD,Organizations.DIVISION);DatasetSchema.get(dataset);
    String payload=Codec.hash(dataset+"|"+overwrite+"|"+incoming.toString());
    return transaction(()->{
      currentIdentity(actor);
      String prior=repeat(actor,requestId,payload);
      if(prior!=null){try(PreparedStatement st=statement("SELECT * FROM import_batches WHERE id=?",prior);ResultSet rs=st.executeQuery()){if(!rs.next())throw new SQLException("导入幂等记录缺失");return new ImportOutcome(prior,rs.getInt("added_count"),rs.getInt("duplicate_count"),rs.getInt("preserved_count"));}}
      if(expectedBaseline!=null&&!expectedBaseline.equals(baseline(list(actor,dataset,null,null))))throw new ConcurrentModificationException("预览期间正式数据已变化，请重新上传核对；本次未导入");
      Set<String> replace=new HashSet<>();if(overwrite)for(var row:incoming)replace.add(fingerprint(row));
      ImportOutcome outcome=applyImport(actor,dataset,incoming,replace,requestId);
      remember(actor,requestId,payload,outcome.batchId());return outcome;
    });
  }
  /** Caller owns the transaction and has checked identity, preview and decisions. */
  ImportOutcome applyImport(ActorContext actor,String dataset,List<BusinessRecord> incoming,Set<String> replace,String requestId)throws SQLException {
      AccessPolicy.require(actor,AccessPolicy.Action.UPLOAD,Organizations.DIVISION);
      int added=0,duplicates=0,preserved=0;
      for(BusinessRecord candidate:incoming) {
        DatasetSchema schema=DatasetSchema.get(candidate.dataset());
        if(!dataset.equals(candidate.dataset())||candidate.values().size()!=schema.width()||!Organizations.BRANCHES.containsKey(candidate.organizationId()))throw new IllegalArgumentException("导入数据类型、机构或列数不正确");
        for(int i=0;i<schema.width();i++)if(schema.editable(i))schema.validateEdit(i,candidate.values().get(i));
        String fingerprint=fingerprint(candidate);boolean overwrite=replace.contains(fingerprint);BusinessRecord old=null;
        try(PreparedStatement st=statement("SELECT * FROM official_records WHERE source_fingerprint=? FOR UPDATE",fingerprint);ResultSet rs=st.executeQuery()){
          if(rs.next())old=read(rs);if(rs.next())throw new IllegalArgumentException("存在多条相同历史来源记录，请先核对，未自动覆盖");
        }
        if(old==null) {
          String id=UUID.randomUUID().toString();insert(candidate,id);added++;
          audit(actor,candidate.organizationId(),id,"IMPORT_ADD",requestId,"",Codec.encode(candidate.values()),candidate.filename());
        } else {
          duplicates++;List<String> values=new ArrayList<>(candidate.values());boolean kept=false;
          for(int i=0;i<schema.width();i++)if(schema.editable(i)&&!overwrite&&!old.values().get(i).isBlank()) {
            if(!old.values().get(i).equals(values.get(i)))kept=true;values.set(i,old.values().get(i));
          }
          if(kept)preserved++;
          if(!values.equals(old.values())) {
            exec("UPDATE official_records SET revision=revision+1,cell_data=?,updated_at=?,filename=? WHERE id=?",Codec.encode(values),Instant.now().toString(),candidate.filename(),old.id());
            audit(actor,old.organizationId(),old.id(),"IMPORT_UPDATE",requestId,Codec.encode(old.values()),Codec.encode(values),overwrite?"已确认覆盖填报内容（包括空白）":"保留已有填报内容");
          }
        }
        checkpoint.accept("import-row-written");
      }
      String batch=UUID.randomUUID().toString();
      exec("INSERT INTO import_batches VALUES(?,?,?,?,?,?,?)",batch,actor.userId(),dataset,Instant.now().toString(),added,duplicates,preserved);
      checkpoint.accept("import-batch-written");return new ImportOutcome(batch,added,duplicates,preserved);
  }
  public synchronized int migrateLegacy(List<LegacyItem> items) {
    return transaction(()->{
      int imported=0;
      for(LegacyItem item:items) {
        try(PreparedStatement st=statement("SELECT source_hash FROM migration_items WHERE legacy_key=?",item.key());ResultSet rs=st.executeQuery()) {
          if(rs.next()){if(!rs.getString(1).equals(item.sourceHash()))throw new IllegalStateException("旧数据在迁移后发生变化，请先核对备份："+item.key());continue;}
        }
        String id="legacy-"+Codec.hash(item.key()).substring(0,40);insert(item.record(),id);
        exec("INSERT INTO migration_items VALUES(?,?,?)",item.key(),id,item.sourceHash());
        exec("INSERT INTO audit_events VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),Instant.now().toString(),"legacy-migration","历史迁入（原操作人未知）","MIGRATION",item.record().organizationId(),id,"LEGACY_MIGRATION","migration-v1","",Codec.encode(item.record().values()),Codec.encode(new ArrayList<>(item.record().legacyExtras().values())));
        imported++;
      }
      return imported;
    });
  }
  public synchronized List<AuditEvent> auditEvents(ActorContext actor,int limit) {
    currentIdentity(actor);
    if(actor==null)throw new SecurityException("请先登录");
    String sql="SELECT * FROM audit_events";List<Object> args=new ArrayList<>();
    if(!AccessPolicy.all(actor)){sql+=" WHERE organization_id=?";args.add(actor.organizationId());}
    sql+=" ORDER BY event_at DESC LIMIT ?";args.add(Math.max(1,Math.min(limit,200)));
    try(PreparedStatement st=statement(sql,args.toArray());ResultSet rs=st.executeQuery()){
      List<AuditEvent> events=new ArrayList<>();while(rs.next())events.add(new AuditEvent(rs.getString("event_at"),rs.getString("actor_name"),rs.getString("organization_id"),rs.getString("action"),rs.getString("record_id"),rs.getString("request_id"),rs.getString("before_data"),rs.getString("after_data")));return events;
    }catch(SQLException e){throw failure(e);}
  }
  public synchronized Map<String,Integer> diagnostics() {
    LinkedHashMap<String,Integer> result=new LinkedHashMap<>();
    for(String table:List.of("official_records","migration_items","audit_events","users","drafts","submissions"))try(Statement st=db.createStatement();ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM "+table)){rs.next();result.put(table,rs.getInt(1));}catch(SQLException e){throw failure(e);}
    try(PreparedStatement st=statement("SELECT COUNT(*) FROM official_records WHERE organization_id=?",Organizations.UNASSIGNED);ResultSet rs=st.executeQuery()){rs.next();result.put("unassigned_records",rs.getInt(1));}catch(SQLException e){throw failure(e);}
    return result;
  }
  public static String fingerprint(BusinessRecord r) {
    DatasetSchema s=DatasetSchema.get(r.dataset());List<String> a=new ArrayList<>();a.add(r.dataset());a.add(r.period().key());a.add(r.organizationId());
    for(int i=0;i<s.width();i++)if(!s.editable(i))a.add(s.value(r.values(),i).strip());return Codec.hash(Codec.encode(a));
  }
  public static String baseline(List<BusinessRecord> rows){List<String> ids=new ArrayList<>();for(var r:rows)ids.add(r.id()+":"+r.version());Collections.sort(ids);return Codec.hash(String.join("|",ids));}
  private void insert(BusinessRecord r,String id)throws SQLException {
    List<String> extras=new ArrayList<>();for(var e:new TreeMap<>(r.legacyExtras()).entrySet()){extras.add(e.getKey());extras.add(e.getValue());}
    exec("INSERT INTO official_records VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",id,1L,r.dataset(),r.period().key(),java.sql.Date.valueOf(r.period().start()),java.sql.Date.valueOf(r.period().end()),r.organizationId(),fingerprint(r),Codec.encode(r.values()),r.filename(),r.importedAt(),r.updatedAt(),Codec.encode(extras));
  }
  private BusinessRecord load(String id,boolean lock)throws SQLException{
    try(PreparedStatement st=statement("SELECT * FROM official_records WHERE id=?"+(lock?" FOR UPDATE":""),id);ResultSet rs=st.executeQuery()){return rs.next()?read(rs):null;}
  }
  private BusinessRecord read(ResultSet rs)throws SQLException {
    Map<String,String> extras=new LinkedHashMap<>();String raw=rs.getString("legacy_extras");if(!raw.isEmpty()){List<String> a=Codec.decode(raw);for(int i=0;i+1<a.size();i+=2)extras.put(a.get(i),a.get(i+1));}
    return new BusinessRecord(rs.getString("id"),rs.getLong("revision"),rs.getString("dataset"),new Period(rs.getString("period_key"),rs.getDate("period_start").toLocalDate(),rs.getDate("period_end").toLocalDate()),rs.getString("organization_id"),Codec.decode(rs.getString("cell_data")),rs.getString("filename"),rs.getString("imported_at"),rs.getString("updated_at"),extras);
  }
  private String audit(ActorContext a,String org,String record,String action,String request,String before,String after,String details)throws SQLException {
    String id=UUID.randomUUID().toString();
    exec("INSERT INTO audit_events VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,Instant.now().toString(),a.userId(),a.name(),a.role().name(),org,record,action,request,before,after,details);return id;
  }
  private String repeat(ActorContext a,String request,String hash)throws SQLException {
    if(request==null||!request.matches("[A-Za-z0-9_-]{10,100}"))throw new IllegalArgumentException("请求编号无效，请刷新后重试");
    try(PreparedStatement st=statement("SELECT * FROM processed_requests WHERE id=?",request);ResultSet rs=st.executeQuery()){
      if(!rs.next())return null;
      if(!a.userId().equals(rs.getString("actor_id"))||!hash.equals(rs.getString("payload_hash")))throw new IllegalArgumentException("重复请求内容发生变化，请刷新页面");
      return rs.getString("result_id");
    }
  }
  private void remember(ActorContext a,String request,String hash,String result)throws SQLException{exec("INSERT INTO processed_requests VALUES(?,?,?,?)",request,a.userId(),hash,result);}
  private PreparedStatement statement(String sql,Object...args)throws SQLException {PreparedStatement st=db.prepareStatement(sql);for(int i=0;i<args.length;i++)st.setObject(i+1,args[i]);return st;}
  private void exec(String sql,Object...args)throws SQLException{try(PreparedStatement st=statement(sql,args)){st.executeUpdate();}}
  @FunctionalInterface interface WorkflowWork<T>{T run()throws Exception;}
  <T>T anonymousTransaction(WorkflowWork<T> work) { synchronized(this) { return transaction(work::run); } }
  <T>T workflowTransaction(ActorContext actor,WorkflowWork<T> work) {
    synchronized(this) {
      return transaction(()->{currentIdentity(actor);return work.run();});
    }
  }
  String workflowAudit(ActorContext actor,String org,String record,String action,String request,String before,String after,String details)throws SQLException {
    return audit(actor,org,record,action,request,before,after,details);
  }
  @FunctionalInterface private interface Work<T>{T run()throws Exception;}
  private <T>T transaction(Work<T> work){try{db.setAutoCommit(false);try{T result=work.run();db.commit();return result;}catch(Exception e){db.rollback();if(e instanceof RuntimeException r)throw r;throw new IllegalStateException(e);}finally{db.setAutoCommit(true);}}catch(SQLException e){throw failure(e);}}
  private static IllegalStateException failure(SQLException e){return new IllegalStateException("数据库处理失败，未确认的更改不会生效",e);}
  @Override public synchronized void close()throws SQLException{db.close();}
}

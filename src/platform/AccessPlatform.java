package xinguan.platform;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** A1 services. Shares the store monitor and transaction with accounts, notices and audit. */
public final class AccessPlatform {
  public static final String SAFETY_VERSION="safety-placeholder-v1";
  public record Application(String id,String number,String name,String organization,String state,
      long revision,String route,String createdAt,String decidedAt,String reason,String userId,
      Role requestedRole) {
    public Application(String id,String number,String name,String organization,String state,
        long revision,String route,String createdAt,String decidedAt,String reason,String userId) {
      this(id,number,name,organization,state,revision,route,createdAt,decidedAt,reason,userId,null);
    }
  }
  public record Decision(Application application,PlatformStore.CreatedUser created,boolean replayed) {}
  public record AuditFilter(String category,String organization,String dataset,String search,LocalDate from,LocalDate through) {}
  public record AuditEntry(String id,String at,String actor,String organization,String action,String recordId,
      String requestId,String dataset,String customer,String submissionId,List<String> before,List<String> after,String details) {}
  private final PlatformStore store;
  private final Connection db;
  private final WorkflowEngine notices;
  private final Clock clock;
  private final Consumer<String> checkpoint;
  AccessPlatform(PlatformStore store,Connection db,WorkflowEngine notices,Clock clock,Consumer<String> checkpoint) {
    this.store=store;this.db=db;this.notices=notices;this.clock=clock;this.checkpoint=checkpoint;
  }
  /** New applications must always state their target role; legacy rows are read/approved separately. */
  public void apply(String number,String name,String organization) {
    throw new IllegalArgumentException("必须明确申请角色，不能使用旧版无角色申请入口");
  }
  public void apply(String number,String name,String organization,Role requestedRole) {
    String n=text(number,20),display=text(name,100);
    if(!n.matches("[0-9]{6,20}")||display.isEmpty())throw new IllegalArgumentException("请填写 6～20 位统一认证号和姓名");
    organization(organization);
    if(requestedRole==null)throw new IllegalArgumentException("必须明确申请角色，不能使用旧版无角色申请入口");
    validateApplicationRole(organization,requestedRole);
    store.anonymousTransaction(()->{
      if(scalar("SELECT id FROM users WHERE auth_number=?",n)!=null||scalar("SELECT request_id FROM access_pending_numbers WHERE auth_number=?",n)!=null)throw new IllegalArgumentException("该统一认证号已有账号或待审批申请，请联系管理员");
      if(Integer.parseInt(scalar("SELECT COUNT(*) FROM access_pending_numbers"))>=10000)throw new IllegalArgumentException("申请队列已满，请联系管理员");
      String route=organization.equals(Organizations.DIVISION)?"SUPER":requestedRole==Role.BRANCH_ADMIN?"DIVISION":requestedRole==Role.OPERATOR||requestedRole==Role.REVIEWER?"BRANCH":hasBranchManager(organization)?"BRANCH":"DIVISION";
      if(!hasRequiredManager(requestedRole,organization))throw new IllegalStateException(requiredManagerMessage(requestedRole));
      String id=UUID.randomUUID().toString();
      exec("INSERT INTO access_requests(id,auth_number,display_name,organization_id,state,created_at,revision,route_level,requested_role) VALUES(?,?,?,?,'PENDING',?,1,?,?)",id,n,display,organization,now(),route,requestedRole==null?null:requestedRole.name());
      exec("INSERT INTO access_pending_numbers VALUES(?,?)",n,id);
      notifyManagers(load(id),"有新的权限申请","请核验申请人身份并审批；角色由管理员指定");
      checkpoint.accept("access-application-written");return null;
    });
  }
  public List<Application> applications(ActorContext a,boolean pendingOnly,int offset,int limit) {
    return call(a,()->{
      manager(a);page(offset,limit);String sql="SELECT * FROM access_requests WHERE 1=1";List<Object> args=new ArrayList<>();
      if(a.role()==Role.DIVISION_ADMIN){sql+=" AND organization_id<>?";args.add(Organizations.DIVISION);}
      if(a.role()==Role.BRANCH_ADMIN){sql+=" AND organization_id=?";args.add(a.organizationId());}
      if(pendingOnly)sql+=" AND state IN ('PENDING','ESCALATED')";
      sql+=" ORDER BY created_at DESC,id LIMIT ? OFFSET ?";args.add(limit);args.add(offset);
      List<Application> result=new ArrayList<>();try(PreparedStatement st=statement(sql,args.toArray());ResultSet rs=st.executeQuery()){while(rs.next())result.add(read(rs));}return List.copyOf(result);
    });
  }
  public Application application(ActorContext a,String id) {return call(a,()->{Application r=load(id);visible(a,r);return r;});}
  public boolean canDecide(ActorContext a,Application r) {
    return PlatformStore.isManager(a)&&Set.of("PENDING","ESCALATED").contains(r.state())&&
      (a.role()==Role.SUPER_ADMIN||a.role()==Role.DIVISION_ADMIN&&!r.organization().equals(Organizations.DIVISION)||
       a.role()==Role.BRANCH_ADMIN&&a.organizationId().equals(r.organization())&&r.route().equals("BRANCH"));
  }
  public Decision decide(ActorContext a,String id,long revision,String action,Role role,String reason,String requestId) {
    return call(a,()->{
      Application r=load(id);visible(a,r);String why=text(reason,1000);
      if(action==null||!Set.of("APPROVE","REJECT","ESCALATE").contains(action))throw new IllegalArgumentException("审批操作无效");
      String hash=Codec.hash(Codec.encode(Arrays.asList(id,""+revision,action,role==null?"":role.name(),why)));
      if(requestId==null||!requestId.matches("[A-Za-z0-9_-]{10,100}"))throw new IllegalArgumentException("请求编号无效");
      try(PreparedStatement st=statement("SELECT * FROM processed_requests WHERE id=?",requestId);ResultSet rs=st.executeQuery()) {
        if(rs.next()) {if(!a.userId().equals(rs.getString("actor_id"))||!hash.equals(rs.getString("payload_hash")))throw new IllegalArgumentException("重复请求的内容不同");return new Decision(r,null,true);}
      }
      if(!canDecide(a,r))throw new SecurityException("没有审批权限或该申请已处理");
      if(r.revision()!=revision)throw new ConcurrentModificationException("申请已被其他管理员更新，请刷新");
      PlatformStore.CreatedUser created=null;String state,route=r.route();
      if(action.equals("APPROVE")) {
        if(r.requestedRole()!=null&&role!=r.requestedRole())throw new SecurityException("申请目标角色已固定，请按申请角色审批");
        if(role==null||!AccessPolicy.canManage(a,role,r.organization()))throw new SecurityException("不能分配该角色");
        created=store.createUserInTransaction(a,r.number(),r.name(),role,r.organization(),r.id());state="APPROVED";
        checkpoint.accept("access-account-written");
      } else {
        if(why.isEmpty())throw new IllegalArgumentException("请填写退回或转交原因");
        if(action.equals("ESCALATE")) {
          if(a.role()!=Role.BRANCH_ADMIN||!r.route().equals("BRANCH"))throw new SecurityException("只有本支行管理员可转交分行审批");
          state="ESCALATED";route="DIVISION";
        } else state="REJECTED";
      }
      exec("UPDATE access_requests SET state=?,route_level=?,revision=revision+1,decided_by=?,decided_at=?,decision_reason=?,created_user_id=? WHERE id=?",state,route,a.userId(),now(),why,created==null?null:created.user().id(),id);
      if(!state.equals("ESCALATED"))exec("DELETE FROM access_pending_numbers WHERE request_id=?",id);
      store.workflowAudit(a,r.organization(),id,"ACCESS_"+action,requestId,"","",state+"；"+why);
      notifyManagers(load(id),state.equals("ESCALATED")?"权限申请已转交分行":"权限申请已处理",a.name()+"："+state);
      exec("INSERT INTO processed_requests VALUES(?,?,?,?)",requestId,a.userId(),hash,id);
      checkpoint.accept("access-decision-written");return new Decision(load(id),created,false);
    });
  }
  public void acknowledge(ActorContext a,String sessionHash,String version) {
    call(a,()->{
      if(sessionHash==null||!sessionHash.matches("[a-f0-9]{64}")||!SAFETY_VERSION.equals(version))throw new IllegalArgumentException("安全提示版本已变化，请刷新确认");
      if(scalar("SELECT id FROM security_acknowledgements WHERE user_id=? AND session_id=? AND notice_version=?",a.userId(),sessionHash,version)==null) {
        String id=UUID.randomUUID().toString();exec("INSERT INTO security_acknowledgements VALUES(?,?,?,?,?)",id,a.userId(),sessionHash,version,now());
        store.workflowAudit(a,a.organizationId(),id,"SECURITY_ACK",id,"","",version);
        checkpoint.accept("access-safety-written");
      }
      return null;
    });
  }
  /** Detail query keeps NotificationService's B1 contract unchanged. */
  public NotificationService.Notice notice(ActorContext a,String id) {
    return call(a,()->{
      if(id==null||id.isBlank())throw new SecurityException("通知不存在或无权查看");
      String sql="SELECT e.*,r.read_at FROM notification_events e JOIN notification_receipts r ON e.id=r.event_id WHERE e.id=? AND r.user_id=? AND r.recipient_org=?";
      List<Object> args=new ArrayList<>(List.of(id,a.userId(),a.organizationId()));if(!AccessPolicy.all(a)){sql+=" AND e.organization_id=?";args.add(a.organizationId());}
      try(PreparedStatement st=statement(sql,args.toArray());ResultSet rs=st.executeQuery()) {
        if(!rs.next())throw new SecurityException("通知不存在或无权查看");List<String> v=Codec.decode(rs.getString("payload"));String read=rs.getString("read_at");
        return new NotificationService.Notice(id,rs.getString("event_type"),rs.getString("organization_id"),v.get(0),v.get(1),v.get(2),Instant.parse(rs.getString("created_at")),read==null?null:Instant.parse(read));
      }
    });
  }
  public String noticeApplication(ActorContext a,String noticeId) {
    // Do not nest service transactions. Both checks use the same reentrant monitor.
    synchronized(store) {notice(a,noticeId);return call(a,()->{String id=scalar("SELECT request_id FROM access_notification_links WHERE event_id=?",noticeId);if(id==null)return "";visible(a,load(id));return id;});}
  }
  public List<AuditEntry> audit(ActorContext a,AuditFilter filter,int offset,int limit) {
    return auditQuery(a,filter,offset,limit,"");
  }
  public List<AuditEntry> auditForSubmission(ActorContext a,String submissionId,AuditFilter filter,int offset,int limit) {
    synchronized(store) {
      store.workflow().submission(a,submissionId);
      return auditQuery(a,filter,offset,limit,submissionId);
    }
  }
  private List<AuditEntry> auditQuery(ActorContext a,AuditFilter filter,int offset,int limit,String submissionId) {
    return call(a,()->{
      page(offset,limit);var selected=auditSelection(a,filter,submissionId);
      String sql="SELECT e.*,COALESCE(o.dataset,s.dataset) AS dataset,o.cell_data,w.submission_id"+selected.sql();List<Object> args=new ArrayList<>(selected.args());
      sql+=" ORDER BY CAST(e.event_at AS TIMESTAMP WITH TIME ZONE) DESC,e.id LIMIT ? OFFSET ?";args.add(limit);args.add(offset);
      List<AuditEntry> result=new ArrayList<>();
      try(PreparedStatement st=statement(sql,args.toArray());ResultSet rs=st.executeQuery()){while(rs.next()) {
        String dataset=blank(rs.getString("dataset")),cells=rs.getString("cell_data");String customer=dataset.isEmpty()?"":DatasetSchema.get(dataset).value(decode(cells),DatasetSchema.get(dataset).customerColumn);
        result.add(new AuditEntry(rs.getString("id"),rs.getString("event_at"),rs.getString("actor_name"),rs.getString("organization_id"),rs.getString("action"),rs.getString("record_id"),rs.getString("request_id"),dataset,customer,blank(rs.getString("submission_id")),decode(rs.getString("before_data")),decode(rs.getString("after_data")),rs.getString("details")));
      }}return List.copyOf(result);
    });
  }
  private record AuditSelection(String sql,List<Object> args) {}
  private AuditSelection auditSelection(ActorContext a,AuditFilter filter,String submissionId) {
      String category=blank(filter.category());if(category.isEmpty())category="business";
      if(!Set.of("business","security","all").contains(category))throw new IllegalArgumentException("记录类别无效");
      boolean security=!category.equals("business");
      if(security&&!PlatformStore.isManager(a))throw new SecurityException("没有账号管理记录查看权限");
      if(filter.from()!=null&&filter.through()!=null&&filter.from().isAfter(filter.through()))throw new IllegalArgumentException("开始日期不能晚于结束日期");
      String sql=" FROM audit_events e LEFT JOIN official_records o ON o.id=e.record_id LEFT JOIN workflow_audit_links w ON w.event_id=e.id LEFT JOIN submissions s ON s.id=w.submission_id WHERE 1=1";
      List<Object> args=new ArrayList<>();
      if(!submissionId.isEmpty()){sql+=" AND w.submission_id=?";args.add(submissionId);}
      String types="(e.action LIKE 'USER_%' OR e.action LIKE 'PASSWORD_%' OR e.action LIKE 'ACCESS_%' OR e.action LIKE 'SECURITY_%' OR e.action LIKE 'NOTICE_%' OR e.action='AUDIT_PURGE')";
      sql+=" AND e.action NOT IN ('DRAFT_SAVE','DRAFT_RESTORE_RETURNED')";if(!category.equals("all"))sql+=" AND "+(security?types:"NOT "+types);
      if(a.role()!=Role.SUPER_ADMIN)sql+=" AND e.actor_role<>'SUPER_ADMIN'";
      if(!AccessPolicy.all(a)){sql+=" AND e.organization_id=?";args.add(a.organizationId());}
      if(!blank(filter.organization()).isEmpty()){organization(filter.organization());AccessPolicy.require(a,AccessPolicy.Action.VIEW,filter.organization());sql+=" AND e.organization_id=?";args.add(filter.organization());}
      if(!blank(filter.dataset()).isEmpty()){DatasetSchema.get(filter.dataset());sql+=" AND COALESCE(o.dataset,s.dataset)=?";args.add(filter.dataset());}
      if(filter.from()!=null){sql+=" AND e.event_at>=?";args.add(boundary(filter.from()));}
      if(filter.through()!=null){sql+=" AND e.event_at<?";args.add(boundary(filter.through().plusDays(1)));}
      String search=text(filter.search(),100);if(!search.isEmpty()){sql+=" AND (LOCATE(?,e.actor_name)>0 OR e.record_id=? OR e.request_id=? OR w.submission_id=? OR e.action=? OR (o.id IS NULL AND EXISTS (SELECT 1 FROM submission_items si WHERE si.submission_id=w.submission_id AND si.record_id=?)))";args.addAll(Collections.nCopies(6,search));}
      return new AuditSelection(sql,args);
  }
  /** Exact, bounded targets shared with the visible audit filter; called inside the maintenance transaction. */
  List<String> auditIds(ActorContext a,AuditFilter filter,String submissionId)throws SQLException {
    if(!blank(submissionId).isEmpty())notices.submission(a,submissionId);
    var selected=auditSelection(a,filter,blank(submissionId));List<String> result=new ArrayList<>();
    try(var st=statement("SELECT DISTINCT e.id"+selected.sql()+" AND e.action<>'AUDIT_PURGE' ORDER BY e.id LIMIT 20001",selected.args().toArray());var rs=st.executeQuery()){while(rs.next())result.add(rs.getString(1));}
    return List.copyOf(result);
  }
  private void notifyManagers(Application r,String title,String summary)throws SQLException {
    List<String> recipients=new ArrayList<>();
    try(Statement st=db.createStatement();ResultSet rs=st.executeQuery("SELECT * FROM users WHERE active=TRUE")){while(rs.next()) {
      String role=rs.getString("role"),org=rs.getString("organization_id");
      if(role.equals("SUPER_ADMIN")||!r.organization().equals(Organizations.DIVISION)&&
        (role.equals("DIVISION_ADMIN")||r.route().equals("BRANCH")&&role.equals("BRANCH_ADMIN")&&org.equals(r.organization())))recipients.add(rs.getString("id"));
    }}
    String event=notices.emit("ACCESS_REQUEST",r.organization(),title,summary,"",recipients,"access-"+r.id()+"-"+r.revision());
    exec("INSERT INTO access_notification_links VALUES(?,?)",event,r.id());
  }
  private boolean hasBranchManager(String org)throws SQLException {return scalar("SELECT id FROM users WHERE active=TRUE AND role='BRANCH_ADMIN' AND organization_id=? LIMIT 1",org)!=null;}
  private boolean hasRequiredManager(Role role,String org)throws SQLException {
    String sql=switch(role){
      case DIVISION_ADMIN -> "SELECT id FROM users WHERE active=TRUE AND role='SUPER_ADMIN' LIMIT 1";
      case BRANCH_ADMIN -> "SELECT id FROM users WHERE active=TRUE AND role='DIVISION_ADMIN' LIMIT 1";
      case OPERATOR,REVIEWER -> "SELECT id FROM users WHERE active=TRUE AND role='BRANCH_ADMIN' AND organization_id=? LIMIT 1";
      case SUPER_ADMIN -> "SELECT id FROM users WHERE active=TRUE AND role='SUPER_ADMIN' LIMIT 1";
    };
    return switch(role){case OPERATOR,REVIEWER->scalar(sql,org)!=null;default->scalar(sql)!=null;};
  }
  private static String requiredManagerMessage(Role role){return switch(role){
    case DIVISION_ADMIN->"当前没有超级管理员，请先联系超级管理员建立分行管理员";
    case BRANCH_ADMIN->"当前没有分行管理员，请先联系分行管理员建立支行管理员";
    case OPERATOR,REVIEWER->"当前没有所属支行管理员，请先联系支行管理员建立后再申请";
    case SUPER_ADMIN->"当前没有超级管理员，不能提交该申请";
  };}
  private static void validateApplicationRole(String org,Role role){
    if(role==Role.SUPER_ADMIN||Organizations.DIVISION.equals(org)&&role!=Role.DIVISION_ADMIN||!Organizations.DIVISION.equals(org)&&role==Role.DIVISION_ADMIN)
      throw new IllegalArgumentException("申请机构与目标角色不匹配");
  }
  private void visible(ActorContext a,Application r) {
    manager(a);if(r==null||a.role()==Role.DIVISION_ADMIN&&r.organization().equals(Organizations.DIVISION)||a.role()==Role.BRANCH_ADMIN&&!a.organizationId().equals(r.organization()))throw new SecurityException("申请不存在或不在管理范围");
  }
  private static void manager(ActorContext a){if(!PlatformStore.isManager(a))throw new SecurityException("没有申请审批权限");}
  private static void organization(String org){if(!Organizations.DIVISION.equals(org)&&!Organizations.BRANCHES.containsKey(org))throw new IllegalArgumentException("请选择分行或九家支行之一");}
  private Application load(String id)throws SQLException {try(PreparedStatement st=statement("SELECT * FROM access_requests WHERE id=?",id);ResultSet rs=st.executeQuery()){return rs.next()?read(rs):null;}}
  private static Application read(ResultSet r)throws SQLException {String role=blank(r.getString("requested_role"));return new Application(r.getString("id"),r.getString("auth_number"),r.getString("display_name"),r.getString("organization_id"),r.getString("state"),r.getLong("revision"),r.getString("route_level"),r.getString("created_at"),r.getString("decided_at"),blank(r.getString("decision_reason")),r.getString("created_user_id"),role.isEmpty()?null:Role.valueOf(role));}
  private <T>T call(ActorContext a,PlatformStore.WorkflowWork<T> work){if(a==null||a.identityRevision()<0)throw new SecurityException("必须使用当前真实登录身份");return store.workflowTransaction(a,work);}
  private PreparedStatement statement(String sql,Object...args)throws SQLException {PreparedStatement st=db.prepareStatement(sql);for(int i=0;i<args.length;i++)st.setObject(i+1,args[i]);return st;}
  private void exec(String sql,Object...args)throws SQLException {try(PreparedStatement st=statement(sql,args)){st.executeUpdate();}}
  private String scalar(String sql,Object...args)throws SQLException {try(PreparedStatement st=statement(sql,args);ResultSet rs=st.executeQuery()){return rs.next()?rs.getString(1):null;}}
  private static List<String> decode(String raw){if(raw==null||raw.isEmpty())return List.of();try{return Codec.decode(raw);}catch(IllegalArgumentException e){return List.of("历史原始记录："+raw);}}
  private static String blank(String value){return value==null?"":value.strip();}
  private static String text(String value,int max){String s=blank(value);if(s.length()>max)throw new IllegalArgumentException("输入内容过长");return s;}
  private static String boundary(LocalDate date){return date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toString().replace("Z","");}
  private static void page(int offset,int limit){if(offset<0||offset>100000||limit<1||limit>100)throw new IllegalArgumentException("分页范围无效");}
  private String now(){return clock.instant().toString();}
}

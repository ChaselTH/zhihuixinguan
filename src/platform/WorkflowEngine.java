package xinguan.platform;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static xinguan.platform.WorkflowContracts.*;

/** Public methods use the PlatformStore monitor, identity check and one shared DB transaction. */
final class WorkflowEngine implements WorkflowService, NotificationService {
  private final PlatformStore store;
  private final Connection db;
  private final Clock clock;
  private final Consumer<String> checkpoint;
  private static final Duration CONFIRM_TTL=Duration.ofMinutes(15);
  // Fixed precision preserves chronological ordering in the VARCHAR timestamp columns.
  private static final java.time.format.DateTimeFormatter TIMESTAMP=new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter();

  WorkflowEngine(PlatformStore store,Connection db,Clock clock,Consumer<String> checkpoint) {
    this.store=store;this.db=db;this.clock=Objects.requireNonNull(clock);this.checkpoint=checkpoint;
  }
  private <T>T call(ActorContext actor,PlatformStore.WorkflowWork<T> work) {
    if(actor==null||actor.identityRevision()<1)throw new SecurityException("工作流需要有效的实名登录身份");
    try { return store.workflowTransaction(actor,work); }
    catch(SecurityException|WorkflowException e) { throw e; }
    catch(ConcurrentModificationException e) { throw error(Code.VERSION_CONFLICT,"记录已变化，本次全部未保存，请重新核对"); }
    catch(IllegalArgumentException e) { throw error(Code.INVALID_INPUT,e.getMessage()); }
    catch(IllegalStateException e) { throw error(Code.TRANSACTION_FAILED,"数据库处理失败，本次事务未完成；请重新查询状态后使用原请求编号重试"); }
  }
  @Override public Draft saveDraft(ActorContext a,String id,long expectedVersion,String dataset,List<RecordChange> changes,String priorId,String requestId) {
    return call(a,()->{
      operator(a);DatasetSchema.get(dataset);validateChanges(changes,true);
      String draftId=blank(id),prior=blank(priorId);
      String hash=hash("SAVE_DRAFT",draftId,Long.toString(expectedVersion),dataset,prior,WorkflowCodec.changes(changes));
      String repeated=repeat(a,requestId,hash);if(repeated!=null)return loadDraft(a,repeated);
      Draft old=null;
      if(draftId.isEmpty()) { if(expectedVersion!=0)throw error(Code.INVALID_INPUT,"新草稿版本必须为 0");draftId=id(); }
      else {
        old=loadDraft(a,draftId);
        if(old.version()!=expectedVersion)throw error(Code.VERSION_CONFLICT,"草稿已被另一页面保存，请恢复最新草稿后重试");
        if(!old.dataset().equals(dataset))throw error(Code.INVALID_INPUT,"不能改变已有草稿的数据集");
      }
      List<SnapshotRow> rows=snapshot(a,dataset,changes,AccessPolicy.Action.SAVE_DRAFT,true);
      checkPrior(a,prior,dataset,a.organizationId());
      String payload=encode(rows),now=now();
      if(old==null)exec("INSERT INTO drafts(id,owner_id,organization_id,dataset,payload,revision,updated_at,prior_submission_id) VALUES(?,?,?,?,?,1,?,?)",
        draftId,a.userId(),a.organizationId(),dataset,payload,now,prior);
      else exec("UPDATE drafts SET payload=?,revision=revision+1,updated_at=?,prior_submission_id=? WHERE id=?",payload,now,prior,draftId);
      // Only metadata is audited for private drafts. Do not expose private values through the shared audit API.
      audit(a,a.organizationId(),draftId,"DRAFT_SAVE",requestId,"","","私人草稿；填报内容仅本人可见");
      checkpoint.accept("draft-written");remember(a,requestId,hash,draftId);
      return loadDraft(a,draftId);
    });
  }
  @Override public Draft draft(ActorContext a,String id) { return call(a,()->{operator(a);return loadDraft(a,id);}); }
  @Override public List<Draft> drafts(ActorContext a,String dataset,int offset,int limit) {
    return call(a,()->{
      operator(a);page(offset,limit);if(!blank(dataset).isEmpty())DatasetSchema.get(dataset);
      String sql="SELECT * FROM drafts WHERE owner_id=? AND organization_id=?";
      List<Object> args=new ArrayList<>(List.of(a.userId(),a.organizationId()));
      if(!blank(dataset).isEmpty()){sql+=" AND dataset=?";args.add(dataset);}
      sql+=" ORDER BY updated_at DESC,id LIMIT ? OFFSET ?";args.add(limit);args.add(offset);
      List<Draft> result=new ArrayList<>();
      try(PreparedStatement st=statement(sql,args.toArray());ResultSet rs=st.executeQuery()){while(rs.next())result.add(readDraft(rs));}
      return List.copyOf(result);
    });
  }
  @Override public Preview previewDraft(ActorContext a,String draftId,long expectedVersion) {
    return call(a,()->{
      operator(a);Draft draft=loadDraft(a,draftId);
      if(draft.version()!=expectedVersion)throw error(Code.VERSION_CONFLICT,"草稿已变化，请使用最新草稿重新预览");
      requireRows(draft.rows());validateSnapshot(a,draft.rows(),AccessPolicy.Action.SUBMIT);
      return createPreview(a,Mode.REVIEW,draft.organizationId(),draft.dataset(),draft.id(),draft.version(),draft.priorSubmissionId(),draft.rows());
    });
  }
  @Override public Preview previewDirect(ActorContext a,String dataset,List<RecordChange> changes) {
    return call(a,()->{
      direct(a);List<SnapshotRow> rows=snapshot(a,dataset,changes,AccessPolicy.Action.DIRECT_EDIT,false);
      return createPreview(a,Mode.DIRECT,rows.get(0).before().organizationId(),dataset,"",0,"",rows);
    });
  }
  @Override public Preview preview(ActorContext a,String previewId) {
    return call(a,()->{PreviewRecord p=loadPreview(a,previewId);checkPreviewIdentity(a,p);return p.preview();});
  }
  @Override public Submission confirm(ActorContext a,String previewId,String requestId) {
    return call(a,()->{
      PreviewRecord stored=loadPreview(a,previewId);Preview preview=stored.preview();
      checkMode(a,preview.mode(),preview.organizationId());
      String hash=hash("CONFIRM",previewId);
      String repeated=repeat(a,requestId,hash);if(repeated!=null)return loadSubmission(a,repeated);
      checkPreviewIdentity(a,stored);
      // A second request for the same confirmation is also harmless, even after the preview expires.
      if(!stored.resultId().isEmpty()) {
        remember(a,requestId,hash,stored.resultId());return loadSubmission(a,stored.resultId());
      }
      if(!clock.instant().isBefore(preview.expiresAt()))throw error(Code.CONFIRMATION_EXPIRED,"确认已超过 15 分钟，请重新预览");
      if(preview.mode()==Mode.REVIEW) {
        Draft draft=loadDraft(a,preview.draftId());
        if(draft.version()!=preview.draftVersion()||!encode(draft.rows()).equals(encode(preview.rows()))||!draft.priorSubmissionId().equals(preview.priorSubmissionId()))
          throw error(Code.VERSION_CONFLICT,"预览后草稿已变化，请重新预览，未创建待审单");
      }
      validateSnapshot(a,preview.rows(),preview.mode()==Mode.REVIEW?AccessPolicy.Action.SUBMIT:AccessPolicy.Action.DIRECT_EDIT);
      checkPrior(a,preview.priorSubmissionId(),preview.dataset(),preview.organizationId());
      List<String> reviewers=List.of();
      if(preview.mode()==Mode.REVIEW) {
        for(var row:preview.rows())if(scalar("SELECT submission_id FROM pending_submission_records WHERE owner_id=? AND record_id=?",a.userId(),row.before().id())!=null)
          throw error(Code.ALREADY_SUBMITTED,"本次包含本人已提交待复核的记录；请先处理原提交单");
        reviewers=reviewers(preview.organizationId());
        if(reviewers.isEmpty())throw error(Code.NO_REVIEWER,"本支行尚无有效复核员，请管理员配置后重试；草稿仍保留");
      }
      String submissionId=id(),now=now();boolean isDirect=preview.mode()==Mode.DIRECT;
      exec("INSERT INTO submissions(id,owner_id,organization_id,dataset,state,payload,created_at,reviewer_id,decided_at,decision_reason,mode,owner_name,reviewer_name,draft_id,draft_revision,prior_submission_id,preview_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        submissionId,a.userId(),preview.organizationId(),preview.dataset(),isDirect?"APPROVED":"SUBMITTED",encode(preview.rows()),now,
        isDirect?a.userId():null,isDirect?now:null,"",preview.mode().name(),a.name(),isDirect?a.name():"",preview.draftId(),preview.draftVersion(),preview.priorSubmissionId(),preview.id());
      for(var row:preview.rows()) {
        BusinessRecord r=row.before();
        exec("INSERT INTO submission_items VALUES(?,?,?,?)",submissionId,r.id(),java.sql.Date.valueOf(r.period().start()),java.sql.Date.valueOf(r.period().end()));
        if(!isDirect)exec("INSERT INTO pending_submission_records VALUES(?,?,?)",a.userId(),r.id(),submissionId);
      }
      checkpoint.accept("submission-written");
      if(isDirect)linkAudits(submissionId,store.applyOfficialChanges(a,changes(preview.rows()),AccessPolicy.Action.DIRECT_EDIT,"DIRECT_EDIT",requestId,submissionId));
      linkAudits(submissionId,List.of(audit(a,preview.organizationId(),submissionId,isDirect?"DIRECT_SUBMIT":"SUBMIT",requestId,"","",a.userId())));
      emit(isDirect?"DIRECT_EDIT":"SUBMITTED",preview.organizationId(),"填报"+(isDirect?"已生效":"待复核"),
        summary(a,preview.dataset(),preview.rows().size()),submissionId,isDirect?List.of(a.userId()):reviewers,"submission-"+submissionId);
      exec("UPDATE workflow_previews SET result_id=? WHERE id=?",submissionId,preview.id());
      remember(a,requestId,hash,submissionId);checkpoint.accept("confirmation-complete");
      return loadSubmission(a,submissionId);
    });
  }
  @Override public Submission submission(ActorContext a,String id) { return call(a,()->loadSubmission(a,id)); }
  @Override public List<Submission> submissions(ActorContext a,Query query) { return call(a,()->listSubmissions(a,query,false)); }
  @Override public List<Submission> pendingReviews(ActorContext a,Query query) {
    return call(a,()->{AccessPolicy.require(a,AccessPolicy.Action.REVIEW,a.organizationId());return listSubmissions(a,query,true);});
  }
  @Override public List<Submission> recordHistory(ActorContext a,String recordId,int offset,int limit) {
    return call(a,()->{
      store.find(a,recordId);page(offset,limit);List<Submission> result=new ArrayList<>();
      try(PreparedStatement st=statement("SELECT s.* FROM submissions s JOIN submission_items i ON i.submission_id=s.id WHERE i.record_id=? ORDER BY s.created_at DESC,s.id LIMIT ? OFFSET ?",recordId,limit,offset);ResultSet rs=st.executeQuery()) {
        while(rs.next()){AccessPolicy.require(a,AccessPolicy.Action.VIEW,rs.getString("organization_id"));result.add(readSubmission(rs));}
      }
      return List.copyOf(result);
    });
  }
  @Override public List<AuditEntry> auditTrail(ActorContext a,String submissionId) {
    return call(a,()->{
      Submission submission=loadSubmission(a,submissionId);List<AuditEntry> result=new ArrayList<>();
      String sql="SELECT e.* FROM audit_events e JOIN workflow_audit_links l ON l.event_id=e.id WHERE e.organization_id=? AND l.submission_id=? ORDER BY e.event_at,e.id";
      try(PreparedStatement st=statement(sql,submission.organizationId(),submissionId);ResultSet rs=st.executeQuery()) {
        while(rs.next())result.add(new AuditEntry(rs.getString("id"),Instant.parse(rs.getString("event_at")),rs.getString("actor_id"),rs.getString("actor_name"),rs.getString("actor_role"),rs.getString("record_id"),rs.getString("action"),rs.getString("request_id"),rs.getString("before_data"),rs.getString("after_data"),rs.getString("details")));
      }
      return List.copyOf(result);
    });
  }
  @Override public Submission approve(ActorContext a,String id,String requestId) { return decide(a,id,"",requestId,true); }
  @Override public Submission reject(ActorContext a,String id,String reason,String requestId) { return decide(a,id,reason,requestId,false); }
  private Submission decide(ActorContext a,String id,String reason,String requestId,boolean approve) {
    return call(a,()->{
      Submission submission=loadSubmission(a,id);
      AccessPolicy.require(a,AccessPolicy.Action.REVIEW,submission.organizationId());
      if(a.userId().equals(submission.ownerId()))throw new SecurityException("不能复核本人提交");
      String explanation=approve?"":requiredText(reason,2000,"请填写 1～2000 字退回原因");
      String hash=hash(approve?"APPROVE":"REJECT",id,explanation);
      String repeated=repeat(a,requestId,hash);if(repeated!=null)return loadSubmission(a,repeated);
      if(submission.mode()!=Mode.REVIEW||submission.state()!=State.SUBMITTED)throw error(Code.ALREADY_DECIDED,"该提交单已经处理，请刷新；没有再次修改正式数据");
      if(approve) {
        UserAccount owner=store.sessionUser(submission.ownerId());
        if(owner==null||!owner.active()||owner.role()!=Role.OPERATOR||!owner.organizationId().equals(submission.organizationId()))
          throw error(Code.OWNER_CHANGED,"提交人的账号或机构权限已变化；请退回并重新核对，不得直接批准");
        validateSnapshot(a,submission.rows(),AccessPolicy.Action.REVIEW);
        linkAudits(id,store.applyOfficialChanges(a,changes(submission.rows()),AccessPolicy.Action.REVIEW,"REVIEW_APPROVED",requestId,id+";submitter="+submission.ownerId()));
      }
      exec("UPDATE submissions SET state=?,reviewer_id=?,reviewer_name=?,decided_at=?,decision_reason=? WHERE id=?",
        approve?"APPROVED":"RETURNED",a.userId(),a.name(),now(),explanation,id);
      exec("DELETE FROM pending_submission_records WHERE submission_id=?",id);
      linkAudits(id,List.of(audit(a,submission.organizationId(),id,approve?"REVIEW_DECISION":"REVIEW_RETURNED",requestId,"","",explanation)));
      checkpoint.accept("decision-written");
      emit(approve?"APPROVED":"RETURNED",submission.organizationId(),approve?"填报复核通过":"填报已退回",
        summary(a,submission.dataset(),submission.rows().size()),id,List.of(submission.ownerId()),"decision-"+id);
      remember(a,requestId,hash,id);checkpoint.accept("decision-complete");return loadSubmission(a,id);
    });
  }

  @Override public List<Notice> inbox(ActorContext a,boolean unreadOnly,int offset,int limit) {
    return call(a,()->{
      page(offset,limit);List<Object> args=noticeArgs(a);
      String sql=noticeQuery(a,"e.*,r.read_at")+(unreadOnly?" AND r.read_at IS NULL":"")+" ORDER BY e.created_at DESC,e.id LIMIT ? OFFSET ?";
      args.add(limit);args.add(offset);List<Notice> result=new ArrayList<>();
      try(PreparedStatement st=statement(sql,args.toArray());ResultSet rs=st.executeQuery()) {
        while(rs.next()) {List<String> v=Codec.decode(rs.getString("payload"));result.add(new Notice(rs.getString("id"),rs.getString("event_type"),rs.getString("organization_id"),v.get(0),v.get(1),v.get(2),Instant.parse(rs.getString("created_at")),instant(rs.getString("read_at"))));}
      }
      return List.copyOf(result);
    });
  }
  @Override public long unreadCount(ActorContext a) {
    return call(a,()->Long.parseLong(scalar(noticeQuery(a,"COUNT(*)")+" AND r.read_at IS NULL",noticeArgs(a).toArray())));
  }
  @Override public void markRead(ActorContext a,String noticeId) {
    call(a,()->{
      List<Object> args=noticeArgs(a);args.add(noticeId);
      if(scalar(noticeQuery(a,"e.id")+" AND e.id=?",args.toArray())==null)throw new SecurityException("通知不存在或无权访问");
      exec("UPDATE notification_receipts SET read_at=? WHERE event_id=? AND user_id=? AND read_at IS NULL",now(),noticeId,a.userId());return null;
    });
  }
  @Override public String publishNotice(ActorContext a,String org,String title,String summary,List<String> recipientIds,String requestId) {
    return call(a,()->{
      if(!PlatformStore.isManager(a))throw new SecurityException("只有管理员可以发布管理通知");
      if(!Organizations.BRANCHES.containsKey(org)&&!Organizations.DIVISION.equals(org))throw error(Code.INVALID_INPUT,"通知机构无效");
      AccessPolicy.require(a,AccessPolicy.Action.VIEW,org);
      String heading=requiredText(title,100,"通知标题需要 1～100 字"),body=requiredText(summary,500,"通知摘要需要 1～500 字");
      if(recipientIds==null||recipientIds.isEmpty()||recipientIds.size()>100)throw error(Code.INVALID_INPUT,"通知需要 1～100 位接收人");
      if(recipientIds.stream().anyMatch(id->id==null||id.isBlank()))throw error(Code.INVALID_INPUT,"通知接收人编号不能为空");
      List<String> recipients=new ArrayList<>(new TreeSet<>(recipientIds));
      String hash=hash("NOTICE",org,heading,body,Codec.encode(recipients));
      String repeated=repeat(a,requestId,hash);if(repeated!=null)return repeated;
      for(String recipient:recipients)if(!eligibleRecipient(recipient,org))throw new SecurityException("接收人不存在、已停用或不属于通知范围");
      String id=emit("NOTICE",org,heading,body,"",recipients,"notice-"+id());
      audit(a,org,id,"NOTICE_PUBLISH",requestId,"","","管理通知");remember(a,requestId,hash,id);return id;
    });
  }

  private List<SnapshotRow> snapshot(ActorContext a,String dataset,List<RecordChange> changes,AccessPolicy.Action action,boolean emptyAllowed) {
    DatasetSchema schema=DatasetSchema.get(dataset);validateChanges(changes,emptyAllowed);
    Set<String> seen=new HashSet<>();List<SnapshotRow> result=new ArrayList<>();List<Conflict> conflicts=new ArrayList<>();String org=null;
    for(RecordChange change:changes) {
      if(!seen.add(change.recordId()))throw error(Code.INVALID_INPUT,"一次修改不能包含重复记录");
      BusinessRecord current=store.find(a,change.recordId());AccessPolicy.require(a,action,current.organizationId());
      if(!current.dataset().equals(dataset))throw error(Code.INVALID_INPUT,"一单只能修改同一种表格");
      if(org!=null&&!org.equals(current.organizationId()))throw error(Code.INVALID_INPUT,"一单只能包含同一家支行，请分单提交");
      org=current.organizationId();Map<String,String> values=new TreeMap<>();
      for(var e:change.values().entrySet()) {
        int index=schema.index(e.getKey());schema.validateEdit(index,e.getValue());String value=e.getValue().strip();
        if(!value.equals(current.values().get(index)))values.put(e.getKey(),value);
      }
      if(current.version()!=change.expectedVersion())conflicts.add(new Conflict(null,current,change));
      if(!values.isEmpty())result.add(new SnapshotRow(current,new RecordChange(current.id(),current.version(),values)));
    }
    if(!conflicts.isEmpty())throw conflicts(conflicts);
    if(!emptyAllowed)requireRows(result);encode(result);return List.copyOf(result);
  }
  private void validateSnapshot(ActorContext a,List<SnapshotRow> rows,AccessPolicy.Action action) {
    requireRows(rows);List<Conflict> conflicts=new ArrayList<>();
    for(var row:rows) {
      BusinessRecord current=store.find(a,row.before().id());AccessPolicy.require(a,action,current.organizationId());
      if(!current.organizationId().equals(row.before().organizationId())||!current.dataset().equals(row.before().dataset()))throw new SecurityException("记录归属已变化，请重新核对");
      if(current.version()!=row.change().expectedVersion()||!current.values().equals(row.before().values()))conflicts.add(new Conflict(row.before(),current,row.change()));
      DatasetSchema schema=DatasetSchema.get(current.dataset());for(var field:row.change().values().entrySet())schema.validateEdit(schema.index(field.getKey()),field.getValue());
    }
    if(!conflicts.isEmpty())throw conflicts(conflicts);
  }
  private Preview createPreview(ActorContext a,Mode mode,String org,String dataset,String draft,long version,String prior,List<SnapshotRow> rows)throws SQLException {
    Instant created=clock.instant(),expires=created.plus(CONFIRM_TTL);String id=id();
    long count=Long.parseLong(scalar("SELECT COUNT(*) FROM workflow_previews WHERE owner_id=? AND expires_at>? AND result_id IS NULL",a.userId(),TIMESTAMP.format(created)));
    if(count>=100)throw error(Code.INVALID_INPUT,"未确认预览过多，请稍后再试");
    exec("INSERT INTO workflow_previews VALUES(?,?,?,?,?,?,?,?,?,?,?,?,NULL)",id,a.userId(),a.identityRevision(),org,dataset,mode.name(),draft,version,prior,encode(rows),TIMESTAMP.format(created),TIMESTAMP.format(expires));
    return new Preview(id,mode,a.userId(),org,dataset,draft,version,prior,created,expires,rows);
  }
  private record PreviewRecord(Preview preview,long identityRevision,String resultId) {}
  private PreviewRecord loadPreview(ActorContext a,String id)throws SQLException {
    try(PreparedStatement st=statement("SELECT * FROM workflow_previews WHERE id=? AND owner_id=?",id,a.userId());ResultSet rs=st.executeQuery()) {
      if(!rs.next())throw new SecurityException("预览不存在或无权访问");
      AccessPolicy.require(a,AccessPolicy.Action.VIEW,rs.getString("organization_id"));
      return new PreviewRecord(new Preview(rs.getString("id"),Mode.valueOf(rs.getString("mode")),rs.getString("owner_id"),rs.getString("organization_id"),rs.getString("dataset"),rs.getString("draft_id"),rs.getLong("draft_revision"),rs.getString("prior_submission_id"),Instant.parse(rs.getString("created_at")),Instant.parse(rs.getString("expires_at")),WorkflowCodec.rows(rs.getString("payload"))),rs.getLong("identity_revision"),blank(rs.getString("result_id")));
    }
  }
  private void checkPreviewIdentity(ActorContext a,PreviewRecord stored) {
    if(a.identityRevision()!=stored.identityRevision())throw error(Code.CONFIRMATION_EXPIRED,"账号状态变化后需要重新预览");
    checkMode(a,stored.preview().mode(),stored.preview().organizationId());
  }
  private Draft loadDraft(ActorContext a,String id)throws SQLException {
    operator(a);
    try(PreparedStatement st=statement("SELECT * FROM drafts WHERE id=? AND owner_id=? AND organization_id=?",id,a.userId(),a.organizationId());ResultSet rs=st.executeQuery()) {
      if(!rs.next())throw new SecurityException("草稿不存在或无权访问");return readDraft(rs);
    }
  }
  private Draft readDraft(ResultSet rs)throws SQLException {
    return new Draft(rs.getString("id"),rs.getString("owner_id"),rs.getString("organization_id"),rs.getString("dataset"),rs.getLong("revision"),Instant.parse(rs.getString("updated_at")),rs.getString("prior_submission_id"),WorkflowCodec.rows(rs.getString("payload")));
  }
  private Submission loadSubmission(ActorContext a,String id)throws SQLException {
    try(PreparedStatement st=statement("SELECT * FROM submissions WHERE id=?",id);ResultSet rs=st.executeQuery()) {
      if(!rs.next())throw new SecurityException("提交单不存在或无权访问");
      AccessPolicy.require(a,AccessPolicy.Action.VIEW,rs.getString("organization_id"));return readSubmission(rs);
    }
  }
  private Submission readSubmission(ResultSet rs)throws SQLException {
    return new Submission(rs.getString("id"),Mode.valueOf(rs.getString("mode")),State.valueOf(rs.getString("state")),rs.getString("owner_id"),rs.getString("owner_name"),rs.getString("organization_id"),rs.getString("dataset"),rs.getString("draft_id"),rs.getLong("draft_revision"),rs.getString("prior_submission_id"),Instant.parse(rs.getString("created_at")),blank(rs.getString("reviewer_id")),rs.getString("reviewer_name"),instant(rs.getString("decided_at")),blank(rs.getString("decision_reason")),WorkflowCodec.rows(rs.getString("payload")));
  }
  private List<Submission> listSubmissions(ActorContext a,Query query,boolean pending)throws SQLException {
    if(query==null)throw error(Code.INVALID_INPUT,"缺少查询条件");page(query.offset(),query.limit());
    if(query.from()!=null&&query.through()!=null&&query.from().isAfter(query.through()))throw error(Code.INVALID_INPUT,"时间区间无效");
    String sql="SELECT s.* FROM submissions s WHERE 1=1";List<Object> args=new ArrayList<>();
    if(!AccessPolicy.all(a)){sql+=" AND s.organization_id=?";args.add(a.organizationId());}
    if(!blank(query.organization()).isEmpty()) {
      if(!Organizations.BRANCHES.containsKey(query.organization()))throw error(Code.INVALID_INPUT,"查询机构无效");
      AccessPolicy.require(a,AccessPolicy.Action.VIEW,query.organization());sql+=" AND s.organization_id=?";args.add(query.organization());
    }
    if(!blank(query.dataset()).isEmpty()){DatasetSchema.get(query.dataset());sql+=" AND s.dataset=?";args.add(query.dataset());}
    State state=pending?State.SUBMITTED:query.state();if(state!=null){sql+=" AND s.state=?";args.add(state.name());}
    if(query.mineOnly()){sql+=" AND s.owner_id=?";args.add(a.userId());}
    if(query.from()!=null||query.through()!=null) {
      sql+=" AND EXISTS (SELECT 1 FROM submission_items i WHERE i.submission_id=s.id";
      if(query.from()!=null){sql+=" AND i.period_end>=?";args.add(java.sql.Date.valueOf(query.from()));}
      if(query.through()!=null){sql+=" AND i.period_start<=?";args.add(java.sql.Date.valueOf(query.through()));}
      sql+=")";
    }
    sql+=" ORDER BY s.created_at DESC,s.id LIMIT ? OFFSET ?";args.add(query.limit());args.add(query.offset());
    List<Submission> result=new ArrayList<>();try(PreparedStatement st=statement(sql,args.toArray());ResultSet rs=st.executeQuery()){while(rs.next())result.add(readSubmission(rs));}
    return List.copyOf(result);
  }
  private void checkPrior(ActorContext a,String prior,String dataset,String org)throws SQLException {
    if(prior.isEmpty())return;Submission old=loadSubmission(a,prior);
    if(!old.ownerId().equals(a.userId())||old.state()!=State.RETURNED||!old.dataset().equals(dataset)||!old.organizationId().equals(org))
      throw error(Code.INVALID_INPUT,"只能关联本人同机构、同表格的已退回提交单");
  }
  private List<String> reviewers(String org)throws SQLException {
    List<String> result=new ArrayList<>();try(PreparedStatement st=statement("SELECT id FROM users WHERE active=TRUE AND role='REVIEWER' AND organization_id=? ORDER BY id",org);ResultSet rs=st.executeQuery()){while(rs.next())result.add(rs.getString(1));}return result;
  }
  private boolean eligibleRecipient(String id,String org) {
    UserAccount user=store.sessionUser(id);
    return user!=null&&user.active()&&(AccessPolicy.all(user.actor())||user.organizationId().equals(org));
  }
  String emit(String type,String org,String title,String summary,String submissionId,List<String> recipients,String eventKey)throws SQLException {
    String existing=scalar("SELECT id FROM notification_events WHERE event_key=?",eventKey);if(existing!=null)return existing;
    String id=id();exec("INSERT INTO notification_events(id,event_type,organization_id,payload,created_at,event_key) VALUES(?,?,?,?,?,?)",id,type,org,Codec.encode(List.of(title,summary,submissionId)),now(),eventKey);
    for(String recipient:new LinkedHashSet<>(recipients))if(eligibleRecipient(recipient,org)) {
      UserAccount user=store.sessionUser(recipient);
      exec("INSERT INTO notification_receipts(event_id,user_id,read_at,recipient_org) VALUES(?,?,NULL,?)",id,recipient,user.organizationId());
    }
    checkpoint.accept("notification-written");return id;
  }
  private String noticeQuery(ActorContext a,String columns) {
    return "SELECT "+columns+" FROM notification_events e JOIN notification_receipts r ON r.event_id=e.id WHERE r.user_id=? AND r.recipient_org=?"+(!AccessPolicy.all(a)?" AND e.organization_id=?":"");
  }
  private List<Object> noticeArgs(ActorContext a) {
    List<Object> args=new ArrayList<>(List.of(a.userId(),a.organizationId()));if(!AccessPolicy.all(a))args.add(a.organizationId());return args;
  }
  private String repeat(ActorContext a,String request,String hash)throws SQLException {
    if(request==null||!request.matches("[A-Za-z0-9_-]{10,100}"))throw error(Code.INVALID_INPUT,"请求编号无效，请刷新重试");
    try(PreparedStatement st=statement("SELECT * FROM processed_requests WHERE id=?",request);ResultSet rs=st.executeQuery()) {
      if(!rs.next())return null;
      if(!a.userId().equals(rs.getString("actor_id"))||!hash.equals(rs.getString("payload_hash")))throw error(Code.REQUEST_REUSED,"请求编号已用于其他操作，请重新核对");
      return rs.getString("result_id");
    }
  }
  private void remember(ActorContext a,String request,String hash,String result)throws SQLException { exec("INSERT INTO processed_requests VALUES(?,?,?,?)",request,a.userId(),hash,result); }
  private String audit(ActorContext a,String org,String record,String action,String request,String before,String after,String details)throws SQLException {return store.workflowAudit(a,org,record,action,request,before,after,details);}
  private void linkAudits(String submissionId,List<String> ids)throws SQLException {for(String id:ids)exec("INSERT INTO workflow_audit_links VALUES(?,?)",submissionId,id);}
  private PreparedStatement statement(String sql,Object...args)throws SQLException {PreparedStatement st=db.prepareStatement(sql);for(int i=0;i<args.length;i++)st.setObject(i+1,args[i]);return st;}
  private void exec(String sql,Object...args)throws SQLException {try(PreparedStatement st=statement(sql,args)){st.executeUpdate();}}
  private String scalar(String sql,Object...args)throws SQLException {try(PreparedStatement st=statement(sql,args);ResultSet rs=st.executeQuery()){return rs.next()?rs.getString(1):null;}}
  private String now(){return TIMESTAMP.format(clock.instant());}
  private static Instant instant(String value){return value==null||value.isEmpty()?null:Instant.parse(value);}
  private static String blank(String value){return value==null?"":value.strip();}
  private static String id(){return UUID.randomUUID().toString();}
  private static String hash(String...parts){return Codec.hash(Codec.encode(Arrays.asList(parts)));}
  private static String encode(List<SnapshotRow> rows){String payload=WorkflowCodec.rows(rows);if(payload.length()>4_000_000)throw error(Code.INVALID_INPUT,"单次内容过大，请分单提交");return payload;}
  private static String requiredText(String value,int max,String message){if(value==null||value.isBlank()||value.length()>max)throw error(Code.INVALID_INPUT,message);return value.strip();}
  private static void validateChanges(List<RecordChange> changes,boolean emptyAllowed){if(changes==null||changes.size()>200||(!emptyAllowed&&changes.isEmpty()))throw error(Code.INVALID_INPUT,"每单最多 200 条记录，正式提交不能为空");for(var c:changes)if(c==null||c.recordId()==null||c.expectedVersion()<1)throw error(Code.INVALID_INPUT,"缺少记录编号或版本");}
  private static void page(int offset,int limit){if(offset<0||offset>1_000_000||limit<1||limit>100)throw error(Code.INVALID_INPUT,"分页参数无效，每页最多 100 条");}
  private static void operator(ActorContext a){AccessPolicy.require(a,AccessPolicy.Action.SAVE_DRAFT,a.organizationId());}
  private static void direct(ActorContext a){AccessPolicy.require(a,AccessPolicy.Action.DIRECT_EDIT,a.organizationId());}
  private static void checkMode(ActorContext a,Mode mode,String org){AccessPolicy.require(a,mode==Mode.REVIEW?AccessPolicy.Action.SUBMIT:AccessPolicy.Action.DIRECT_EDIT,org);}
  private static void requireRows(List<SnapshotRow> rows){if(rows.isEmpty())throw error(Code.INVALID_INPUT,"没有实际变化的字段，无需提交");}
  private static List<RecordChange> changes(List<SnapshotRow> rows){return rows.stream().map(SnapshotRow::change).toList();}
  private static String summary(ActorContext a,String dataset,int count){return a.name()+" · "+DatasetSchema.get(dataset).label+" · "+count+" 条；详情需登录核验权限";}
  private static WorkflowException conflicts(List<Conflict> conflicts){return new WorkflowException(Code.VERSION_CONFLICT,"正式记录已变化，本次整单未生效，请核对原值、当前值和拟提交值",conflicts);}
  private static WorkflowException error(Code code,String message){return new WorkflowException(code,message);}
}

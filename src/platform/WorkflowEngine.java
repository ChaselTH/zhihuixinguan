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
        if(isConsumed(a,old))throw error(Code.ALREADY_DECIDED,"该草稿版本已提交并冻结，请从提交记录查看；如需修改请使用退回后的新草稿");
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
  @Override public Draft editableDraft(ActorContext a,String id) {
    return call(a,()->{operator(a);Draft draft=loadDraft(a,id);
      if(isConsumed(a,draft))throw error(Code.ALREADY_DECIDED,"该草稿版本已提交并冻结，请从提交记录查看；退回后可恢复草稿");
      return draft;
    });
  }
  @Override public List<Draft> drafts(ActorContext a,String dataset,int offset,int limit) {
    return call(a,()->{
      operator(a);page(offset,limit);if(!blank(dataset).isEmpty())DatasetSchema.get(dataset);
      String sql="SELECT d.* FROM drafts d WHERE d.owner_id=? AND d.organization_id=?";
      List<Object> args=new ArrayList<>(List.of(a.userId(),a.organizationId()));
      if(!blank(dataset).isEmpty()){sql+=" AND d.dataset=?";args.add(dataset);}
      // Filter consumed versions before paging so a submitted version cannot hide a later draft.
      sql+=" AND NOT EXISTS (SELECT 1 FROM submissions s WHERE s.owner_id=d.owner_id AND s.draft_id=d.id AND s.draft_revision=d.revision AND s.state IN ('SUBMITTED','PENDING_DIVISION','PARTIAL','APPROVED'))";
      // Canonical empty snapshots are inactive; exclude before LIMIT/OFFSET.
      sql+=" AND d.payload<>?";args.add(encode(List.of()));
      sql+=" ORDER BY d.updated_at DESC,d.id LIMIT ? OFFSET ?";args.add(limit);args.add(offset);
      List<Draft> result=new ArrayList<>();
      try(PreparedStatement st=statement(sql,args.toArray());ResultSet rs=st.executeQuery()){while(rs.next())result.add(readDraft(rs));}
      return List.copyOf(result);
    });
  }
  @Override public Preview previewDraft(ActorContext a,String draftId,long expectedVersion) {
    return call(a,()->{
      operator(a);Draft draft=loadDraft(a,draftId);
      if(isConsumed(a,draft))throw error(Code.ALREADY_DECIDED,"该草稿版本已提交并冻结，请从提交记录查看；如需修改请使用退回后的新草稿");
      if(draft.version()!=expectedVersion)throw error(Code.VERSION_CONFLICT,"草稿已变化，请使用最新草稿重新预览");
      requireRows(draft.rows());validateSnapshot(a,draft.rows(),AccessPolicy.Action.SUBMIT);
      return createPreview(a,Mode.REVIEW,draft.organizationId(),draft.dataset(),draft.id(),draft.version(),draft.priorSubmissionId(),draft.rows());
    });
  }
  @Override public Preview previewDirect(ActorContext a,String dataset,List<RecordChange> changes) {
    return call(a,()->{
      direct(a);List<SnapshotRow> rows=snapshot(a,dataset,changes,AccessPolicy.Action.DIRECT_EDIT,false);
      String org=batchOrganization(a,rows);Mode mode=Organizations.DIVISION.equals(org)?Mode.BATCH_DIRECT:Mode.DIRECT;
      return createPreview(a,mode,org,dataset,"",0,"",rows);
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
      for(var row:preview.rows()) {
        String active=scalar("SELECT stage FROM workflow_record_state WHERE record_id=?",row.before().id());
        if("BRANCH_REVIEW".equals(active)||"DIVISION_REVIEW".equals(active))throw error(Code.ALREADY_SUBMITTED,"所选记录已有待处理审核任务；请先完成或退回后再提交");
      }
      List<String> reviewers=List.of(),divisionAdmins=List.of();
      if(preview.mode()==Mode.REVIEW) {
        for(var row:preview.rows())if(scalar("SELECT submission_id FROM pending_submission_records WHERE owner_id=? AND record_id=?",a.userId(),row.before().id())!=null)
          throw error(Code.ALREADY_SUBMITTED,"本次包含本人已提交待复核的记录；请先处理原提交单");
        reviewers=reviewers(preview.organizationId());
        if(reviewers.isEmpty())throw error(Code.NO_REVIEWER,"本支行尚无有效复核员，请管理员配置后重试；草稿仍保留");
      } else if(a.role()!=Role.DIVISION_ADMIN) {
        divisionAdmins=divisionAdmins();
        if(divisionAdmins.isEmpty())throw error(Code.NO_REVIEWER,"当前没有有效分行管理员，未创建待终审任务；请联系管理员后重试");
      }
      String submissionId=id(),now=now();boolean isDirect=preview.mode()!=Mode.REVIEW;
      boolean divisionPublishes=isDirect&&a.role()==Role.DIVISION_ADMIN;
      String initialState=preview.mode()==Mode.REVIEW?"SUBMITTED":divisionPublishes?"APPROVED":"PENDING_DIVISION";
      RowStage initialStage=preview.mode()==Mode.REVIEW?RowStage.BRANCH_REVIEW:divisionPublishes?RowStage.PUBLISHED:RowStage.DIVISION_REVIEW;
      exec("INSERT INTO submissions(id,owner_id,organization_id,dataset,state,payload,created_at,reviewer_id,decided_at,decision_reason,mode,owner_name,reviewer_name,draft_id,draft_revision,prior_submission_id,preview_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        submissionId,a.userId(),preview.organizationId(),preview.dataset(),initialState,encode(preview.rows()),now,
        divisionPublishes?a.userId():null,divisionPublishes?now:null,"",preview.mode().name(),a.name(),divisionPublishes?a.name():"",preview.draftId(),preview.draftVersion(),preview.priorSubmissionId(),preview.id());
      for(var row:preview.rows()) {
        BusinessRecord r=row.before();
        exec("INSERT INTO submission_items(submission_id,record_id,period_start,period_end,workflow_state) VALUES(?,?,?,?,?)",submissionId,r.id(),java.sql.Date.valueOf(r.period().start()),java.sql.Date.valueOf(r.period().end()),initialStage.name());
        if(!isDirect)exec("INSERT INTO pending_submission_records VALUES(?,?,?)",a.userId(),r.id(),submissionId);
        setWorkflowState(a,submissionId,r.id(),a.userId(),initialStage,"",initialStage==RowStage.PUBLISHED?"DIRECT_PUBLISHED":initialStage==RowStage.DIVISION_REVIEW?"DIRECT_SENT_TO_DIVISION":"SUBMITTED",requestId,now);
      }
      checkpoint.accept("submission-written");
      if(divisionPublishes)linkAudits(submissionId,store.applyOfficialChanges(a,changes(preview.rows()),AccessPolicy.Action.DIRECT_EDIT,"DIRECT_EDIT",requestId,submissionId));
      linkAudits(submissionId,List.of(audit(a,preview.organizationId(),submissionId,isDirect?"DIRECT_SUBMIT":"SUBMIT",requestId,"","",a.userId())));
      List<String> recipients=preview.mode()==Mode.REVIEW?reviewers:divisionPublishes?List.of(a.userId()):divisionAdmins;
      String noticeType=preview.mode()==Mode.REVIEW?"SUBMITTED":divisionPublishes?"DIRECT_EDIT":"DIVISION_REVIEW_REQUIRED";
      String noticeTitle=preview.mode()==Mode.REVIEW?"填报待支行复核":divisionPublishes?"填报已终审发布":"填报待分行终审";
      emit(noticeType,preview.organizationId(),noticeTitle,summary(a,preview.dataset(),preview.rows().size()),submissionId,recipients,"submission-"+submissionId);
      exec("UPDATE workflow_previews SET result_id=? WHERE id=?",submissionId,preview.id());
      remember(a,requestId,hash,submissionId);checkpoint.accept("confirmation-complete");
      return loadSubmission(a,submissionId);
    });
  }
  /** Imported yellow-cell values remain proposals until the branch and division approve them. Caller owns the transaction. */
  List<Submission> submitImported(ActorContext a,List<SnapshotRow> proposed,String requestId)throws SQLException {
    AccessPolicy.require(a,AccessPolicy.Action.UPLOAD,Organizations.DIVISION);if(a.role()!=Role.DIVISION_ADMIN)throw new SecurityException("只有分行管理员可以提交导入数据复核");
    if(proposed.isEmpty())return List.of();Map<String,List<SnapshotRow>> groups=new LinkedHashMap<>();Set<String> seen=new HashSet<>();
    if(divisionAdmins().stream().noneMatch(id->!id.equals(a.userId())))throw error(Code.NO_REVIEWER,"导入含填报值变更，需要另一名有效分行管理员完成终审；本批未导入");
    for(SnapshotRow row:proposed){if(!seen.add(row.before().id()))throw error(Code.INVALID_INPUT,"导入复核包含重复业务行");String key=row.before().organizationId()+"\n"+row.before().dataset();groups.computeIfAbsent(key,k->new ArrayList<>()).add(row);}
    List<Submission> result=new ArrayList<>();
    for(List<SnapshotRow> rows:groups.values()){
      String org=rows.get(0).before().organizationId(),dataset=rows.get(0).before().dataset();List<String> recipients=reviewers(org);
      if(recipients.isEmpty())throw error(Code.NO_REVIEWER,Organizations.label(org)+"尚未配置有效复核员，导入中止；请配置后重新上传");
      for(SnapshotRow row:rows){BusinessRecord current=store.find(a,row.before().id());if(current.version()!=row.change().expectedVersion()||!current.values().equals(row.before().values()))throw error(Code.VERSION_CONFLICT,"导入预览后正式值已变化，本批未导入");if(current.workflowStage()==RowStage.BRANCH_REVIEW||current.workflowStage()==RowStage.DIVISION_REVIEW)throw error(Code.ALREADY_SUBMITTED,"业务行已有在途审核，本批未导入");}
      String submissionId=id(),created=now();exec("INSERT INTO submissions(id,owner_id,organization_id,dataset,state,payload,created_at,reviewer_id,decided_at,decision_reason,mode,owner_name,reviewer_name,draft_id,draft_revision,prior_submission_id,preview_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",submissionId,a.userId(),org,dataset,State.SUBMITTED.name(),encode(rows),created,null,null,"",Mode.IMPORT.name(),a.name(),"","",0,"",null);
      for(SnapshotRow row:rows){BusinessRecord record=row.before();exec("INSERT INTO submission_items(submission_id,record_id,period_start,period_end,workflow_state) VALUES(?,?,?,?,?)",submissionId,record.id(),java.sql.Date.valueOf(record.period().start()),java.sql.Date.valueOf(record.period().end()),RowStage.BRANCH_REVIEW.name());exec("INSERT INTO pending_submission_records VALUES(?,?,?)",a.userId(),record.id(),submissionId);setWorkflowState(a,submissionId,record.id(),a.userId(),RowStage.BRANCH_REVIEW,"","IMPORT_SUBMITTED",requestId,created);linkAudits(submissionId,List.of(audit(a,org,record.id(),"IMPORT_SUBMIT",requestId,"",WorkflowCodec.changes(List.of(row.change())),"导入黄色字段作为待审核提议；正式值尚未更改")));}
      checkpoint.accept("import-workflow-written");emit("SUBMITTED",org,"导入数据待支行复核",summary(a,dataset,rows.size()),submissionId,recipients,"import-review-"+submissionId);result.add(loadSubmission(a,submissionId));
    }
    return List.copyOf(result);
  }
  @Override public Submission submission(ActorContext a,String id) { return call(a,()->loadSubmission(a,id)); }
  @Override public List<Submission> submissions(ActorContext a,Query query) { return call(a,()->listSubmissions(a,query,null)); }
  @Override public List<Submission> pendingReviews(ActorContext a,Query query) {
    return call(a,()->{AccessPolicy.require(a,AccessPolicy.Action.REVIEW,a.organizationId());return listSubmissions(a,query,State.SUBMITTED);});
  }
  @Override public List<Submission> pendingDivisionReviews(ActorContext a,Query query) {
    return call(a,()->{AccessPolicy.require(a,AccessPolicy.Action.DIVISION_REVIEW,Organizations.DIVISION);return listSubmissions(a,query,State.PENDING_DIVISION);});
  }
  @Override public List<Submission> recordHistory(ActorContext a,String recordId,int offset,int limit) {
    return call(a,()->{
      store.find(a,recordId);page(offset,limit);List<Submission> result=new ArrayList<>();
      try(PreparedStatement st=statement("SELECT s.* FROM submissions s JOIN submission_items i ON i.submission_id=s.id WHERE i.record_id=? ORDER BY s.created_at DESC,s.id LIMIT ? OFFSET ?",recordId,limit,offset);ResultSet rs=st.executeQuery()) {
        while(rs.next())result.add(visibleSubmission(a,readSubmission(rs)));
      }
      return List.copyOf(result);
    });
  }
  @Override public List<AuditEntry> auditTrail(ActorContext a,String submissionId) {
    return call(a,()->{
      Submission submission=loadSubmission(a,submissionId);List<AuditEntry> result=new ArrayList<>();
      String sql="SELECT e.* FROM audit_events e JOIN workflow_audit_links l ON l.event_id=e.id WHERE l.submission_id=?"+(a.role()==Role.SUPER_ADMIN?"":" AND e.actor_role<>'SUPER_ADMIN'")+" ORDER BY e.event_at,e.id";
      try(PreparedStatement st=statement(sql,submissionId);ResultSet rs=st.executeQuery()) {
        while(rs.next()) {
          String org=rs.getString("organization_id");
          if(AccessPolicy.can(a,AccessPolicy.Action.VIEW,org))result.add(new AuditEntry(rs.getString("id"),Instant.parse(rs.getString("event_at")),rs.getString("actor_id"),rs.getString("actor_name"),rs.getString("actor_role"),rs.getString("record_id"),rs.getString("action"),rs.getString("request_id"),rs.getString("before_data"),rs.getString("after_data"),rs.getString("details")));
        }
      }
      return List.copyOf(result);
    });
  }
  @Override public Submission approve(ActorContext a,String id,String requestId) { return decide(a,id,null,"",requestId,true); }
  @Override public Submission reject(ActorContext a,String id,String reason,String requestId) { return decide(a,id,null,reason,requestId,false); }
  @Override public Submission approveRows(ActorContext a,String id,List<String> recordIds,String requestId) { return decide(a,id,recordIds,"",requestId,true); }
  @Override public Submission rejectRows(ActorContext a,String id,List<String> recordIds,String reason,String requestId) { return decide(a,id,recordIds,reason,requestId,false); }
  @Override public String reopenCompleted(ActorContext a,String recordId,long expectedVersion,String reason,String requestId) {
    return call(a,()->{
      AccessPolicy.require(a,AccessPolicy.Action.DIVISION_REVIEW,Organizations.DIVISION);
      String explanation=requiredText(reason,2000,"请填写 1～2000 字终审退回原因");
      String hash=hash("REOPEN_COMPLETED",recordId,Long.toString(expectedVersion),explanation);
      String repeated=repeat(a,requestId,hash);if(repeated!=null)return repeated;
      BusinessRecord row=store.find(a,recordId);
      if(row.workflowStage()!=RowStage.PUBLISHED||!store.completionRules().visible(a).get(row.dataset()).complete(row.values()))throw error(Code.ALREADY_DECIDED,"只能重新打开当前正式已完成的行");
      if(row.version()!=expectedVersion)throw error(Code.VERSION_CONFLICT,"正式版本已变化，请刷新后重新核对");
      String previous=scalar("SELECT submission_id FROM workflow_record_state WHERE record_id=?",recordId);
      String now=now(),eventId=id();
      exec("UPDATE workflow_record_state SET stage='RETURNED',reason=?,updated_at=? WHERE record_id=?",explanation,now,recordId);
      if(previous!=null)exec("UPDATE submission_items SET workflow_state='RETURNED' WHERE submission_id=? AND record_id=?",previous,recordId);
      exec("INSERT INTO workflow_item_events VALUES(?,?,?,?,?,?,?,?,?,?,?)",eventId,previous,recordId,RowStage.RETURNED.name(),"DIVISION_REOPENED",a.userId(),a.name(),a.role().name(),requestId,explanation,now);
      String auditId=audit(a,row.organizationId(),recordId,"DIVISION_REOPENED",requestId,Codec.encode(row.values()),Codec.encode(row.values()),explanation);
      if(previous!=null)linkAudits(previous,List.of(auditId));
      List<String> recipients=new ArrayList<>();try(PreparedStatement st=statement("SELECT id FROM users WHERE active=TRUE AND organization_id=? AND role IN ('OPERATOR','BRANCH_ADMIN','REVIEWER') ORDER BY role,id",row.organizationId());ResultSet rs=st.executeQuery()){while(rs.next())recipients.add(rs.getString(1));}
      emit("DIVISION_REOPENED",row.organizationId(),"已终审记录退回支行待处理",summary(a,row.dataset(),1),previous==null?"":previous,recipients,"reopen-"+recordId+"-"+row.version()+"-"+hash.substring(0,20));
      checkpoint.accept("completed-row-reopened");remember(a,requestId,hash,eventId);return eventId;
    });
  }
  private Submission decide(ActorContext a,String id,List<String> recordIds,String reason,String requestId,boolean approve) {
    return call(a,()->{
      Submission submission=loadSubmission(a,id);
      boolean branchReview=a.role()==Role.REVIEWER;
      boolean divisionReview=a.role()==Role.DIVISION_ADMIN;
      if(branchReview)AccessPolicy.require(a,AccessPolicy.Action.REVIEW,submission.organizationId());
      else if(divisionReview)AccessPolicy.require(a,AccessPolicy.Action.DIVISION_REVIEW,Organizations.DIVISION);
      else throw new SecurityException("当前账号无权审核填报");
      if(a.userId().equals(submission.ownerId()))throw new SecurityException("不能审核本人提交");
      String explanation=approve?"":requiredText(reason,2000,"请填写 1～2000 字退回原因");
      String stage=branchReview?"BRANCH":"DIVISION";
      List<SnapshotRow> selected=selectedRows(submission,recordIds);
      String selectedKey=String.join(",",selected.stream().map(r->r.before().id()).sorted().toList());
      String hash=hash(stage,approve?"APPROVE":"REJECT",id,selectedKey,explanation);
      String repeated=repeat(a,requestId,hash);if(repeated!=null)return loadSubmission(a,repeated);
      RowStage expected=branchReview?RowStage.BRANCH_REVIEW:RowStage.DIVISION_REVIEW;
      if(branchReview&&submission.mode()!=Mode.REVIEW&&submission.mode()!=Mode.IMPORT)throw error(Code.ALREADY_DECIDED,"该提交单不属于支行复核流程");
      for(var row:selected)if(submission.rowStages().get(row.before().id())!=expected)throw error(Code.ALREADY_DECIDED,"选择的记录已不在当前审核阶段，请刷新并重新选择");
      List<String> recipients=List.of(submission.ownerId());
      if(branchReview&&approve) {
        if(submission.mode()!=Mode.IMPORT){UserAccount owner=store.sessionUser(submission.ownerId());if(owner==null||!owner.active()||owner.role()!=Role.OPERATOR||!owner.organizationId().equals(submission.organizationId()))throw error(Code.OWNER_CHANGED,"提交人的账号或机构权限已变化；请退回并重新核对，不得直接批准");}
        validateSnapshot(a,selected,AccessPolicy.Action.REVIEW);
        recipients=divisionAdmins();
        if(recipients.isEmpty())throw error(Code.NO_REVIEWER,"当前没有有效分行管理员，待办保持原状；请联系管理员");
      } else if(divisionReview&&approve) {
        if(submission.mode()!=Mode.IMPORT){UserAccount owner=store.sessionUser(submission.ownerId());if(owner==null||!owner.active()||!owner.organizationId().equals(submission.organizationId())||!Set.of(Role.OPERATOR,Role.BRANCH_ADMIN,Role.REVIEWER).contains(owner.role()))throw error(Code.OWNER_CHANGED,"提交人的账号或机构权限已变化；请退回并重新核对，不得直接发布");}
        validateSnapshot(a,selected,AccessPolicy.Action.DIRECT_EDIT,true);
        linkAudits(id,store.applyOfficialChanges(a,changes(selected),AccessPolicy.Action.DIRECT_EDIT,"DIVISION_APPROVED",requestId,id+";submitter="+submission.ownerId()));
      }
      RowStage next=approve?(branchReview?RowStage.DIVISION_REVIEW:RowStage.PUBLISHED):RowStage.RETURNED;
      String action=branchReview?(approve?"BRANCH_APPROVED":"BRANCH_RETURNED"):(approve?"DIVISION_APPROVED":"DIVISION_RETURNED");
      String decidedAt=now();
      for(var row:selected) {
        setWorkflowState(a,id,row.before().id(),submission.ownerId(),next,explanation,action,requestId,decidedAt);
        if(branchReview)exec("DELETE FROM pending_submission_records WHERE owner_id=? AND record_id=? AND submission_id=?",submission.ownerId(),row.before().id(),id);
      }
      State aggregate=aggregateState(id);
      if(branchReview)exec("UPDATE submissions SET state=?,reviewer_id=?,reviewer_name=?,decided_at=?,decision_reason=? WHERE id=?",aggregate.name(),a.userId(),a.name(),decidedAt,explanation,id);
      else exec("UPDATE submissions SET state=?,decided_at=?,decision_reason=? WHERE id=?",aggregate.name(),decidedAt,explanation,id);
      List<String> auditIds=new ArrayList<>();for(var row:selected)auditIds.add(audit(a,row.before().organizationId(),row.before().id(),action,requestId,"","",explanation));linkAudits(id,auditIds);
      checkpoint.accept("decision-written");
      String noticeType=branchReview?(approve?"BRANCH_APPROVED":"BRANCH_RETURNED"):(approve?"DIVISION_APPROVED":"DIVISION_RETURNED");
      String noticeTitle=branchReview?(approve?"支行复核通过，待分行终审":"支行已退回修改"):(approve?"分行终审通过并发布":"分行终审退回修改");
      emit(noticeType,submission.organizationId(),noticeTitle,summary(a,submission.dataset(),selected.size()),id,recipients,"decision-"+id+"-"+hash.substring(0,24));
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
    Set<String> seen=new HashSet<>();List<SnapshotRow> result=new ArrayList<>();List<Conflict> conflicts=new ArrayList<>();String org=null;boolean multiDirect=action==AccessPolicy.Action.DIRECT_EDIT&&a.role()==Role.DIVISION_ADMIN;
    for(RecordChange change:changes) {
      if(!seen.add(change.recordId()))throw error(Code.INVALID_INPUT,"一次修改不能包含重复记录");
      BusinessRecord current=store.find(a,change.recordId());AccessPolicy.require(a,action,current.organizationId());
      if(!current.dataset().equals(dataset))throw error(Code.INVALID_INPUT,"一单只能修改同一种表格");
      if(action==AccessPolicy.Action.SAVE_DRAFT||action==AccessPolicy.Action.DIRECT_EDIT)requireEditableStage(a,current);
      if(org!=null&&!org.equals(current.organizationId())&&!multiDirect)throw error(Code.INVALID_INPUT,"一单只能包含同一家支行，请分单提交");
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
  private String batchOrganization(ActorContext a,List<SnapshotRow> rows) {
    if(a.role()==Role.DIVISION_ADMIN&&rows.stream().map(r->r.before().organizationId()).distinct().count()>1)return Organizations.DIVISION;
    return rows.get(0).before().organizationId();
  }
  private void validateSnapshot(ActorContext a,List<SnapshotRow> rows,AccessPolicy.Action action) {
    validateSnapshot(a,rows,action,false);
  }
  private void validateSnapshot(ActorContext a,List<SnapshotRow> rows,AccessPolicy.Action action,boolean finalDecision) {
    requireRows(rows);List<Conflict> conflicts=new ArrayList<>();
    for(var row:rows) {
      BusinessRecord current=store.find(a,row.before().id());AccessPolicy.require(a,action,current.organizationId());
      if(!current.organizationId().equals(row.before().organizationId())||!current.dataset().equals(row.before().dataset()))throw new SecurityException("记录归属已变化，请重新核对");
      if(current.version()!=row.change().expectedVersion()||!current.values().equals(row.before().values())){conflicts.add(new Conflict(row.before(),current,row.change()));continue;}
      if(action==AccessPolicy.Action.REVIEW&&current.workflowStage()!=RowStage.BRANCH_REVIEW)throw error(Code.ALREADY_DECIDED,"所选记录已不在支行复核阶段");
      if(action==AccessPolicy.Action.SUBMIT||action==AccessPolicy.Action.DIRECT_EDIT&&!finalDecision)requireEditableStage(a,current);
      if(finalDecision&&current.workflowStage()!=RowStage.DIVISION_REVIEW)throw error(Code.ALREADY_DECIDED,"所选记录已不在分行终审阶段");
      DatasetSchema schema=DatasetSchema.get(current.dataset());for(var field:row.change().values().entrySet())schema.validateEdit(schema.index(field.getKey()),field.getValue());
    }
    if(!conflicts.isEmpty())throw conflicts(conflicts);
  }
  private void requireEditableStage(ActorContext actor,BusinessRecord record) {
    RowStage stage=record.workflowStage();
    if(stage==RowStage.BRANCH_REVIEW||stage==RowStage.DIVISION_REVIEW)throw error(Code.ALREADY_SUBMITTED,"该行已有审核任务，不能通过旧表单或其他入口修改");
    if(actor.role()==Role.DIVISION_ADMIN) {
      if(stage==RowStage.READY||stage==RowStage.RETURNED||stage==RowStage.PUBLISHED||stage==RowStage.LEGACY_PUBLISHED)return;
    } else if(Set.of(Role.OPERATOR,Role.BRANCH_ADMIN,Role.REVIEWER).contains(actor.role())) {
      if(stage==RowStage.READY||stage==RowStage.RETURNED)return;
      if((stage==RowStage.PUBLISHED||stage==RowStage.LEGACY_PUBLISHED)&&!store.completionRules().visible(actor).get(record.dataset()).complete(record.values()))return;
    }
    throw error(Code.ALREADY_DECIDED,"该记录不在当前角色可处理范围");
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
      if(!rs.next())throw new SecurityException("草稿不存在或无权访问");
      return readDraft(rs);
    }
  }
  private boolean isConsumed(ActorContext a,Draft draft)throws SQLException {
    String consumed=scalar("SELECT COUNT(*) FROM submissions WHERE owner_id=? AND draft_id=? AND draft_revision=? AND state IN ('SUBMITTED','PENDING_DIVISION','PARTIAL','APPROVED')",a.userId(),draft.id(),draft.version());
    return consumed!=null&&Integer.parseInt(consumed)>0;
  }
  private Draft readDraft(ResultSet rs)throws SQLException {
    String returned=scalar("SELECT id FROM submissions WHERE owner_id=? AND draft_id=? AND draft_revision=? AND state='RETURNED' ORDER BY decided_at DESC,id LIMIT 1",rs.getString("owner_id"),rs.getString("id"),rs.getLong("revision"));
    return new Draft(rs.getString("id"),rs.getString("owner_id"),rs.getString("organization_id"),rs.getString("dataset"),rs.getLong("revision"),Instant.parse(rs.getString("updated_at")),returned==null?rs.getString("prior_submission_id"):returned,WorkflowCodec.rows(rs.getString("payload")));
  }
  private Submission loadSubmission(ActorContext a,String id)throws SQLException {
    try(PreparedStatement st=statement("SELECT * FROM submissions WHERE id=?",id);ResultSet rs=st.executeQuery()) {
      if(!rs.next())throw new SecurityException("提交单不存在或无权访问");
      return visibleSubmission(a,readSubmission(rs));
    }
  }
  private Submission readSubmission(ResultSet rs)throws SQLException {
    String id=rs.getString("id");Map<String,RowStage> rowStages=new LinkedHashMap<>();
    try(PreparedStatement st=statement("SELECT record_id,workflow_state FROM submission_items WHERE submission_id=? ORDER BY record_id",id);ResultSet items=st.executeQuery()) {
      while(items.next())rowStages.put(items.getString("record_id"),RowStage.valueOf(items.getString("workflow_state")));
    }
    return new Submission(id,Mode.valueOf(rs.getString("mode")),State.valueOf(rs.getString("state")),rs.getString("owner_id"),rs.getString("owner_name"),rs.getString("organization_id"),rs.getString("dataset"),rs.getString("draft_id"),rs.getLong("draft_revision"),rs.getString("prior_submission_id"),Instant.parse(rs.getString("created_at")),blank(rs.getString("reviewer_id")),rs.getString("reviewer_name"),instant(rs.getString("decided_at")),blank(rs.getString("decision_reason")),WorkflowCodec.rows(rs.getString("payload")),rowStages);
  }
  private List<Submission> listSubmissions(ActorContext a,Query query,State pendingState)throws SQLException {
    if(query==null)throw error(Code.INVALID_INPUT,"缺少查询条件");page(query.offset(),query.limit());
    if(query.from()!=null&&query.through()!=null&&query.from().isAfter(query.through()))throw error(Code.INVALID_INPUT,"时间区间无效");
    String sql="SELECT s.* FROM submissions s WHERE 1=1";List<Object> args=new ArrayList<>();
    if(!AccessPolicy.all(a)){sql+=" AND EXISTS (SELECT 1 FROM submission_items scope_i JOIN official_records scope_o ON scope_o.id=scope_i.record_id WHERE scope_i.submission_id=s.id AND scope_o.organization_id=?)";args.add(a.organizationId());}
    if(!blank(query.organization()).isEmpty()) {
      if(!Organizations.BRANCHES.containsKey(query.organization()))throw error(Code.INVALID_INPUT,"查询机构无效");
      AccessPolicy.require(a,AccessPolicy.Action.VIEW,query.organization());
      if(AccessPolicy.all(a)) {
        sql+=" AND (s.organization_id=? OR EXISTS (SELECT 1 FROM submission_items scoped_i JOIN official_records scoped_o ON scoped_o.id=scoped_i.record_id WHERE scoped_i.submission_id=s.id AND scoped_o.organization_id=?))";
        args.add(query.organization());args.add(query.organization());
      } else { sql+=" AND EXISTS (SELECT 1 FROM submission_items scoped_i JOIN official_records scoped_o ON scoped_o.id=scoped_i.record_id WHERE scoped_i.submission_id=s.id AND scoped_o.organization_id=?)";args.add(query.organization()); }
    }
    if(!blank(query.dataset()).isEmpty()){DatasetSchema.get(query.dataset());sql+=" AND s.dataset=?";args.add(query.dataset());}
    if(pendingState!=null) {
      String stage=pendingState==State.SUBMITTED?RowStage.BRANCH_REVIEW.name():RowStage.DIVISION_REVIEW.name();
      sql+=" AND EXISTS (SELECT 1 FROM submission_items pending_i WHERE pending_i.submission_id=s.id AND pending_i.workflow_state=?";
      args.add(stage);
      if(!AccessPolicy.all(a)){sql+=" AND EXISTS (SELECT 1 FROM official_records pending_o WHERE pending_o.id=pending_i.record_id AND pending_o.organization_id=?)";args.add(a.organizationId());}
      sql+=")";
    } else if(query.state()!=null){sql+=" AND s.state=?";args.add(query.state().name());}
    if(query.mineOnly()){sql+=" AND s.owner_id=?";args.add(a.userId());}
    if(query.from()!=null||query.through()!=null) {
      sql+=" AND EXISTS (SELECT 1 FROM submission_items i WHERE i.submission_id=s.id";
      if(query.from()!=null){sql+=" AND i.period_end>=?";args.add(java.sql.Date.valueOf(query.from()));}
      if(query.through()!=null){sql+=" AND i.period_start<=?";args.add(java.sql.Date.valueOf(query.through()));}
      sql+=")";
    }
    sql+=" ORDER BY s.created_at DESC,s.id LIMIT ? OFFSET ?";args.add(query.limit());args.add(query.offset());
    List<Submission> result=new ArrayList<>();try(PreparedStatement st=statement(sql,args.toArray());ResultSet rs=st.executeQuery()){while(rs.next())result.add(visibleSubmission(a,readSubmission(rs)));}
    return List.copyOf(result);
  }
  private Submission visibleSubmission(ActorContext a,Submission submission) {
    if(AccessPolicy.can(a,AccessPolicy.Action.VIEW,submission.organizationId()))return submission;
    List<SnapshotRow> rows=submission.rows().stream().filter(row->AccessPolicy.can(a,AccessPolicy.Action.VIEW,row.before().organizationId())).toList();
    if(rows.isEmpty())throw new SecurityException("提交单不存在或无权访问");
    Set<String> visible=rows.stream().map(row->row.before().id()).collect(java.util.stream.Collectors.toSet());
    Map<String,RowStage> rowStages=new LinkedHashMap<>();submission.rowStages().forEach((recordId,stage)->{if(visible.contains(recordId))rowStages.put(recordId,stage);});
    return new Submission(submission.id(),submission.mode(),submission.state(),submission.ownerId(),submission.ownerName(),a.organizationId(),submission.dataset(),submission.draftId(),submission.draftVersion(),submission.priorSubmissionId(),submission.createdAt(),submission.reviewerId(),submission.reviewerName(),submission.decidedAt(),submission.reason(),rows,rowStages);
  }
  private static List<SnapshotRow> selectedRows(Submission submission,List<String> requested) {
    List<String> ids=requested==null?submission.rows().stream().map(row->row.before().id()).toList():List.copyOf(requested);
    if(ids.isEmpty()||ids.size()>200||new HashSet<>(ids).size()!=ids.size())throw error(Code.INVALID_INPUT,"请明确选择 1～200 条且不重复的记录");
    Map<String,SnapshotRow> available=new HashMap<>();for(var row:submission.rows())available.put(row.before().id(),row);
    List<SnapshotRow> result=new ArrayList<>();for(String recordId:ids){SnapshotRow row=available.get(recordId);if(row==null)throw error(Code.INVALID_INPUT,"选择的记录不属于当前可见提交单");result.add(row);}
    return List.copyOf(result);
  }
  private State aggregateState(String submissionId)throws SQLException {
    int total=0,branch=0,division=0,returned=0,published=0;
    try(PreparedStatement st=statement("SELECT workflow_state,COUNT(*) FROM submission_items WHERE submission_id=? GROUP BY workflow_state",submissionId);ResultSet rs=st.executeQuery()) {
      while(rs.next()){int n=rs.getInt(2);total+=n;switch(RowStage.valueOf(rs.getString(1))){case BRANCH_REVIEW->branch+=n;case DIVISION_REVIEW->division+=n;case RETURNED->returned+=n;case PUBLISHED,LEGACY_PUBLISHED->published+=n;case READY->{}}}
    }
    if(total==0)throw error(Code.INVALID_INPUT,"提交单不包含业务行");
    if(branch==total)return State.SUBMITTED;if(division==total)return State.PENDING_DIVISION;if(returned==total)return State.RETURNED;if(published==total)return State.APPROVED;return State.PARTIAL;
  }
  private void checkPrior(ActorContext a,String prior,String dataset,String org)throws SQLException {
    if(prior.isEmpty())return;Submission old=loadSubmission(a,prior);
    if(!old.ownerId().equals(a.userId())||old.state()!=State.RETURNED||!old.dataset().equals(dataset)||!old.organizationId().equals(org))
      throw error(Code.INVALID_INPUT,"只能关联本人同机构、同表格的已退回提交单");
  }
  private List<String> reviewers(String org)throws SQLException {
    List<String> result=new ArrayList<>();try(PreparedStatement st=statement("SELECT id FROM users WHERE active=TRUE AND role='REVIEWER' AND organization_id=? ORDER BY id",org);ResultSet rs=st.executeQuery()){while(rs.next())result.add(rs.getString(1));}return result;
  }
  private List<String> divisionAdmins()throws SQLException {
    List<String> result=new ArrayList<>();try(PreparedStatement st=statement("SELECT id FROM users WHERE active=TRUE AND role='DIVISION_ADMIN' ORDER BY id");ResultSet rs=st.executeQuery()){while(rs.next())result.add(rs.getString(1));}return result;
  }
  private void setWorkflowState(ActorContext actor,String submissionId,String recordId,String ownerId,RowStage stage,String reason,String action,String requestId,String at)throws SQLException {
    exec("UPDATE submission_items SET workflow_state=? WHERE submission_id=? AND record_id=?",stage.name(),submissionId,recordId);
    exec("MERGE INTO workflow_record_state(record_id,submission_id,owner_id,stage,reason,updated_at) KEY(record_id) VALUES(?,?,?,?,?,?)",recordId,submissionId,ownerId,stage.name(),reason,at);
    exec("INSERT INTO workflow_item_events VALUES(?,?,?,?,?,?,?,?,?,?,?)",id(),submissionId,recordId,stage.name(),action,actor.userId(),actor.name(),actor.role().name(),requestId,reason,at);
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
  private static void checkMode(ActorContext a,Mode mode,String org){if(mode==Mode.BATCH_DIRECT&&a.role()!=Role.DIVISION_ADMIN)throw new SecurityException("批量跨支行修改仅限分行管理员");AccessPolicy.require(a,mode==Mode.REVIEW?AccessPolicy.Action.SUBMIT:AccessPolicy.Action.DIRECT_EDIT,org);}
  private static void requireRows(List<SnapshotRow> rows){if(rows.isEmpty())throw error(Code.INVALID_INPUT,"没有实际变化的字段，无需提交");}
  private static List<RecordChange> changes(List<SnapshotRow> rows){return rows.stream().map(SnapshotRow::change).toList();}
  private static String summary(ActorContext a,String dataset,int count){return a.name()+" · "+DatasetSchema.get(dataset).label+" · "+count+" 条；详情需登录核验权限";}
  private static WorkflowException conflicts(List<Conflict> conflicts){return new WorkflowException(Code.VERSION_CONFLICT,"正式记录已变化，本次整单未生效，请核对原值、当前值和拟提交值",conflicts);}
  private static WorkflowException error(Code code,String message){return new WorkflowException(code,message);}
}

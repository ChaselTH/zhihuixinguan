package xinguan.platform;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static xinguan.platform.WorkflowContracts.*;

/** Real persisted synthetic identities, isolated H2 databases, no production configuration. */
public final class WorkflowPlatformTest {
  private static int assertions;
  public static void main(String[] args)throws Exception {
    try(Fixture f=new Fixture()) {
      draftsAndConfirmation(f);approvalAndNotifications(f);conflictsAndReturns(f);directAndQueries(f);
      rollback(f);concurrency(f);identityChanges(f);restart(f);
    }
    migrations();
    System.out.println("WORKFLOW_PLATFORM_OK assertions="+assertions+" real synthetic identities, concurrent requests, injected failures, restart and V1 upgrade");
  }
  static void draftsAndConfirmation(Fixture f)throws Exception {
    var r=f.record("WUJIN","multi");String privateText="PRIVATE_DRAFT_ONLY_测试字段";
    var change=edit(r,privateText);String request=id();
    var draft=f.w().saveDraft(f.op,"",0,"multi",List.of(change),"",request);
    check(draft.version()==1&&draft.ownerId().equals(f.op.userId()),"owner and draft version");
    check(f.w().saveDraft(f.op,"",0,"multi",List.of(change),"",request).id().equals(draft.id()),"save retry stable id");
    code(Code.REQUEST_REUSED,()->f.w().saveDraft(f.op,"",0,"multi",List.of(edit(r,"changed request")),"",request));
    denied(()->f.w().draft(f.op2,draft.id()));denied(()->f.w().draft(f.root,draft.id()));denied(()->f.w().draft(f.div,draft.id()));
    denied(()->f.w().draft(f.otherOp,draft.id()));denied(()->f.w().saveDraft(f.op2,draft.id(),1,"multi",List.of(change),"",id()));
    check(f.w().drafts(f.op2,null,0,50).isEmpty(),"same branch private list");
    check(f.w().drafts(f.op,"multi",0,50).stream().anyMatch(d->d.id().equals(draft.id())),"own draft list");
    check(!f.store.find(f.op,r.id()).complete(),"draft not official completion");
    check(f.store.auditEvents(f.root,200).stream().noneMatch(e->(e.before()+e.after()).contains(privateText)),"draft values absent from shared audit");
    code(Code.VERSION_CONFLICT,()->f.w().saveDraft(f.op,draft.id(),0,"multi",List.of(change),"",id()));
    var preview=f.w().previewDraft(f.op,draft.id(),1);
    check(preview.rows().get(0).fields().get(0).before().isEmpty(),"server produces before diff");
    check(preview.rows().get(0).fields().get(0).after().equals(privateText),"server produces after diff");
    check(f.w().preview(f.op,preview.id()).rows().equals(preview.rows()),"stored preview can be restored");
    denied(()->f.w().preview(f.op2,preview.id()));denied(()->f.w().confirm(f.op2,preview.id(),id()));
    check(f.w().submissions(f.op,Query.firstPage()).isEmpty(),"preview creates no submission");
    f.w().saveDraft(f.op,draft.id(),1,"multi",List.of(edit(r,"draft changed")),"",id());
    code(Code.VERSION_CONFLICT,()->f.w().confirm(f.op,preview.id(),id()));
    var latest=f.w().previewDraft(f.op,draft.id(),2);f.clock.advance(Duration.ofMinutes(16));
    code(Code.CONFIRMATION_EXPIRED,()->f.w().confirm(f.op,latest.id(),id()));
    check(f.w().draft(f.op,draft.id()).rows().get(0).change().values().get("feedback").equals("draft changed"),"expired confirm preserves draft");
    var other=f.record("JINTAN","multi");denied(()->f.w().saveDraft(f.op,"",0,"multi",List.of(edit(other,"cross")),"",id()));
    code(Code.INVALID_INPUT,()->f.w().saveDraft(f.op,"",0,"multi",List.of(new RecordChange(r.id(),1,Map.of("customer_name","tamper"))),"",id()));
    code(Code.INVALID_INPUT,()->f.w().saveDraft(f.op,"",0,"multi",List.of(new RecordChange(r.id(),1,Map.of("default_risk","maybe"))),"",id()));
    code(Code.INVALID_INPUT,()->f.w().saveDraft(f.op,"",0,"multi",List.of(change,change),"",id()));
    var empty=f.w().saveDraft(f.op,"",0,"multi",List.of(),"",id());
    check(empty.rows().isEmpty(),"empty draft persists");code(Code.INVALID_INPUT,()->f.w().previewDraft(f.op,empty.id(),1));
    denied(()->f.w().drafts(new ActorContext("fake","fake",Role.OPERATOR,"WUJIN"),null,0,10));
    denied(()->f.w().drafts(null,null,0,10));
    ActorContext precise=f.user(Role.OPERATOR,"WUJIN");
    var older=f.w().saveDraft(precise,"",0,"multi",List.of(change),"",id());
    f.clock.advance(Duration.ofMillis(1));var newer=f.w().saveDraft(precise,"",0,"multi",List.of(change),"",id());
    check(f.w().drafts(precise,null,0,2).get(0).id().equals(newer.id())&&newer.updatedAt().isAfter(older.updatedAt()),"fractional timestamp ordering remains chronological");
  }
  static void approvalAndNotifications(Fixture f)throws Exception {
    var r=f.record("WUJIN","negative");var d=f.save(f.op,r,"原始提交");
    long notices=f.n().unreadCount(f.review),otherNotices=f.n().unreadCount(f.otherReview);
    var preview=f.w().previewDraft(f.op,d.id(),1);String request=id();var submitted=f.w().confirm(f.op,preview.id(),request);
    check(submitted.state()==State.SUBMITTED&&submitted.mode()==Mode.REVIEW,"confirmation creates review submission");
    check(f.n().unreadCount(f.review)==notices+1&&f.n().unreadCount(f.review2)>0,"all branch reviewers notified");
    check(f.n().unreadCount(f.otherReview)==otherNotices,"other branch not notified");
    check(f.w().confirm(f.op,preview.id(),request).id().equals(submitted.id()),"same confirm idempotent");
    check(f.w().confirm(f.op,preview.id(),id()).id().equals(submitted.id()),"same preview different request no duplicate");
    check(f.n().unreadCount(f.review)==notices+1,"repeat confirmation no duplicate notification");
    f.w().saveDraft(f.op,d.id(),1,"negative",List.of(edit(r,"之后的私人草稿")),"",id());
    check(f.w().submission(f.review,submitted.id()).rows().get(0).change().values().get("feedback").equals("原始提交"),"submitted snapshot immutable after draft edit");
    var next=f.w().previewDraft(f.op,d.id(),2);code(Code.ALREADY_SUBMITTED,()->f.w().confirm(f.op,next.id(),id()));
    check(!f.store.find(f.op,r.id()).complete(),"pending not complete");
    var notice=f.n().inbox(f.review,false,0,100).stream().filter(n->n.submissionId().equals(submitted.id())).findFirst().orElseThrow();
    denied(()->f.n().markRead(f.op2,notice.id()));f.n().markRead(f.review,notice.id());f.n().markRead(f.review,notice.id());
    check(f.n().unreadCount(f.review)==notices,"own read idempotent");
    check(f.n().inbox(f.review2,true,0,100).stream().anyMatch(n->n.id().equals(notice.id())),"another recipient keeps independent unread state");
    check(f.w().pendingReviews(f.review,Query.firstPage()).stream().anyMatch(s->s.id().equals(submitted.id())),"read notice does not finish task");
    denied(()->f.w().approve(f.branch,submitted.id(),id()));denied(()->f.w().approve(f.div,submitted.id(),id()));denied(()->f.w().approve(f.otherReview,submitted.id(),id()));
    String approveRequest=id();var approved=f.w().approve(f.review,submitted.id(),approveRequest);
    check(approved.state()==State.APPROVED&&approved.reviewerId().equals(f.review.userId()),"approved with reviewer identity");
    check(f.store.find(f.op,r.id()).version()==2&&f.store.find(f.op,r.id()).complete(),"approval becomes official exactly once");
    check(f.w().approve(f.review,submitted.id(),approveRequest).id().equals(submitted.id()),"same approval retry success");
    code(Code.ALREADY_DECIDED,()->f.w().approve(f.review2,submitted.id(),id()));
    check(f.w().pendingReviews(f.review,Query.firstPage()).stream().noneMatch(s->s.id().equals(submitted.id())),"approved leaves pending queue");
    check(f.n().inbox(f.op,true,0,100).stream().anyMatch(n->n.type().equals("APPROVED")&&n.submissionId().equals(submitted.id())),"submitter gets decision");
    var audit=f.w().auditTrail(f.root,submitted.id());
    check(audit.stream().anyMatch(e->e.actorId().equals(f.op.userId())&&e.action().equals("SUBMIT")),"audit retains submitter");
    check(audit.stream().anyMatch(e->e.actorId().equals(f.review.userId())&&e.action().equals("REVIEW_APPROVED")&&!e.after().isEmpty()),"audit retains approver and values");
    denied(()->f.w().auditTrail(f.otherOp,submitted.id()));
    check(f.w().recordHistory(f.op,r.id(),0,50).size()==1,"record trace only official submission not private draft");
    f.store.publishDirect(f.branch,List.of(edit(f.store.find(f.op,r.id()),"之后正式修改")),id());
    f.w().approve(f.review,submitted.id(),approveRequest);
    check(value(f.store.find(f.op,r.id())).equals("之后正式修改"),"approval replay never overwrites later changes");
  }
  static void conflictsAndReturns(Fixture f)throws Exception {
    BusinessRecord a=f.record("WUJIN","multi"),b=f.record("WUJIN","multi");
    var d=f.w().saveDraft(f.op,"",0,"multi",List.of(edit(a,"A changed"),edit(b,"B changed")),"",id());
    var s=f.w().confirm(f.op,f.w().previewDraft(f.op,d.id(),1).id(),id());
    f.store.publishDirect(f.branch,List.of(edit(b,"concurrent official")),id());
    int auditCount=f.store.diagnostics().get("audit_events");long notices=f.n().unreadCount(f.op);
    var conflict=code(Code.VERSION_CONFLICT,()->f.w().approve(f.review,s.id(),id()));
    check(conflict.conflicts().size()==1&&conflict.conflicts().get(0).base()!=null,"three-way conflict data");
    check(conflict.conflicts().get(0).current().version()==2,"conflict current version");
    check(!f.store.find(f.op,a.id()).complete()&&value(f.store.find(f.op,b.id())).equals("concurrent official"),"whole conflict batch not applied");
    check(f.store.diagnostics().get("audit_events")==auditCount&&f.n().unreadCount(f.op)==notices,"conflict has no success audit or notices");
    code(Code.INVALID_INPUT,()->f.w().reject(f.review,s.id(),"  ",id()));
    String request=id();var returned=f.w().reject(f.review,s.id(),"请核对最新数据",request);
    check(returned.state()==State.RETURNED&&returned.reason().equals("请核对最新数据"),"return reason saved");
    check(f.w().reject(f.review,s.id(),"请核对最新数据",request).id().equals(s.id()),"return idempotent");
    code(Code.REQUEST_REUSED,()->f.w().reject(f.review,s.id(),"different",request));
    var again=f.w().saveDraft(f.op,"",0,"multi",List.of(edit(f.store.find(f.op,b.id()),"核实后提交")),s.id(),id());
    var resubmitted=f.w().confirm(f.op,f.w().previewDraft(f.op,again.id(),1).id(),id());
    check(resubmitted.priorSubmissionId().equals(s.id())&&!resubmitted.id().equals(s.id()),"return starts a linked new immutable submission");
    check(f.w().submission(f.op,s.id()).state()==State.RETURNED,"old decision unchanged");
    code(Code.INVALID_INPUT,()->f.w().saveDraft(f.op2,"",0,"multi",List.of(edit(f.store.find(f.op,b.id()),"steal")),s.id(),id()));
    var record=f.record("WUJIN","cross");var old=f.save(f.op,record,"saved before import");
    f.store.publishDirect(f.branch,List.of(edit(record,"new import equivalent")),id());
    code(Code.VERSION_CONFLICT,()->f.w().previewDraft(f.op,old.id(),1));
  }
  static void directAndQueries(Fixture f)throws Exception {
    for(ActorContext actor:List.of(f.div,f.branch,f.review))for(String dataset:List.of("multi","negative","cross")) {
      var r=f.record("WUJIN",dataset);var preview=f.w().previewDirect(actor,dataset,List.of(edit(r,"否")));
      check(!f.store.find(f.op,r.id()).complete(),"direct preview not official");
      var s=f.w().confirm(actor,preview.id(),id());
      check(s.mode()==Mode.DIRECT&&s.state()==State.APPROVED,"direct confirmation mode");
      check(value(f.store.find(f.op,r.id())).equals("否"),"direct value applies");
      var clear=f.w().previewDirect(actor,dataset,List.of(edit(f.store.find(actor,r.id()),"")));
      f.w().confirm(actor,clear.id(),id());check(!f.store.find(f.op,r.id()).complete(),"clearing last official field resets completion");
    }
    BusinessRecord r=f.record("WUJIN","multi"),j=f.record("JINTAN","multi");
    denied(()->f.w().previewDirect(f.op,"multi",List.of(edit(r,"bad"))));denied(()->f.w().previewDirect(f.root,"multi",List.of(edit(r,"bad"))));
    denied(()->f.w().previewDirect(f.branch,"multi",List.of(edit(j,"bad"))));
    code(Code.INVALID_INPUT,()->f.w().previewDirect(f.div,"multi",List.of(edit(r,"one"),edit(j,"two"))));
    code(Code.INVALID_INPUT,()->f.w().previewDirect(f.div,"multi",List.of(edit(r,""))));
    var direct=f.w().previewDirect(f.branch,"multi",List.of(edit(r,"initial")));
    f.store.publishDirect(f.div,List.of(edit(r,"changed after preview")),id());
    code(Code.VERSION_CONFLICT,()->f.w().confirm(f.branch,direct.id(),id()));
    var inJ=f.save(f.otherOp,j,"JINTAN pending");f.w().confirm(f.otherOp,f.w().previewDraft(f.otherOp,inJ.id(),1).id(),id());
    check(f.w().submissions(f.op,Query.firstPage()).stream().allMatch(s->s.organizationId().equals("WUJIN")),"submission list scope");
    check(f.w().submissions(f.root,Query.firstPage()).stream().anyMatch(s->s.organizationId().equals("JINTAN")),"super sees all submitted data");
    denied(()->f.w().submissions(f.op,new Query(null,"JINTAN",null,null,null,false,0,20)));
    denied(()->f.w().pendingReviews(f.op,Query.firstPage()));
    check(f.w().submissions(f.op,new Query(null,null,null,null,null,true,0,100)).stream().allMatch(s->s.ownerId().equals(f.op.userId())),"mine filter");
    check(f.w().submissions(f.op,new Query("multi",null,null,LocalDate.of(2026,2,1),LocalDate.of(2026,2,28),false,0,50)).isEmpty(),"source period filter excludes September");
    check(!f.w().submissions(f.op,new Query("multi",null,null,LocalDate.of(2026,9,5),LocalDate.of(2026,9,5),false,0,50)).isEmpty(),"source interval overlap");
    code(Code.INVALID_INPUT,()->f.w().submissions(f.op,new Query(null,null,null,null,null,false,0,101)));
    String request=id();String notice=f.n().publishNotice(f.branch,"WUJIN","管理通知","仅虚构内容",List.of(f.op.userId(),f.op2.userId()),request);
    check(f.n().publishNotice(f.branch,"WUJIN","管理通知","仅虚构内容",List.of(f.op2.userId(),f.op.userId()),request).equals(notice),"notice publication canonical idempotency");
    denied(()->f.n().publishNotice(f.op,"WUJIN","bad","bad",List.of(f.op.userId()),id()));
    denied(()->f.n().publishNotice(f.branch,"WUJIN","bad","bad",List.of(f.otherOp.userId()),id()));
    code(Code.INVALID_INPUT,()->f.n().publishNotice(f.branch,"WUJIN","bad","bad",Arrays.asList((String)null),id()));
  }
  static void rollback(Fixture f)throws Exception {
    BusinessRecord a=f.record("WUJIN","negative"),b=f.record("WUJIN","negative");
    int beforeDrafts=f.store.diagnostics().get("drafts");String draftRequest=id();f.fail("draft-written",1);
    code(Code.TRANSACTION_FAILED,()->f.w().saveDraft(f.op,"",0,"negative",List.of(edit(a,"A")),"",draftRequest));
    check(f.store.diagnostics().get("drafts")==beforeDrafts,"failed draft transaction leaves no draft");
    var d=f.w().saveDraft(f.op,"",0,"negative",List.of(edit(a,"A")),"",draftRequest);check(d.version()==1,"draft retries after rollback");
    var batch=f.w().saveDraft(f.op,"",0,"negative",List.of(edit(a,"A"),edit(b,"B")),"",id());
    var preview=f.w().previewDraft(f.op,batch.id(),1);int submissions=f.store.diagnostics().get("submissions"),audits=f.store.diagnostics().get("audit_events");long count=f.n().unreadCount(f.review);String request=id();
    f.fail("notification-written",1);code(Code.TRANSACTION_FAILED,()->f.w().confirm(f.op,preview.id(),request));
    check(f.store.diagnostics().get("submissions")==submissions&&f.store.diagnostics().get("audit_events")==audits,"submit with failed notification rolls back state and audit");
    check(f.n().unreadCount(f.review)==count,"submit failed notices rolled back");
    var s=f.w().confirm(f.op,preview.id(),request);
    for(String point:List.of("official-row-written","decision-written","notification-written","decision-complete")) {
      audits=f.store.diagnostics().get("audit_events");count=f.n().unreadCount(f.op);String decision=id();f.fail(point,point.equals("official-row-written")?2:1);
      code(Code.TRANSACTION_FAILED,()->f.w().approve(f.review,s.id(),decision));
      check(f.w().submission(f.op,s.id()).state()==State.SUBMITTED,"failed approval stays submitted at "+point);
      check(f.store.find(f.op,a.id()).version()==1&&f.store.find(f.op,b.id()).version()==1,"both formal rows rolled back at "+point);
      check(f.store.diagnostics().get("audit_events")==audits&&f.n().unreadCount(f.op)==count,"approval audit and notifications rolled back at "+point);
    }
    f.w().approve(f.review,s.id(),id());check(f.store.find(f.op,b.id()).version()==2,"retry after partial transaction fault succeeds");
    var c=f.record("WUJIN","cross");var returned=f.pending(f.op,c,"return rollback");String returnRequest=id();f.fail("notification-written",1);
    code(Code.TRANSACTION_FAILED,()->f.w().reject(f.review,returned.id(),"需补充",returnRequest));
    check(f.w().submission(f.op,returned.id()).state()==State.SUBMITTED&&!f.store.find(f.op,c.id()).complete(),"failed return unchanged");
    f.w().reject(f.review,returned.id(),"需补充",returnRequest);check(f.w().submission(f.op,returned.id()).state()==State.RETURNED,"return retry commits");
    BusinessRecord x=f.record("WUJIN","multi"),y=f.record("WUJIN","multi");var p=f.w().previewDirect(f.branch,"multi",List.of(edit(x,"X"),edit(y,"Y")));String directRequest=id();
    f.fail("notification-written",1);code(Code.TRANSACTION_FAILED,()->f.w().confirm(f.branch,p.id(),directRequest));
    check(f.store.find(f.op,x.id()).version()==1&&f.store.find(f.op,y.id()).version()==1,"direct entire batch rollback");
    f.w().confirm(f.branch,p.id(),directRequest);check(f.store.find(f.op,x.id()).version()==2,"direct confirmation retry");
  }
  static void concurrency(Fixture f)throws Exception {
    var r=f.record("WUJIN","multi");var s=f.pending(f.op,r,"race");
    List<Object> results=race(()->f.w().approve(f.review,s.id(),id()),()->f.w().approve(f.review2,s.id(),id()));
    check(results.stream().filter(Submission.class::isInstance).count()==1,"two reviewers only one winner");
    check(results.stream().filter(v->v instanceof WorkflowException e&&e.code()==Code.ALREADY_DECIDED).count()==1,"loser receives already decided");
    check(f.store.find(f.op,r.id()).version()==2,"concurrent approval writes once");
    var r2=f.record("WUJIN","negative");var mixed=f.pending(f.op,r2,"approve vs return");
    results=race(()->f.w().approve(f.review,mixed.id(),id()),()->f.w().reject(f.review2,mixed.id(),"different decision",id()));
    check(results.stream().filter(Submission.class::isInstance).count()==1,"approve vs return one winner");
    State state=f.w().submission(f.op,mixed.id()).state();check(f.store.find(f.op,r2.id()).version()==(state==State.APPROVED?2:1),"winner and formal data agree");
    var r3=f.record("WUJIN","cross");var d=f.save(f.op,r3,"simultaneous submit");var p=f.w().previewDraft(f.op,d.id(),1);String request=id();
    results=race(()->f.w().confirm(f.op,p.id(),request),()->f.w().confirm(f.op,p.id(),request));
    check(results.get(0) instanceof Submission&&results.get(1) instanceof Submission,"simultaneous duplicate submit returns results");
    check(((Submission)results.get(0)).id().equals(((Submission)results.get(1)).id()),"simultaneous submit one id");
    var editDraft=f.save(f.op2,r3,"parallel edit");
    results=race(()->f.w().saveDraft(f.op2,editDraft.id(),1,"cross",List.of(edit(r3,"tab1")),"",id()),()->f.w().saveDraft(f.op2,editDraft.id(),1,"cross",List.of(edit(r3,"tab2")),"",id()));
    check(results.stream().filter(Draft.class::isInstance).count()==1,"optimistic draft version serializes tabs");
    check(results.stream().filter(v->v instanceof WorkflowException e&&e.code()==Code.VERSION_CONFLICT).count()==1,"old draft version conflict");
    var directRecord=f.record("WUJIN","multi");
    var p1=f.w().previewDirect(f.branch,"multi",List.of(edit(directRecord,"first direct edit")));
    var p2=f.w().previewDirect(f.review,"multi",List.of(edit(directRecord,"second direct edit")));
    results=race(()->f.w().confirm(f.branch,p1.id(),id()),()->f.w().confirm(f.review,p2.id(),id()));
    check(results.stream().filter(Submission.class::isInstance).count()==1,"concurrent direct confirmations one winner");
    check(results.stream().filter(v->v instanceof WorkflowException e&&e.code()==Code.VERSION_CONFLICT).count()==1,"losing direct confirmation detects formal revision");
    check(f.store.find(f.op,directRecord.id()).version()==2&&f.w().recordHistory(f.op,directRecord.id(),0,10).size()==1,"direct race one formal write and one immutable change set");
  }
  static void identityChanges(Fixture f)throws Exception {
    ActorContext movable=f.user(Role.OPERATOR,"WUJIN");var r=f.record("WUJIN","multi");var d=f.save(movable,r,"private before transfer");var p=f.w().previewDraft(movable,d.id(),1);
    f.n().publishNotice(f.branch,"WUJIN","机构通知","不向转机构人员泄露",List.of(movable.userId()),id());
    UserAccount u=f.store.sessionUser(movable.userId());f.store.updateUser(f.root,u.id(),u.revision(),u.name(),u.role(),"JINTAN",true);var moved=f.store.sessionUser(u.id()).actor();
    denied(()->f.w().draft(movable,d.id()));denied(()->f.w().draft(moved,d.id()));denied(()->f.w().confirm(moved,p.id(),id()));
    check(f.n().inbox(moved,false,0,100).isEmpty(),"transferred user does not receive previous branch messages");
    ActorContext inactive=f.user(Role.OPERATOR,"WUJIN");var pending=f.pending(inactive,r,"inactive owner");UserAccount old=f.store.sessionUser(inactive.userId());
    f.store.updateUser(f.root,old.id(),old.revision(),old.name(),old.role(),old.organizationId(),false);
    denied(()->f.w().submissions(inactive,Query.firstPage()));code(Code.OWNER_CHANGED,()->f.w().approve(f.review,pending.id(),id()));
    f.w().reject(f.review,pending.id(),"提交人已停用，请重新安排",id());check(!f.store.find(f.op,r.id()).complete(),"inactive owner can be returned without official write");
    ActorContext fresh=f.user(Role.OPERATOR,"WUJIN");var freshDraft=f.save(fresh,r,"password reset");var pre=f.w().previewDraft(fresh,freshDraft.id(),1);
    f.store.changeOwnPassword(fresh,id());var updated=f.store.sessionUser(fresh.userId()).actor();denied(()->f.w().confirm(fresh,pre.id(),id()));
    code(Code.CONFIRMATION_EXPIRED,()->f.w().confirm(updated,pre.id(),id()));check(f.w().draft(updated,freshDraft.id()).version()==1,"new login preserves old draft");
    UserAccount rev=f.store.sessionUser(f.otherReview.userId());f.store.updateUser(f.root,rev.id(),rev.revision(),rev.name(),rev.role(),rev.organizationId(),false);
    var jr=f.record("JINTAN","cross");var jd=f.save(f.otherOp,jr,"missing reviewer");var jp=f.w().previewDraft(f.otherOp,jd.id(),1);
    code(Code.NO_REVIEWER,()->f.w().confirm(f.otherOp,jp.id(),id()));check(f.w().draft(f.otherOp,jd.id()).version()==1,"no reviewer keeps draft");
  }
  static void restart(Fixture f)throws Exception {
    var r=f.record("WUJIN","negative");var d=f.save(f.op,r,"restart content");var p=f.w().previewDraft(f.op,d.id(),1);String request=id();
    var s=f.w().confirm(f.op,p.id(),request);var n=f.n().inbox(f.review,false,0,100).stream().filter(x->x.submissionId().equals(s.id())).findFirst().orElseThrow();f.n().markRead(f.review,n.id());
    f.reopen();check(f.w().draft(f.op,d.id()).rows().get(0).change().values().get("feedback").equals("restart content"),"draft survives restart");
    check(f.w().submission(f.review,s.id()).state()==State.SUBMITTED,"pending survives restart");
    check(f.n().inbox(f.review,false,0,100).stream().filter(x->x.id().equals(n.id())).findFirst().orElseThrow().readAt()!=null,"read receipt survives restart");
    check(f.w().confirm(f.op,p.id(),request).id().equals(s.id()),"confirmation retry after restart");
    String approve=id();f.w().approve(f.review,s.id(),approve);f.reopen();f.w().approve(f.review,s.id(),approve);
    check(f.store.find(f.op,r.id()).version()==2,"approval replay after restart applies once");
  }
  static void migrations()throws Exception {
    Path dir=Files.createTempDirectory("xinguan-v1-upgrade-");String v1;
    try(InputStream in=PlatformStore.class.getResourceAsStream("/db/V001__foundation.sql")){v1=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
    String password=id();Passwords.Encoded encoded=Passwords.encode(password);
    DatasetSchema schema=DatasetSchema.get("negative");List<String> oldValues=new ArrayList<>(Collections.nCopies(schema.width(),""));
    oldValues.set(schema.customerColumn,"虚构旧库企业");oldValues.set(schema.branchColumn,"武进");oldValues.set(schema.index("feedback"),"升级前正式反馈");
    String encodedValues=Codec.encode(oldValues);
    try(Connection db=connect(dir);Statement st=db.createStatement()) {
      st.execute("CREATE TABLE schema_migrations(version INT PRIMARY KEY,checksum VARCHAR(64) NOT NULL,applied_at VARCHAR(40) NOT NULL)");
      for(String sql:v1.split(";"))if(!sql.isBlank())st.execute(sql);
      try(PreparedStatement p=db.prepareStatement("INSERT INTO schema_migrations VALUES(1,?,?)")){p.setString(1,Codec.hash(v1));p.setString(2,Instant.now().toString());p.executeUpdate();}
      st.execute("INSERT INTO organizations VALUES('CZ','分行',NULL)");
      st.execute("INSERT INTO organizations VALUES('WUJIN','武进','CZ')");
      try(PreparedStatement p=db.prepareStatement("INSERT INTO users VALUES('old-user','000000001','虚构迁移超管','SUPER_ADMIN','CZ',TRUE,?,?,FALSE,1)")){p.setString(1,encoded.hash());p.setString(2,encoded.salt());p.executeUpdate();}
      try(PreparedStatement p=db.prepareStatement("INSERT INTO official_records VALUES('old-record',7,'negative','20260901-20260915','2026-09-01','2026-09-15','WUJIN',?,?,'synthetic-v1.xlsx','2026-09-01T00:00:00Z','2026-09-12T00:00:00Z',?)")) {
        p.setString(1,Codec.hash("synthetic-v1"));p.setString(2,encodedValues);p.setString(3,Codec.encode(List.of("旧字段","虚构历史备注")));p.executeUpdate();
      }
      st.execute("INSERT INTO audit_events VALUES('old-audit','2026-09-12T00:00:00Z','old-user','虚构历史人员','DIVISION_ADMIN','WUJIN','old-record','DIRECT_EDIT','old-request','before','after','legacy audit')");
      st.execute("INSERT INTO processed_requests VALUES('old-request','old-user','old-hash','old-result')");
      st.execute("INSERT INTO migration_items VALUES('synthetic/legacy/1','old-record','old-source')");
    }
    try(PlatformStore store=new PlatformStore(dir)){
      check(store.schemaVersion()==2,"V1 upgrades to V2");var oldUser=store.authenticateUser("000000001",password);check(oldUser!=null,"V1 credential preserved");
      BusinessRecord row=store.find(oldUser.actor(),"old-record");
      check(row.version()==7&&row.values().equals(oldValues),"V1 formal ids versions and feedback preserved");
      check(row.legacyExtras().get("旧字段").equals("虚构历史备注"),"V1 legacy metadata preserved");
      check(store.auditEvents(oldUser.actor(),100).stream().anyMatch(e->e.recordId().equals("old-record")&&e.before().equals("before")&&e.after().equals("after")),"V1 audit history preserved");
    }
    try(PlatformStore store=new PlatformStore(dir)){check(store.authenticateUser("000000001",password).id().equals("old-user"),"migration idempotent preserves account id");}
    try(Connection db=connect(dir);Statement st=db.createStatement();ResultSet rs=st.executeQuery("SELECT version,checksum FROM schema_migrations ORDER BY version")) {
      check(rs.next()&&rs.getInt(1)==1&&rs.getString(2).equals(Codec.hash(v1)),"original V1 checksum unchanged");check(rs.next()&&rs.getInt(1)==2&&!rs.next(),"two sequential schema versions");
    }
    try(Connection db=connect(dir);Statement st=db.createStatement();ResultSet rs=st.executeQuery("SELECT result_id FROM processed_requests WHERE id='old-request'")){check(rs.next()&&rs.getString(1).equals("old-result"),"V1 idempotency record preserved");}
    try(Connection db=connect(dir);Statement st=db.createStatement();ResultSet rs=st.executeQuery("SELECT source_hash FROM migration_items WHERE legacy_key='synthetic/legacy/1'")){check(rs.next()&&rs.getString(1).equals("old-source"),"V1 legacy migration marker preserved");}
    Path failed=Files.createTempDirectory("xinguan-failed-migration-");
    expect(IOException.class,()->{try(var ignored=new PlatformStore(failed,Clock.systemUTC(),point->{if(point.equals("migration-2-step-2"))throw new IllegalStateException("synthetic migration fault");})){throw new AssertionError("fault missing");}});
    expect(IOException.class,()->{try(var ignored=new PlatformStore(failed)){throw new AssertionError("partial migration incorrectly resumed");}});
    try(Connection db=connect(failed);Statement st=db.createStatement();ResultSet rs=st.executeQuery("SELECT MAX(version) FROM schema_migrations")){rs.next();check(rs.getInt(1)==1,"failed DDL never marked applied");}
    try(Connection db=connect(dir);Statement st=db.createStatement()){st.execute("UPDATE schema_migrations SET checksum='bad' WHERE version=1");}
    expect(IOException.class,()->{try(var ignored=new PlatformStore(dir)){throw new AssertionError("tampered checksum accepted");}});
    try(Connection db=connect(dir);PreparedStatement st=db.prepareStatement("UPDATE schema_migrations SET checksum=? WHERE version=1")){st.setString(1,Codec.hash(v1));st.executeUpdate();}
    try(Connection db=connect(dir);Statement st=db.createStatement()){st.execute("INSERT INTO schema_migrations VALUES(3,'future','2026-09-14T00:00:00Z')");}
    expect(IOException.class,()->{try(var ignored=new PlatformStore(dir)){throw new AssertionError("future version accepted");}});
  }

  static final class Fixture implements AutoCloseable {
    final Path dir=Files.createTempDirectory("xinguan-workflow-test-");final MutableClock clock=new MutableClock();
    final AtomicReference<String> failPoint=new AtomicReference<>();final AtomicInteger countdown=new AtomicInteger();
    PlatformStore store;ActorContext root,div,branch,op,op2,review,review2,otherOp,otherReview;int sequence=10;
    Fixture()throws Exception {
      open();String password=id();store.bootstrapSuperAdmin("000000001",password);root=store.authenticateUser("000000001",password).actor();
      div=user(Role.DIVISION_ADMIN,"CZ");branch=user(Role.BRANCH_ADMIN,"WUJIN");op=user(Role.OPERATOR,"WUJIN");op2=user(Role.OPERATOR,"WUJIN");
      review=user(Role.REVIEWER,"WUJIN");review2=user(Role.REVIEWER,"WUJIN");otherOp=user(Role.OPERATOR,"JINTAN");otherReview=user(Role.REVIEWER,"JINTAN");
    }
    void open()throws Exception {store=new PlatformStore(dir,clock,point->{if(point.equals(failPoint.get())&&countdown.decrementAndGet()==0){failPoint.set(null);throw new IllegalStateException("synthetic fault: "+point);}});}
    void reopen()throws Exception {store.close();open();}
    ActorContext user(Role role,String org) {var created=store.createUser(root,String.format("%09d",++sequence),"虚构"+role+sequence,role,org);store.changeOwnPassword(created.user().actor(),id());return store.sessionUser(created.user().id()).actor();}
    BusinessRecord record(String org,String dataset) {
      var schema=DatasetSchema.get(dataset);String key="ROW"+(++sequence);List<String> values=new ArrayList<>(Collections.nCopies(schema.width(),""));
      values.set(0,key);values.set(schema.customerColumn,"虚构企业 "+key);values.set(schema.branchColumn,Organizations.label(org));values.set(schema.codeColumn,"000"+key);
      Period period=Period.parse("20260901-20260915","");if(schema.periodColumn>=0)values.set(schema.periodColumn,period.key());
      store.importRows(div,dataset,List.of(new BusinessRecord("",0,dataset,period,org,values,"synthetic.xlsx",clock.instant().toString(),"",Map.of())),false,id());
      return store.list(div,dataset,null,null).stream().filter(r->r.values().get(0).equals(key)).findFirst().orElseThrow();
    }
    Draft save(ActorContext actor,BusinessRecord r,String text) {return w().saveDraft(actor,"",0,r.dataset(),List.of(edit(r,text)),"",id());}
    Submission pending(ActorContext actor,BusinessRecord r,String text) {var d=save(actor,r,text);return w().confirm(actor,w().previewDraft(actor,d.id(),1).id(),id());}
    WorkflowService w(){return store.workflow();}NotificationService n(){return store.notifications();}
    void fail(String point,int after){failPoint.set(point);countdown.set(after);}
    public void close()throws Exception{store.close();}
  }
  static final class MutableClock extends Clock {
    Instant instant=Instant.parse("2026-09-14T02:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return instant;}void advance(Duration d){instant=instant.plus(d);}
  }
  static RecordChange edit(BusinessRecord r,String value){return new RecordChange(r.id(),r.version(),Map.of(r.dataset().equals("cross")?"cross_feedback":"feedback",value));}
  static String value(BusinessRecord r){return r.values().get(DatasetSchema.get(r.dataset()).index(r.dataset().equals("cross")?"cross_feedback":"feedback"));}
  static String id(){return UUID.randomUUID().toString();}
  static Connection connect(Path dir)throws Exception {Files.createDirectories(dir.resolve("platform"));return DriverManager.getConnection("jdbc:h2:file:"+dir.resolve("platform/records").toAbsolutePath().toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE","sa","");}
  static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
  interface Work {void run()throws Exception;}
  interface Value {Object run()throws Exception;}
  static void denied(Work work){expect(SecurityException.class,work);}
  static void expect(Class<? extends Throwable> type,Work work){assertions++;try{work.run();}catch(Throwable e){if(type.isInstance(e))return;throw new AssertionError("expected "+type+" but got "+e,e);}throw new AssertionError("expected "+type);}
  static WorkflowException code(Code code,Work work){assertions++;try{work.run();}catch(WorkflowException e){if(e.code()==code)return e;throw new AssertionError("expected "+code+" but got "+e.code()+": "+e.getMessage(),e);}catch(Exception e){throw new AssertionError("expected workflow error "+code,e);}throw new AssertionError("expected "+code);}
  static List<Object> race(Value first,Value second)throws Exception {
    ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
    try {
      List<Future<Object>> futures=new ArrayList<>();for(Value value:List.of(first,second))futures.add(pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("race timeout");try{return value.run();}catch(WorkflowException e){return e;}}));
      check(ready.await(10,TimeUnit.SECONDS),"both concurrent calls ready");start.countDown();return List.of(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS));
    }finally{pool.shutdownNow();}
  }
}

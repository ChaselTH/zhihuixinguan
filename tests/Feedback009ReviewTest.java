package xinguan.platform;

import java.util.*;
import java.time.*;
import static xinguan.platform.WorkflowContracts.*;
import static xinguan.platform.WorkflowPlatformTest.*;

/** Maintainer reproductions, independent fixtures so every defect is reported on the old head. */
public final class Feedback009ReviewTest {
  static int checks, failures;
  public static void main(String[] args)throws Exception {
    run("R1 division review blocks whole month deletion",Feedback009ReviewTest::deletion);
    run("R3 branch submission receipt has no hidden snapshot",Feedback009ReviewTest::receipts);
    run("R5 single division administrator imports candidates",Feedback009ReviewTest::singleAdministrator);
    run("R6 explicit unchanged reconfirmation",Feedback009ReviewTest::reconfirmation);
    run("R7 legacy publication can reopen",Feedback009ReviewTest::legacyReopen);
    run("R8 partial return restores only returned rows",Feedback009ReviewTest::partialReturn);
    run("approval failures and concurrency preserve whole transactions",Feedback009ReviewTest::transactions);
    run("deleted returned rows do not break private draft lists",Feedback009ReviewTest::deletedReturns);
    if(failures>0)throw new AssertionError("FEEDBACK009_REVIEW_FAILED cases="+failures);
    System.out.println("FEEDBACK009_REVIEW_OK checks="+checks);
  }
  static void run(String name,Work work)throws Exception {try{work.run();System.out.println("PASS "+name);}catch(Throwable e){failures++;System.out.println("FAIL "+name+": "+e);}}
  static void verify(boolean result,String message){checks++;if(!result)throw new AssertionError(message);}
  static void deletion()throws Exception {try(var f=new Fixture()){
    for(var role:List.of(f.op,f.branch,f.review)){
      var a=f.record("WUJIN","multi");var b=f.record("JINTAN","negative");
      var preview=f.store.maintenance().previewMonth(f.div,"2026-09");
      var s=role.role()==Role.OPERATOR?f.pending(role,a,"delete-protected"):publish(f.store,role,edit(a,"delete-protected"));
      if(role.role()==Role.OPERATOR)f.w().approve(f.review,s.id(),id());
      verify(f.store.maintenance().previewMonth(f.div,"2026-09").pending()>0,"division tasks missing from delete preview");
      expect(ConcurrentModificationException.class,()->f.store.maintenance().confirm(f.div,preview.token(),"month"));
      verify(f.store.find(f.div,a.id())!=null&&f.store.find(f.div,b.id())!=null,"deletion must be all-or-nothing");
      f.w().reject(f.div,s.id(),"cleanup scenario",id());
    }
  }}
  static void receipts()throws Exception {try(var f=new Fixture()){
    var a=f.record("WUJIN","multi");var b=f.record("WUJIN","multi");
    var draft=f.w().saveDraft(f.op,"",0,"multi",List.of(edit(a,"SECRET-A"),edit(b,"VISIBLE-B")),"",id());
    var s=f.w().confirm(f.op,f.w().previewDraft(f.op,draft.id(),1).id(),id());
    f.w().approveRows(f.review,s.id(),List.of(a.id()),id());
    for(var actor:List.of(f.op,f.branch,f.review)){
      var receipt=f.w().submission(actor,s.id());
      int expected=actor.role()==Role.OPERATOR?0:1;
      verify(receipt.rows().size()==expected&&(expected==0||receipt.rows().get(0).before().id().equals(b.id())),"mixed receipt leaks hidden division or branch-review row");
      verify(f.w().submissions(actor,Query.firstPage()).get(0).rows().size()==expected,"submission list leaks hidden workflow row");
      verify(f.w().recordHistory(actor,a.id(),0,10).stream().flatMap(h->h.rows().stream()).noneMatch(r->r.before().id().equals(a.id())),"history leaks hidden row");
    }
    f.w().approveRows(f.div,s.id(),List.of(a.id()),id());
    verify(f.w().submission(f.branch,s.id()).rows().stream().noneMatch(r->r.before().id().equals(a.id())),"published snapshot leaks");
    denied(()->f.w().submission(f.otherOp,s.id()));
    verify(f.w().submission(f.root,s.id()).rows().size()==2,"global read-only audit retains full scope");
    var unpublished=f.record("WUJIN","multi");String saveRequest=id();var edits=List.of(edit(unpublished,"PRIVATE-SAVED"));
    var privateDraft=f.w().saveDraft(f.op,"",0,"multi",edits,"",saveRequest);
    publish(f.store,f.div,edit(unpublished,"HIDDEN-CURRENT-OFFICIAL"));
    verify(f.w().saveDraft(f.op,"",0,"multi",edits,"",saveRequest).rows().isEmpty(),"idempotent draft save cannot return a now-hidden snapshot");
    var conflict=code(Code.VERSION_CONFLICT,()->f.w().previewDraft(f.op,privateDraft.id(),1));
    verify(conflict.conflicts().isEmpty(),"stale preview must not carry hidden current values in conflict details");
  }}
  static void singleAdministrator()throws Exception {try(var f=new Fixture()){
    for(String dataset:List.of("multi","negative","cross")){
      var row=f.record("WUJIN",dataset);var schema=DatasetSchema.get(dataset);var values=new ArrayList<>(row.values());
      values.set(schema.index(dataset.equals("cross")?"cross_feedback":"feedback"),"IMPORTED-CANDIDATE");
      var incoming=new BusinessRecord("",0,dataset,row.period(),row.organizationId(),values,"synthetic.xlsx",f.clock.instant().toString(),"",Map.of());
      f.store.importRows(f.div,dataset,List.of(incoming),true,id());
      verify(value(f.store.find(f.div,row.id())).isEmpty(),"import cannot publish formal values");
      var s=f.w().pendingReviews(f.review,new Query(dataset,null,null,null,null,false,0,10)).get(0);
      expect(WorkflowException.class,()->f.w().approve(f.div,s.id(),id()));
      f.w().approve(f.review,s.id(),id());
      verify(value(f.store.find(f.div,row.id())).isEmpty(),"branch confirmation cannot publish import");
      f.w().approve(f.div,s.id(),id());
      verify(value(f.store.find(f.div,row.id())).equals("IMPORTED-CANDIDATE"),"original uploader can finalize independently branch-confirmed candidate");
    }
    var own=publish(f.store,f.review,edit(f.record("WUJIN","multi"),"reviewer-authored"));
    denied(()->f.w().approve(f.review,own.id(),id()));
  }}
  // Reflection lets this regression compile and report a missing API on the original PR.
  static Preview returnedPreview(WorkflowService w,ActorContext a,BusinessRecord row)throws Exception {
    try{return (Preview)WorkflowService.class.getMethod("previewReturned",ActorContext.class,String.class,long.class).invoke(w,a,row.id(),row.version());}
    catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw e;}
  }
  static void reconfirmation()throws Exception {try(var f=new Fixture()){
    var row=f.record("WUJIN","multi");var published=publish(f.store,f.div,edit(row,"unchanged and correct"));row=f.store.find(f.div,row.id());
    f.w().reopenCompleted(f.div,row.id(),row.version(),"please verify",id());row=f.store.find(f.op,row.id());
    var ordinary=f.w().saveDraft(f.op,"",0,"multi",List.of(edit(row,"unchanged and correct")),"",id());
    verify(ordinary.rows().isEmpty(),"ordinary same-value save must stay empty");
    var p=returnedPreview(f.w(),f.op,row);var s=f.w().confirm(f.op,p.id(),id());
    verify(s.priorSubmissionId().equals(published.id()),"reconfirmation must link the previous publication");
    f.w().approve(f.review,s.id(),id());f.w().approve(f.div,s.id(),id());
    verify(f.store.find(f.div,row.id()).version()==row.version()&&f.store.find(f.div,row.id()).workflowStage()==RowStage.PUBLISHED,"reconfirmation must publish state without fabricating a value revision");
    long notices=f.n().unreadCount(f.op);String repeat=id();
    String event=f.w().reopenCompleted(f.div,row.id(),row.version(),"please verify",repeat);
    verify(f.n().unreadCount(f.op)==notices+1,"second same-value/same-reason reopening must create a fresh notice");
    verify(f.w().reopenCompleted(f.div,row.id(),row.version(),"please verify",repeat).equals(event)&&f.n().unreadCount(f.op)==notices+1,"same reopen request remains exactly once");
  }}
  static void legacyReopen()throws Exception {try(var f=new Fixture()){
    var row=f.record("WUJIN","multi");publish(f.store,f.div,edit(row,"legacy-complete"));
    try(var db=connect(f.dir);var st=db.prepareStatement("UPDATE workflow_record_state SET stage='LEGACY_PUBLISHED',submission_id=NULL WHERE record_id=?")){st.setString(1,row.id());st.executeUpdate();}
    var legacy=f.store.find(f.div,row.id());f.w().reopenCompleted(f.div,row.id(),legacy.version(),"legacy correction",id());
    verify(f.store.find(f.op,row.id()).workflowStage()==RowStage.RETURNED&&value(f.store.find(f.op,row.id())).equals("legacy-complete"),"legacy formal values must survive reopening");
  }}
  static void partialReturn()throws Exception {try(var f=new Fixture()){
    var a=f.record("WUJIN","negative");var b=f.record("WUJIN","negative");
    var d=f.w().saveDraft(f.op,"",0,"negative",List.of(edit(a,"RETURNED-A"),edit(b,"PENDING-B")),"",id());
    var s=f.w().confirm(f.op,f.w().previewDraft(f.op,d.id(),1).id(),id());
    f.w().rejectRows(f.review,s.id(),List.of(a.id()),"only A",id());
    verify(f.w().submission(f.op,s.id()).state()==State.PARTIAL,"fixture is genuinely partial");
    var recovered=f.w().editableDraft(f.op,d.id());
    verify(recovered.rows().size()==1&&recovered.rows().get(0).before().id().equals(a.id())&&recovered.priorSubmissionId().equals(s.id()),"recover only returned snapshot, never pending sibling");
    var replacement=f.w().saveDraft(f.op,"",0,"negative",List.of(edit(f.store.find(f.op,a.id()),"REVISED-A")),s.id(),id());
    f.w().confirm(f.op,f.w().previewDraft(f.op,replacement.id(),1).id(),id());
    verify(f.store.find(f.div,b.id()).workflowStage()==RowStage.BRANCH_REVIEW,"resubmitting A cannot rewrite B");
    f.w().rejectRows(f.review,s.id(),List.of(b.id()),"reassign B",id());
    var privateDraft=f.save(f.op,f.record("WUJIN","negative"),"PRIVATE-NEVER-SHARE");
    // Simulates a deactivated historical author; no production identity is read or changed.
    try(var db=connect(f.dir);var st=db.prepareStatement("UPDATE users SET active=FALSE,revision=revision+1 WHERE id=?")){st.setString(1,f.op.userId());st.executeUpdate();}
    var successor=f.w().saveDraft(f.op2,"",0,"negative",List.of(edit(f.store.find(f.op2,b.id()),"SUCCESSOR-B")),s.id(),id());
    f.w().confirm(f.op2,f.w().previewDraft(f.op2,successor.id(),1).id(),id());
    denied(()->f.w().draft(f.op2,privateDraft.id()));
    verify(f.w().drafts(f.op2,"negative",0,100).stream().noneMatch(x->x.id().equals(privateDraft.id())),"successor never inherits private drafts");
  }}
  static void transactions()throws Exception {try(var f=new Fixture()){
    var a=ImportPlatformTest.record(f,"WUJIN","multi");var b=ImportPlatformTest.record(f,"WUJIN","multi");
    var d=f.w().saveDraft(f.op,"",0,"multi",List.of(edit(a,"atomic-A"),edit(b,"atomic-B")),"",id());
    var s=f.w().confirm(f.op,f.w().previewDraft(f.op,d.id(),1).id(),id());
    int audit=f.store.diagnostics().get("audit_events");long notices=f.n().unreadCount(f.div);String branchRequest=id();
    f.fail("decision-written",1);code(Code.TRANSACTION_FAILED,()->f.w().approve(f.review,s.id(),branchRequest));
    verify(f.store.find(f.div,a.id()).workflowStage()==RowStage.BRANCH_REVIEW&&f.store.find(f.div,b.id()).workflowStage()==RowStage.BRANCH_REVIEW&&f.store.diagnostics().get("audit_events")==audit&&f.n().unreadCount(f.div)==notices,"failed branch decision rolls back stages/audit/notices");
    f.w().approve(f.review,s.id(),branchRequest);
    var staged=ImportPlatformTest.stage(f.store.importing(),f.div,"multi",List.of(ImportPlatformTest.source(ImportPlatformTest.withFeedback(a,"overwrite"))),0);
    verify(f.store.importing().preview(f.div,staged.id(),0,25).items().get(0).pending()==1,"import preview counts division stage");
    expect(ConcurrentModificationException.class,()->f.store.importing().confirm(f.div,staged.id(),1,"overwrite",true,false));
    audit=f.store.diagnostics().get("audit_events");int beforeAudit=audit;String finalRequest=id();
    f.fail("official-row-written",2);code(Code.TRANSACTION_FAILED,()->f.w().approve(f.div,s.id(),finalRequest));
    verify(f.store.find(f.div,a.id()).version()==1&&f.store.find(f.div,b.id()).version()==1&&f.store.diagnostics().get("audit_events")==beforeAudit&&f.store.find(f.div,a.id()).workflowStage()==RowStage.DIVISION_REVIEW,"partial final write rolls back both rows and all audit");
    var outcomes=race(()->f.w().approve(f.div,s.id(),finalRequest),()->f.w().approve(f.div,s.id(),finalRequest));
    verify(outcomes.stream().allMatch(Submission.class::isInstance)&&f.store.find(f.div,a.id()).version()==2&&f.store.find(f.div,b.id()).version()==2,"concurrent final review publishes exactly once");
    f.w().reopenCompleted(f.div,a.id(),2,"published draft restoration",id());
    var recovered=f.w().editableDraft(f.op,d.id());verify(recovered.rows().size()==1&&recovered.rows().get(0).change().expectedVersion()==2,"reopened original draft uses current formal baseline and only returned row");
    var revised=f.w().saveDraft(f.op,recovered.id(),recovered.version(),"multi",List.of(edit(f.store.find(f.op,a.id()),"corrected-after-publication")),recovered.priorSubmissionId(),id());
    var next=f.w().confirm(f.op,f.w().previewDraft(f.op,revised.id(),revised.version()).id(),id());
    verify(f.w().submission(f.div,s.id()).rows().get(0).before().version()==1&&next.priorSubmissionId().equals(s.id()),"draft rebasing preserves old immutable snapshot and links new round");
    f.w().reject(f.review,next.id(),"restore to successor",id());
    String restoreRequest=id();var restored=f.w().restoreReturned(f.op2,next.id(),List.of(a.id()),restoreRequest);
    for(var actor:List.of(f.op,f.branch,f.div,f.root))verify(f.store.access().audit(actor,new AccessPlatform.AuditFilter("business","","",restored.id(),null,null),0,100).isEmpty(),"restored private draft existence is not exposed in public audit");
    var successor=f.w().confirm(f.op2,f.w().previewDraft(f.op2,restored.id(),1).id(),id());f.w().approve(f.review,successor.id(),id());
    verify(f.w().restoreReturned(f.op2,next.id(),List.of(a.id()),restoreRequest).rows().isEmpty(),"restore retry after branch approval returns redacted result");
    denied(()->f.w().restoreReturned(f.otherOp,next.id(),List.of(a.id()),id()));
  }}
  static void deletedReturns()throws Exception {try(var f=new Fixture()){
    var row=f.record("WUJIN","multi");var old=f.pending(f.op,row,"returned then deleted");f.w().reject(f.review,old.id(),"obsolete month",id());
    var deletion=f.store.maintenance().previewMonth(f.div,"2026-09");f.store.maintenance().confirm(f.div,deletion.token(),"month");
    var available=f.save(f.op,f.record("WUJIN","multi"),"new available draft");
    verify(f.w().drafts(f.op,"multi",0,1).get(0).id().equals(available.id()),"deleted returned draft must not break lists or consume pagination");
    verify(f.w().draft(f.op,old.draftId()).rows().isEmpty(),"deleted returned row never reappears in old draft");
  }}
}

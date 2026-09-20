package xinguan.platform;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static xinguan.platform.ImportPlatform.*;
import static xinguan.platform.WorkflowPlatformTest.id;

/** Isolated real H2, persisted identities, injected transaction failures and upgrade fixtures. */
public final class ImportPlatformTest {
  static int assertions;
  public static void main(String[] args)throws Exception {
    try(var f=new WorkflowPlatformTest.Fixture()){
      permissionsAndDecisions(f);conflictsAndRollback(f);restartAndExpiry(f);migration(f);
    }
    schema8Upgrade();
    Path failed=Files.createTempDirectory("xinguan-import-failed-migration-");
    expect(java.io.IOException.class,()->{try(var ignored=new PlatformStore(failed,Clock.systemUTC(),p->{if(p.equals("migration-5-step-1"))throw new IllegalStateException("synthetic migration failure");})) {throw new AssertionError("missing migration fault");}});
    expect(java.io.IOException.class,()->{try(var ignored=new PlatformStore(failed)){throw new AssertionError("partial migration accepted");}});
    System.out.println("IMPORT_PLATFORM_OK assertions="+assertions+" persistent staging, choices, identity, concurrency, rollback, expiry and V3 upgrade");
  }
  static void permissionsAndDecisions(WorkflowPlatformTest.Fixture f)throws Exception {
    var p=f.store.importing();var r=record(f,"WUJIN","multi");
    for(var a:List.of(f.root,f.branch,f.op,f.review,f.otherOp)){
      expect(SecurityException.class,()->stage(p,a,"multi",List.of(source(r)),0));expect(SecurityException.class,()->p.jobs(a,0,25));
    }
    var peer=f.user(Role.DIVISION_ADMIN,"CZ");int official=f.store.list(f.div,null,null,null).size(),audits=f.store.diagnostics().get("audit_events");
    var j=stage(p,f.div,"multi",List.of(source(withFeedback(r,"导入填写")),source(withFeedback(r,"导入填写"))),2);
    check(j.count()==1&&j.repeated()==1&&j.examples()==2,"exact duplicates collapse with counters");
    check(f.store.list(f.div,null,null,null).size()==official&&!f.store.find(f.op,r.id()).complete()&&f.store.diagnostics().get("audit_events")==audits,"stage does not publish or leak into audit");
    expect(SecurityException.class,()->p.preview(peer,j.id(),0,25));expect(SecurityException.class,()->p.confirm(peer,j.id(),1,"saved",false,false));
    check(p.jobs(peer,0,25).isEmpty(),"other division administrator has no private staging list");
    var preview=p.preview(f.div,j.id(),0,25);check(preview.items().get(0).previous().id().equals(r.id())&&preview.items().get(0).source().row()==3,"stable id and source location captured");
    check(preview.summary().newCount()==0&&preview.summary().formalDuplicates()==1&&preview.summary().preserveUpdated()==1&&preview.summary().overwriteUpdated()==1,"fill blank counts as one update in both modes");
    var result=p.confirm(f.div,j.id(),1,"saved",false,false);check(result.duplicates()==1&&result.added()==0&&WorkflowPlatformTest.value(f.store.find(f.op,r.id())).isEmpty()&&f.store.find(f.op,r.id()).version()==1,"import prefill is a proposal, not a formal write");
    check(p.confirm(f.div,j.id(),1,"saved",false,false).equals(result),"repeat returns original result");
    approveImported(f,r.id(),peer);check(WorkflowPlatformTest.value(f.store.find(f.op,r.id())).equals("导入填写")&&f.store.find(f.op,r.id()).version()==2,"import proposal becomes formal only after both approvals");
    expect(ConcurrentModificationException.class,()->p.confirm(f.div,j.id(),1,"overwrite",true,false));
    expect(ConcurrentModificationException.class,()->p.cancel(f.div,j.id(),2));
    expect(IllegalArgumentException.class,()->stage(p,f.div,"multi",List.of(source(r),source(withFeedback(r,"different"))),0));
    var preserve=stage(p,f.div,"multi",List.of(source(r)),0);var summary=p.preview(f.div,preserve.id(),0,1).summary();check(summary.preserveUpdated()==0&&summary.overwriteUpdated()==1,"preserve keeps filled value, overwrite empty clears one row");p.confirm(f.div,preserve.id(),1,"preserve",false,false);
    check(WorkflowPlatformTest.value(f.store.find(f.op,r.id())).equals("导入填写")&&f.store.find(f.op,r.id()).version()==2,"preserve leaves nonempty official value and revision unchanged");
    var clear=stage(p,f.div,"multi",List.of(source(r)),0);var chosen=p.choices(f.div,clear.id(),1,Map.of(1,Choice.OVERWRITE),null);
    check(chosen.revision()==2&&p.preview(f.div,clear.id(),0,25).items().get(0).choice()==Choice.OVERWRITE,"per-row choice persists");
    expect(ConcurrentModificationException.class,()->p.confirm(f.div,clear.id(),1,"saved",true,false));
    expect(IllegalArgumentException.class,()->p.confirm(f.div,clear.id(),2,"saved",false,false));
    p.confirm(f.div,clear.id(),2,"saved",true,false);check(f.store.find(f.op,r.id()).complete()&&f.store.find(f.op,r.id()).version()==2,"overwrite proposal cannot clear formal completion before approval");approveImported(f,r.id(),peer);check(!f.store.find(f.op,r.id()).complete()&&f.store.find(f.op,r.id()).version()==3,"explicit blank overwrite clears completion only after approval");
    var a=record(f,"WUJIN","negative");var b=record(f,"JINTAN","negative");
    var mixed=stage(p,f.div,"negative",List.of(source(withFeedback(a,"own")),source(withFeedback(b,"skip"))),0);
    p.choices(f.div,mixed.id(),1,Map.of(2,Choice.SKIP),null);p.confirm(f.div,mixed.id(),2,"saved",false,false);
    check(WorkflowPlatformTest.value(f.store.find(f.op,a.id())).isEmpty()&&!f.store.find(f.div,b.id()).complete(),"mixed row choices remain proposals and skipped rows stay unchanged");approveImported(f,a.id(),peer);check(WorkflowPlatformTest.value(f.store.find(f.op,a.id())).equals("own"),"selected import row advances through both approval stages");
    var invalid=stage(p,f.div,"multi",List.of(source(r)),0);
    expect(IllegalArgumentException.class,()->p.choices(f.div,invalid.id(),1,Map.of(99,Choice.SKIP),null));
    expect(IllegalArgumentException.class,()->p.choices(f.div,invalid.id(),1,Map.of(1,Choice.SKIP),Choice.PRESERVE));
    expect(IllegalArgumentException.class,()->p.confirm(f.div,invalid.id(),1,"anything",false,false));
    p.choices(f.div,invalid.id(),1,Map.of(),Choice.SKIP);expect(IllegalArgumentException.class,()->p.confirm(f.div,invalid.id(),2,"saved",false,false));
    p.cancel(f.div,invalid.id(),2);check(p.preview(f.div,invalid.id(),0,25).items().isEmpty(),"cancel clears temporary row payload");
    check(p.cancel(f.div,invalid.id(),2).state().equals("CANCELLED"),"cancel idempotent");
    expect(ConcurrentModificationException.class,()->p.confirm(f.div,invalid.id(),2,"saved",false,false));
    expect(IllegalArgumentException.class,()->p.preview(f.div,j.id(),-1,25));expect(IllegalArgumentException.class,()->p.jobs(f.div,0,31));
    List<SourceRow> many=new ArrayList<>();for(int i=0;i<31;i++)many.add(source(newRecord("multi","WUJIN","page"+i)));
    var paged=stage(p,f.div,"multi",many,0);check(p.preview(f.div,paged.id(),0,25).items().size()==25&&p.preview(f.div,paged.id(),25,25).items().size()==6,"full preview pagination");
    p.choices(f.div,paged.id(),1,Map.of(1,Choice.SKIP),null);p.choices(f.div,paged.id(),2,Map.of(31,Choice.SKIP),null);
    check(p.preview(f.div,paged.id(),0,25).items().get(0).choice()==Choice.SKIP,"later page retains previous page choice");p.cancel(f.div,paged.id(),3);
    var bundleRows=List.of(source(newRecord("negative","WUJIN","bundle-negative")),source(newRecord("multi","WUJIN","bundle-multi")),source(newRecord("cross","WUJIN","bundle-cross")));
    var bundle=stageBundle(p,f.div,bundleRows,0);var bundlePreview=p.preview(f.div,bundle.id(),0,25);check(bundle.dataset().equals("bundle")&&bundle.count()==3,"unified staging keeps all three data sheets in one job");check(bundlePreview.items().size()==3,"unified preview exposes all staged source rows");check(bundlePreview.summary().fileCount()==1&&bundlePreview.summary().datasetCounts().get("negative")==1&&bundlePreview.summary().datasetCounts().get("multi")==1&&bundlePreview.summary().datasetCounts().get("cross")==1&&bundlePreview.summary().newCount()==3&&bundlePreview.summary().formalDuplicates()==0,"unified summary aggregates full batch by file, period, type and formal duplicate");var bundleResult=p.confirm(f.div,bundle.id(),1,"saved",false,false);check(bundleResult.added()==3&&f.store.list(f.div,null,null,null).stream().filter(record->record.filename().equals("synthetic.xlsx")).count()>=3,"unified confirmation writes all sheets atomically");check(p.confirm(f.div,bundle.id(),1,"saved",false,false).equals(bundleResult),"unified confirmation retry is idempotent");
  }
  static void conflictsAndRollback(WorkflowPlatformTest.Fixture f)throws Exception {
    var p=f.store.importing();var r=record(f,"WUJIN","cross");var pending=f.pending(f.op,r,"待审不丢失");
    var draft=f.w().draft(f.op,pending.draftId());var j=stage(p,f.div,"cross",List.of(source(withFeedback(r,"正式导入"))),0);
    check(p.preview(f.div,j.id(),0,25).items().get(0).pending()==1,"pending warning counts real submissions");
    expect(ConcurrentModificationException.class,()->p.confirm(f.div,j.id(),1,"saved",false,false));
    check(WorkflowPlatformTest.value(f.store.find(f.op,r.id())).isEmpty(),"import conflict leaves both formal row and pending snapshot intact");
    f.w().approve(f.review,pending.id(),id());f.w().approve(f.div,pending.id(),id());
    check(f.w().submission(f.op,pending.id()).state()==WorkflowContracts.State.APPROVED&&WorkflowPlatformTest.value(f.store.find(f.op,r.id())).equals("待审不丢失")&&f.w().draft(f.op,draft.id()).equals(draft),"import preserves immutable submission and private draft through final publication");
    var stale=stage(p,f.div,"cross",List.of(source(withFeedback(r,"不得覆盖"))),0);var current=f.store.find(f.div,r.id());WorkflowPlatformTest.publish(f.store,f.div,WorkflowPlatformTest.edit(current,"之后的新修改"));
    expect(ConcurrentModificationException.class,()->p.confirm(f.div,stale.id(),1,"overwrite",true,false));check(WorkflowPlatformTest.value(f.store.find(f.op,r.id())).equals("之后的新修改"),"changed baseline cannot overwrite current official");p.cancel(f.div,stale.id(),1);
    List<String> values=new ArrayList<>(r.values());values.set(0,"changed source sequence");var similar=new BusinessRecord("",0,r.dataset(),r.period(),r.organizationId(),values,"similar.xlsx",r.importedAt(),"",Map.of());
    var candidate=stage(p,f.div,"cross",List.of(source(similar)),0);check(p.preview(f.div,candidate.id(),0,25).items().get(0).similar()==1,"same customer period is a candidate, not exact duplicate");
    expect(IllegalArgumentException.class,()->p.confirm(f.div,candidate.id(),1,"saved",false,false));check(p.confirm(f.div,candidate.id(),1,"saved",false,true).added()==1,"explicit new source acknowledgement adds, never merges");
    for(String point:List.of("import-row-written","import-batch-written","import-official-written","import-confirm-written")){
      var job=stage(p,f.div,"negative",List.of(source(newRecord("negative","WUJIN",point)),source(newRecord("negative","JINTAN",point))),0);
      int total=f.store.list(f.div,null,null,null).size(),audit=f.store.diagnostics().get("audit_events");f.fail(point,1);
      expect(IllegalStateException.class,()->p.confirm(f.div,job.id(),1,"saved",false,false));
      check(f.store.list(f.div,null,null,null).size()==total&&f.store.diagnostics().get("audit_events")==audit,"official and audit rollback "+point);
      check(p.preview(f.div,job.id(),0,25).job().state().equals("PREVIEW"),"job transaction rollback "+point);
      check(p.confirm(f.div,job.id(),1,"saved",false,false).added()==2,"retry after failure succeeds "+point);
    }
    int jobs=p.jobs(f.div,0,30).size();f.fail("import-stage-written",1);expect(IllegalStateException.class,()->stage(p,f.div,"multi",List.of(source(newRecord("multi","WUJIN","stage-fault"))),0));check(p.jobs(f.div,0,30).size()==jobs,"failed staging leaves no job");
    var race=stage(p,f.div,"multi",List.of(source(newRecord("multi","WUJIN","race"))),0);
    var results=AccessPlatformTest.race(()->p.confirm(f.div,race.id(),1,"saved",false,false),()->p.confirm(f.div,race.id(),1,"saved",false,false));check(results.get(0).equals(results.get(1))&&results.get(0) instanceof PlatformStore.ImportOutcome,"parallel confirmation produces one batch");
    var choose=stage(p,f.div,"multi",List.of(source(newRecord("multi","WUJIN","choose-race"))),0);
    var choices=AccessPlatformTest.race(()->p.choices(f.div,choose.id(),1,Map.of(1,Choice.SKIP),null),()->p.choices(f.div,choose.id(),1,Map.of(1,Choice.PRESERVE),null));check(choices.stream().filter(o->o instanceof Job).count()==1&&choices.stream().anyMatch(o->o instanceof ConcurrentModificationException),"concurrent choice editors cannot overwrite one another");p.cancel(f.div,choose.id(),2);
  }
  static void restartAndExpiry(WorkflowPlatformTest.Fixture f)throws Exception {
    var j=stage(f.store.importing(),f.div,"multi",List.of(source(newRecord("multi","WUJIN","restart"))),0);f.reopen();
    check(f.store.importing().preview(f.div,j.id(),0,25).items().size()==1,"staging survives H2 restart");
    f.store.importing().confirm(f.div,j.id(),1,"saved",false,false);f.reopen();check(f.store.importing().confirm(f.div,j.id(),1,"saved",false,false).added()==1,"result and repeat protection survive restart");
    var exp=stage(f.store.importing(),f.div,"multi",List.of(source(newRecord("multi","WUJIN","expires"))),0);
    f.clock.advance(Duration.ofMinutes(30));expect(ConcurrentModificationException.class,()->f.store.importing().confirm(f.div,exp.id(),1,"saved",false,false));
    f.store.importing().jobs(f.div,0,25);check(f.store.importing().preview(f.div,exp.id(),0,25).items().isEmpty(),"expired payload removed on cleanup");
    var peer=f.user(Role.DIVISION_ADMIN,"CZ");var revoked=stage(f.store.importing(),peer,"multi",List.of(source(newRecord("multi","WUJIN","revoked"))),0);
    f.store.updateUser(f.root,peer.userId(),peer.identityRevision(),"权限变更",Role.DIVISION_ADMIN,"CZ",true);
    expect(SecurityException.class,()->f.store.importing().confirm(peer,revoked.id(),1,"saved",false,false));
    expect(SecurityException.class,()->f.store.importing().preview(f.store.sessionUser(peer.userId()).actor(),revoked.id(),0,25));
    var oldJob=stage(f.store.importing(),f.div,"multi",List.of(source(newRecord("multi","WUJIN","legacy-month"))),0);f.store.close();
    try(var db=WorkflowPlatformTest.connect(f.dir);var st=db.createStatement()){st.execute("DELETE FROM schema_migrations WHERE version=10");st.execute("DELETE FROM schema_migration_attempts WHERE version=10");st.execute("ALTER TABLE import_jobs DROP COLUMN selected_month");}
    f.open();var upgraded=f.store.importing().preview(f.div,oldJob.id(),0,25).job();check(upgraded.selectedMonth().isBlank(),"schema 9 staging migration preserves old job but does not invent selected month");expect(ConcurrentModificationException.class,()->f.store.importing().confirm(f.div,oldJob.id(),1,"saved",false,false));f.store.importing().cancel(f.div,oldJob.id(),1);
    List<Job> cap=new ArrayList<>();for(int i=0;i<3;i++)cap.add(stage(f.store.importing(),f.div,"multi",List.of(source(newRecord("multi","WUJIN","cap"+i))),0));
    expect(IllegalArgumentException.class,()->stage(f.store.importing(),f.div,"multi",List.of(source(newRecord("multi","WUJIN","overcap"))),0));for(Job job:cap)f.store.importing().cancel(f.div,job.id(),1);
  }
  static void migration(WorkflowPlatformTest.Fixture f)throws Exception {
    int records=f.store.list(f.div,null,null,null).size(),drafts=f.store.diagnostics().get("drafts"),submissions=f.store.diagnostics().get("submissions");f.store.close();
    // Isolated fixture stripped of V4/V5-only tables is an exact V3 schema, with real legacy rows.
    try(var db=WorkflowPlatformTest.connect(f.dir);var st=db.createStatement()){
      st.execute("DROP TABLE workflow_item_events");st.execute("DROP TABLE workflow_record_state");st.execute("ALTER TABLE submission_items DROP COLUMN workflow_state");st.execute("DROP TABLE completion_rules");st.execute("DROP TABLE record_deletions");st.execute("DROP TABLE feedback_deadlines");st.execute("DROP TABLE import_job_rows");st.execute("DROP TABLE import_jobs");st.execute("DELETE FROM schema_migrations WHERE version>=4");st.execute("DELETE FROM schema_migration_attempts WHERE version>=4");
    }
    f.open();check(f.store.schemaVersion()==10,"V3 migrates to V10");check(f.store.list(f.div,null,null,null).size()==records&&f.store.diagnostics().get("drafts")==drafts&&f.store.diagnostics().get("submissions")==submissions,"V3 official, drafts and submissions preserved");
    check(f.store.importing().jobs(f.div,0,25).isEmpty(),"V5 starts empty staging");
  }
  static void schema8Upgrade()throws Exception {
    try(var f=new WorkflowPlatformTest.Fixture()){
      var legacy=record(f,"WUJIN","multi");WorkflowPlatformTest.publish(f.store,f.div,WorkflowPlatformTest.edit(legacy,"V8 已正式值"));
      var rule=f.store.completionRules().save(f.div,"multi",Set.of("feedback"),0);
      var deadline=f.store.deadlines().save(f.div,"multi",legacy.period().key(),"2099-12-31",0);
      var pendingRow=record(f,"WUJIN","negative");var pending=f.pending(f.op,pendingRow,"V8 待支行复核");
      var returnedRow=record(f,"WUJIN","cross");var returned=f.pending(f.op,returnedRow,"V8 退回快照");f.w().reject(f.review,returned.id(),"V8 原退回原因",id());
      var draftRow=record(f,"WUJIN","multi");var draft=f.save(f.op,draftRow,"V8 私人草稿");
      var deleted=record(f,"WUJIN","negative");try(Connection db=WorkflowPlatformTest.connect(f.dir);PreparedStatement st=db.prepareStatement("INSERT INTO record_deletions VALUES(?,?,?,?)")){st.setString(1,deleted.id());st.setString(2,f.div.userId());st.setString(3,Instant.now().toString());st.setString(4,"schema8-fixture");st.executeUpdate();}
      var oldJob=stage(f.store.importing(),f.div,"multi",List.of(source(newRecord("multi","WUJIN","V8 old import preview"))),0);
      try(Connection db=WorkflowPlatformTest.connect(f.dir);PreparedStatement st=db.prepareStatement("UPDATE import_jobs SET selected_month='' WHERE id=?")){st.setString(1,oldJob.id());st.executeUpdate();}
      long legacyVersion=f.store.find(f.div,legacy.id()).version();f.store.close();
      try(Connection db=WorkflowPlatformTest.connect(f.dir);Statement st=db.createStatement()){
        st.execute("DROP TABLE workflow_item_events");st.execute("DROP TABLE workflow_record_state");st.execute("ALTER TABLE submission_items DROP COLUMN workflow_state");st.execute("ALTER TABLE import_jobs DROP COLUMN selected_month");
        st.execute("DELETE FROM schema_migrations WHERE version>8");st.execute("DELETE FROM schema_migration_attempts WHERE version>8");
      }
      f.open();var restored=f.store.find(f.op,legacy.id());check(f.store.schemaVersion()==10&&restored.version()==legacyVersion&&restored.workflowStage()==WorkflowContracts.RowStage.LEGACY_PUBLISHED&&WorkflowPlatformTest.value(restored).equals("V8 已正式值")&&restored.complete(rule),"schema 8 official values, revision, completion rule and legacy status preserved without fabricated final approval");
      check(f.store.completionRules().visible(f.op).get("multi").equals(rule)&&f.store.deadlines().visible(f.op).get(deadline.key()).equals(deadline),"schema 8 required fields and deadline settings preserved");
      check(f.store.sessionUser(f.op.userId()).active()&&f.w().draft(f.op,draft.id()).equals(draft),"schema 8 account and private draft preserved");
      var pendingAfter=f.w().submission(f.op,pending.id());check(pendingAfter.state()==WorkflowContracts.State.SUBMITTED&&f.store.find(f.op,pendingRow.id()).workflowStage()==WorkflowContracts.RowStage.BRANCH_REVIEW,"schema 8 pending operator submission resumes at branch review");
      f.w().approve(f.review,pending.id(),id());check(f.store.find(f.op,pendingRow.id()).values().get(DatasetSchema.get("negative").index("feedback")).isEmpty(),"migrated branch approval still cannot publish");f.w().approve(f.div,pending.id(),id());check(WorkflowPlatformTest.value(f.store.find(f.op,pendingRow.id())).equals("V8 待支行复核"),"migrated pending snapshot publishes only after division review");
      check(f.w().submission(f.op,returned.id()).state()==WorkflowContracts.State.RETURNED&&f.store.find(f.op,returnedRow.id()).workflowStage()==WorkflowContracts.RowStage.RETURNED&&f.store.find(f.op,returnedRow.id()).workflowReason().equals("V8 原退回原因"),"schema 8 returned history and reason preserved");
      try(Connection db=WorkflowPlatformTest.connect(f.dir);PreparedStatement st=db.prepareStatement("SELECT COUNT(*) FROM record_deletions WHERE record_id=?")){st.setString(1,deleted.id());try(ResultSet rs=st.executeQuery()){rs.next();check(rs.getInt(1)==1,"schema 8 deletion marker preserved");}}
      var legacyJob=f.store.importing().preview(f.div,oldJob.id(),0,25).job();check(legacyJob.selectedMonth().isBlank(),"schema 8 import preview does not invent a month");expect(ConcurrentModificationException.class,()->f.store.importing().confirm(f.div,oldJob.id(),1,"saved",false,false));
    }
  }
  static SourceRow source(BusinessRecord r){return new SourceRow(r,"虚构子表",3);}
  static BusinessRecord record(WorkflowPlatformTest.Fixture f,String org,String dataset){var candidate=newRecord(dataset,org,"import-record-"+UUID.randomUUID());var period=Period.parse("2026-09","");var base=new BusinessRecord("",0,dataset,period,org,candidate.values(),candidate.filename(),candidate.importedAt(),"",Map.of());f.store.importRows(f.div,dataset,List.of(base),false,id());return f.store.list(f.div,dataset,null,null).stream().filter(r->r.values().get(0).equals(candidate.values().get(0))).findFirst().orElseThrow();}
  static Job stage(ImportPlatform platform,ActorContext actor,String dataset,List<SourceRow> rows,int examples){return platform.stage(actor,dataset,rows,examples,"2026-09");}
  static Job stageBundle(ImportPlatform platform,ActorContext actor,List<SourceRow> rows,int examples){return platform.stageBundle(actor,rows,examples,"2026-09");}
  static void approveImported(WorkflowPlatformTest.Fixture f,String recordId,ActorContext finalReviewer){var submission=f.w().recordHistory(f.review,recordId,0,50).stream().filter(s->s.mode()==WorkflowContracts.Mode.IMPORT&&s.rowStages().get(recordId)==WorkflowContracts.RowStage.BRANCH_REVIEW).findFirst().orElseThrow();if(submission.ownerId().equals(f.review.userId()))throw new AssertionError("import submission owner unexpectedly equals reviewer: "+submission.ownerId()+" reviewer="+f.review.userId());f.w().approve(f.review,submission.id(),id());f.w().approve(finalReviewer,submission.id(),id());}
  static BusinessRecord withFeedback(BusinessRecord r,String text){var s=DatasetSchema.get(r.dataset());List<String> v=new ArrayList<>(r.values());v.set(s.index(r.dataset().equals("cross")?"cross_feedback":"feedback"),text);return new BusinessRecord("",0,r.dataset(),r.period(),r.organizationId(),v,"synthetic.xlsx",r.importedAt(),"",Map.of());}
  static BusinessRecord newRecord(String dataset,String org,String key){var s=DatasetSchema.get(dataset);List<String> v=new ArrayList<>(Collections.nCopies(s.width(),""));v.set(0,key);v.set(s.customerColumn,"虚构 A2 "+key);v.set(s.codeColumn,"000"+key);v.set(s.branchColumn,Organizations.label(org));var p=Period.parse("20260901-20260915","");if(s.periodColumn>=0)v.set(s.periodColumn,p.key());return new BusinessRecord("",0,dataset,p,org,v,"synthetic.xlsx","2026-09-16T00:00:00Z","",Map.of());}
  interface Work{void run()throws Exception;}
  static void check(boolean v,String message){assertions++;if(!v)throw new AssertionError(message);}
  static void expect(Class<? extends Throwable> type,Work work){assertions++;try{work.run();}catch(Throwable e){if(type.isInstance(e))return;throw new AssertionError("expected "+type+" got "+e,e);}throw new AssertionError("expected "+type);}
}

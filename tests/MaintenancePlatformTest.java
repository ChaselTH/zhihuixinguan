package xinguan.platform;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import static xinguan.platform.WorkflowPlatformTest.*;

public final class MaintenancePlatformTest {
  static int assertions;
  public static void main(String[] args)throws Exception{scopes();auditCleanup();months();migration();System.out.println("MAINTENANCE_PLATFORM_OK assertions="+assertions+" scopes, frozen confirmation, replay, expiry, rollback, month deletion, history and V6 upgrade");}
  static AccessPlatform.AuditFilter filter(String category,String org,String search){return new AccessPlatform.AuditFilter(category,org,"",search,null,null);}
  static String event(Fixture f,ActorContext a,String org,String action){return f.store.workflowTransaction(a,()->f.store.workflowAudit(a,org,"test-record",action,id(),"","","synthetic event"));}
  static void scopes()throws Exception{try(var f=new Fixture()){
    var p=f.store.access();String own=event(f,f.op,"WUJIN","PASSWORD_TEST"),foreign=event(f,f.otherOp,"JINTAN","PASSWORD_TEST"),root=event(f,f.root,"WUJIN","PASSWORD_TEST");
    var filter=filter("security","","PASSWORD_TEST");
    check(p.audit(f.root,filter,0,100).size()==3,"root sees all actors and branches");
    check(p.audit(f.div,filter,0,100).size()==2,"division sees both branches but never super actor");
    var branch=p.audit(f.branch,filter,0,100);check(branch.size()==1&&branch.get(0).id().equals(own),"branch sees employee actions, not just manager self");
    check(f.store.auditEvents(f.div,200).stream().noneMatch(e->e.actor().equals(f.root.name())),"diagnostic audit also hides super");
    expect(SecurityException.class,()->p.audit(f.branch,filter("security","JINTAN",""),0,25));
    expect(SecurityException.class,()->p.audit(f.op,filter("all","",""),0,25));
    for(var a:List.of(f.div,f.branch,f.op,f.review))expect(SecurityException.class,()->f.store.maintenance().previewAudit(a,filter,""));
    for(var a:List.of(f.root,f.branch,f.op,f.review))expect(SecurityException.class,()->f.store.maintenance().previewMonth(a,"2026-09"));
  }}
  static void auditCleanup()throws Exception{try(var f=new Fixture()){
    var m=f.store.maintenance();var r=f.record("WUJIN","multi");
    var submission=f.w().confirm(f.div,f.w().previewDirect(f.div,"multi",List.of(edit(r,"approved synthetic"))).id(),id());
    var scoped=filter("business","WUJIN","");var preview=m.previewAudit(f.root,scoped,submission.id());int n=preview.count();
    check(n>=2,"linked workflow audits selected by same submission filter");
    var peer=f.user(Role.DIVISION_ADMIN,"CZ");expect(SecurityException.class,()->m.confirm(peer,preview.token(),"audit"));
    expect(SecurityException.class,()->m.confirm(f.root,preview.token(),"month"));
    f.fail("audit-purge-written",1);expect(IllegalStateException.class,()->m.confirm(f.root,preview.token(),"audit"));
    check(f.w().auditTrail(f.root,submission.id()).size()==n,"event and links rollback together");
    check(m.confirm(f.root,preview.token(),"audit")==n&&f.w().auditTrail(f.root,submission.id()).isEmpty(),"purge linked events and links atomically");
    int count=f.store.diagnostics().get("audit_events");check(m.confirm(f.root,preview.token(),"audit")==n&&f.store.diagnostics().get("audit_events")==count,"repeat does not delete twice or duplicate summary");
    check(f.store.find(f.div,r.id()).values().contains("approved synthetic")&&f.w().submission(f.root,submission.id()).id().equals(submission.id()),"audit purge preserves formal values and snapshots");
    check(f.store.access().audit(f.root,filter("security","","AUDIT_PURGE"),0,100).size()==1,"purge leaves protected summary");
    expect(IllegalArgumentException.class,()->m.previewAudit(f.root,filter("security","","AUDIT_PURGE"),""));
    event(f,f.div,"WUJIN","TEST_PURGE");var frozen=m.previewAudit(f.root,filter("business","WUJIN","TEST_PURGE"),"");String added=event(f,f.div,"WUJIN","TEST_PURGE");
    m.confirm(f.root,frozen.token(),"audit");var remaining=f.store.access().audit(f.root,filter("business","WUJIN","TEST_PURGE"),0,25);
    check(remaining.size()==1&&remaining.get(0).id().equals(added),"new matching event after preview is never purged");
    var expired=m.previewAudit(f.root,filter("business","WUJIN","TEST_PURGE"),"");f.clock.advance(Duration.ofMinutes(16));expect(ConcurrentModificationException.class,()->m.confirm(f.root,expired.token(),"audit"));
    var race=m.previewAudit(f.root,filter("business","WUJIN","TEST_PURGE"),"");var results=AccessPlatformTest.race(()->m.confirm(f.root,race.token(),"audit"),()->m.confirm(f.root,race.token(),"audit"));check(results.stream().allMatch(x->x.equals(1)),"concurrent same-token confirms replay safely");
    event(f,f.div,"WUJIN","TEST_PURGE");var restart=m.previewAudit(f.root,filter("business","WUJIN","TEST_PURGE"),"");f.reopen();expect(SecurityException.class,()->f.store.maintenance().confirm(f.root,restart.token(),"audit"));
  }}
  static void months()throws Exception{try(var f=new Fixture()){
    var a=f.record("WUJIN","multi");var b=f.record("JINTAN","negative");var c=f.record("WUJIN","cross");
    var next=new BusinessRecord("",0,a.dataset(),Period.parse("2026-10",""),a.organizationId(),a.values(),a.filename(),a.importedAt(),"",Map.of());f.store.importRows(f.div,"multi",List.of(next),false,id());
    var m=f.store.maintenance();expect(IllegalArgumentException.class,()->m.previewMonth(f.div,"2026-13"));expect(IllegalArgumentException.class,()->m.previewMonth(f.div,"2027-01"));
    check(m.months(f.div).equals(List.of("2026-10","2026-09")),"month choices from formal period data");
    var p=m.previewMonth(f.div,"2026-09");check(p.count()==3&&p.counts().values().stream().allMatch(n->n==1),"month includes all three datasets and all branches");
    var draft=f.save(f.op,c,"private preserved");var pending=f.pending(f.op,a,"pending protected");
    check(m.previewMonth(f.div,"2026-09").pending()==1,"pending count in preview");expect(ConcurrentModificationException.class,()->m.confirm(f.div,p.token(),"month"));
    check(f.store.list(f.root,null,null,null).size()==4,"pending created after preview blocks entire deletion");
    f.w().reject(f.review,pending.id(),"synthetic test",id());
    f.store.deadlines().save(f.div,"cross",c.period().key(),"2026-09-20",0);
    var staged=ImportPlatformTest.stage(f.store.importing(),f.div,"multi",List.of(ImportPlatformTest.source(a)),0);
    var direct=f.w().previewDirect(f.div,"multi",List.of(edit(a,"new edit")));
    f.w().confirm(f.div,direct.id(),id());expect(ConcurrentModificationException.class,()->m.confirm(f.div,p.token(),"month"));
    var fresh=m.previewMonth(f.div,"2026-09");
    f.fail("month-delete-written",1);expect(IllegalStateException.class,()->m.confirm(f.div,fresh.token(),"month"));check(f.store.list(f.root,null,null,null).size()==4,"monthly mutation rolls back as a whole");
    check(m.confirm(f.div,fresh.token(),"month")==3&&m.confirm(f.div,fresh.token(),"month")==3,"month deletion is idempotent");
    check(f.store.list(f.root,null,null,null).size()==1&&f.store.list(f.root,null,LocalDate.of(2026,9,1),LocalDate.of(2026,9,30)).isEmpty(),"only selected month's official data disappears");
    check(m.months(f.div).equals(List.of("2026-10"))&&f.store.diagnostics().get("official_records")==1,"selectors and counters exclude removed data");
    check(f.store.deadlines().visible(f.root).isEmpty(),"deleted periods no longer expose feedback reminders");
    expect(IllegalArgumentException.class,()->f.store.find(f.div,a.id()));
    expect(WorkflowContracts.WorkflowException.class,()->f.w().previewDraft(f.op,draft.id(),draft.version()));
    check(f.w().draft(f.op,draft.id()).rows().isEmpty()&&f.w().submission(f.root,pending.id()).rows().size()==1,"deleted draft content is hidden while authorized submission history survives");
    try(var db=connect(f.dir);var st=db.prepareStatement("SELECT payload FROM drafts WHERE id=?")){st.setString(1,draft.id());try(var rs=st.executeQuery()){check(rs.next()&&WorkflowCodec.rows(rs.getString(1)).equals(draft.rows()),"deletion and response redaction preserve stored private draft exactly");}}
    expect(ConcurrentModificationException.class,()->f.store.importing().confirm(f.div,staged.id(),1,"saved",false,false));
    check(f.store.access().audit(f.branch,filter("business","","DATA_MONTH_DELETE"),0,100).size()==2,"monthly deletion trace remains branch-scoped");
    f.reopen();check(f.store.list(f.root,null,null,null).size()==1,"deletion survives restart");
    f.store.importRows(f.div,"cross",List.of(c),false,id());var reimport=f.store.list(f.div,"cross",null,null);check(reimport.size()==1&&!reimport.get(0).id().equals(c.id()),"explicit reimport creates fresh row without reviving old drafts");
    var revoked=f.store.maintenance().previewMonth(f.div,"2026-10");f.store.updateUser(f.root,f.div.userId(),f.div.identityRevision(),"changed",Role.DIVISION_ADMIN,"CZ",true);expect(SecurityException.class,()->f.store.maintenance().confirm(f.div,revoked.token(),"month"));
  }}
  static void migration()throws Exception{try(var f=new Fixture()){
    var r=f.record("WUJIN","multi");f.store.close();
    try(var db=connect(f.dir);var st=db.createStatement()){st.execute("DROP TABLE workflow_item_events");st.execute("DROP TABLE workflow_record_state");st.execute("ALTER TABLE submission_items DROP COLUMN workflow_state");st.execute("ALTER TABLE import_jobs DROP COLUMN selected_month");st.execute("DROP TABLE completion_rules");st.execute("DROP TABLE record_deletions");st.execute("DELETE FROM schema_migrations WHERE version>=7");st.execute("DELETE FROM schema_migration_attempts WHERE version>=7");}
    f.open();check(f.store.schemaVersion()==10&&f.store.find(f.div,r.id()).id().equals(r.id()),"V6 upgrades without losing existing records");
  }
    try(var f=new Fixture()){
      var base=f.record("WUJIN","multi");var crossing=new BusinessRecord("",0,"multi",Period.parse("20261025-20261105",""),base.organizationId(),base.values(),base.filename(),base.importedAt(),"",Map.of());
      f.store.importRows(f.div,"multi",List.of(crossing),false,id());var preview=f.store.maintenance().previewMonth(f.div,"2026-10");
      check(preview.count()==1&&preview.crossMonth()==1,"cross-month scope is explicitly counted for warning");
      f.store.maintenance().confirm(f.div,preview.token(),"month");check(f.store.list(f.div,null,LocalDate.of(2026,11,1),LocalDate.of(2026,11,30)).isEmpty(),"cross-month row removed consistently from both overlapping month views");
    }
    Path failed=Files.createTempDirectory("xinguan-maintenance-upgrade-failure-");expect(java.io.IOException.class,()->{try(var ignored=new PlatformStore(failed,Clock.systemUTC(),p->{if(p.equals("migration-7-step-1"))throw new IllegalStateException("synthetic fault");})) {throw new AssertionError("fault missing");}});
    expect(java.io.IOException.class,()->{try(var ignored=new PlatformStore(failed)){throw new AssertionError("interrupted upgrade accepted");}});
  }
  static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
  interface Work{void run()throws Exception;}
  static void expect(Class<? extends Throwable> type,Work work){assertions++;try{work.run();}catch(Throwable e){if(type.isInstance(e))return;throw new AssertionError("expected "+type+" got "+e,e);}throw new AssertionError("expected "+type);}
}

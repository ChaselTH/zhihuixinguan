package xinguan.platform;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static xinguan.platform.WorkflowPlatformTest.*;

public class FeedbackDeadlinesTest {
  static int assertions;
  public static void main(String[] args)throws Exception{
    try(var f=new Fixture()){
      var r=f.record("WUJIN","multi");String period=r.period().key();var key=new FeedbackDeadlines.Key("multi",period);
      for(var actor:List.of(f.branch,f.op,f.review,f.otherOp))expect(SecurityException.class,()->f.store.deadlines().save(actor,"multi",period,"2026-09-20",0));
      expect(SecurityException.class,()->f.store.deadlines().save(null,"multi",period,"2026-09-20",0));
      expect(IllegalArgumentException.class,()->f.store.deadlines().save(f.root,"multi","unknown-period","2026-09-20",0));
      expect(IllegalArgumentException.class,()->f.store.deadlines().save(f.root,"multi",period,"2026-02-30",0));
      var first=f.store.deadlines().save(f.root,"multi",period,"2026-09-20",0);
      check(first.revision()==1&&first.dueDate().equals(LocalDate.of(2026,9,20)),"super admin may configure period without business edit permission");
      check(f.store.deadlines().visible(f.op).get(key).equals(first)&&!f.store.deadlines().visible(f.otherOp).containsKey(key),"only own scoped periods visible");
      var second=f.store.deadlines().save(f.div,"multi",period,"2026-09-22",1);
      expect(ConcurrentModificationException.class,()->f.store.deadlines().save(f.root,"multi",period,"2026-09-23",1));
      check(f.store.find(f.root,r.id()).version()==r.version(),"setting leaves official revision unchanged");
      var pending=f.pending(f.op,r,"synthetic pending");f.store.deadlines().save(f.root,"multi",period,"2026-09-23",2);
      check(f.w().submission(f.op,pending.id()).state()==WorkflowContracts.State.SUBMITTED,"setting does not freeze or invalidate submitted snapshot");
      f.w().approve(f.review,pending.id(),id());check(f.store.find(f.op,r.id()).complete(),"workflow may complete after configuration");
      f.store.importRows(f.div,"multi",List.of(r),false,id());check(f.store.deadlines().visible(f.root).get(key).revision()==3,"reimport preserves configuration");
      var other=f.record("JINTAN","negative");var otherKey=new FeedbackDeadlines.Key("negative",other.period().key());
      f.store.deadlines().save(f.div,"negative",other.period().key(),"2026-09-24",0);
      check(!f.store.deadlines().visible(f.op).containsKey(otherKey)&&f.store.deadlines().visible(f.otherOp).containsKey(otherKey),"foreign dataset-period not leaked");
      var cross=f.record("WUJIN","cross");f.store.deadlines().save(f.root,"cross",cross.period().key(),"2026-09-25",0);
      check(f.store.deadlines().visible(f.root).size()==3,"three independent dataset deadlines");
      for(String checkpoint:List.of("feedback-deadline-written","feedback-deadline-audited")){
        int audit=f.store.diagnostics().get("audit_events");f.fail(checkpoint,1);
        expect(IllegalStateException.class,()->f.store.deadlines().save(f.root,"multi",period,"2026-09-26",3));
        check(f.store.deadlines().visible(f.root).get(key).revision()==3&&f.store.diagnostics().get("audit_events")==audit,"config and audit rollback together");
      }
      var results=AccessPlatformTest.race(()->f.store.deadlines().save(f.root,"multi",period,"2026-09-26",3),()->f.store.deadlines().save(f.div,"multi",period,"2026-09-27",3));
      check(results.stream().filter(x->x instanceof FeedbackDeadlines.Setting).count()==1&&results.stream().filter(x->x instanceof ConcurrentModificationException).count()==1,"concurrent admins do not overwrite each other");
      f.reopen();check(f.store.deadlines().visible(f.op).get(key).revision()==4,"persists after restart");
      var cleared=f.store.deadlines().save(f.div,"multi",period,"",4);check(cleared.dueDate()==null&&cleared.revision()==5,"clear retains revision to prevent ABA");
      f.store.updateUser(f.root,f.div.userId(),f.div.identityRevision(),"changed",Role.DIVISION_ADMIN,"CZ",true);
      expect(SecurityException.class,()->f.store.deadlines().save(f.div,"multi",period,"2026-09-30",5));
      int records=f.store.list(f.root,null,null,null).size();f.store.close();
      try(var c=connect(f.dir);var st=c.createStatement()){st.execute("DROP TABLE feedback_deadlines");st.execute("DELETE FROM schema_migrations WHERE version=6");st.execute("DELETE FROM schema_migration_attempts WHERE version=6");}
      f.open();check(f.store.schemaVersion()==6&&f.store.list(f.root,null,null,null).size()==records&&f.store.deadlines().visible(f.root).isEmpty(),"V5 to V6 upgrade preserves records and starts unconfigured");
    }
    LocalDate due=LocalDate.of(2026,9,20);Instant midnight=Instant.parse("2026-09-20T16:00:00Z");
    check(!FeedbackTiming.overdue(due,false,midnight.minusMillis(1)),"inclusive deadline day through 23:59:59.999 Beijing");
    check(FeedbackTiming.overdue(due,false,midnight),"overdue at next Beijing midnight");
    check(!FeedbackTiming.overdue(due,true,midnight.plusSeconds(999999))&&!FeedbackTiming.overdue(null,false,midnight),"complete or unconfigured never overdue");
    check(FeedbackTiming.remaining(due,midnight.minusSeconds(3600)).contains("今日截止"),"same-day countdown");
    check(FeedbackTiming.remaining(due,midnight.minusSeconds(3*86400+3600)).contains("剩余 3 天 1 小时"),"countdown calculated against exclusive boundary");
    check(FeedbackTiming.remaining(due,midnight).equals("已超期 1 天"),"calendar-day overdue reminder");
    Path failed=Files.createTempDirectory("xinguan-feedback-migration-fail-");
    expect(java.io.IOException.class,()->{try(var ignored=new PlatformStore(failed,Clock.systemUTC(),p->{if(p.equals("migration-6-step-1"))throw new IllegalStateException("synthetic fault");})){throw new AssertionError("missing fault");}});
    expect(java.io.IOException.class,()->{try(var ignored=new PlatformStore(failed)){throw new AssertionError("partial migration accepted");}});
    System.out.println("FEEDBACK_DEADLINES_OK assertions="+assertions+" authorization, scope, atomic audit, concurrency, date boundaries, restart and V5 upgrade");
  }
  static void check(boolean v,String label){assertions++;if(!v)throw new AssertionError(label);}
  interface Work{void run()throws Exception;}
  static void expect(Class<? extends Throwable> type,Work work){assertions++;try{work.run();}catch(Throwable t){if(type.isInstance(t))return;throw new AssertionError(t);}throw new AssertionError("expected "+type);}
}

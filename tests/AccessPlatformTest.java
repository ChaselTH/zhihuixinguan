package xinguan.platform;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class AccessPlatformTest {
  static int assertions;
  public static void main(String[] args)throws Exception {
    try(var f=new WorkflowPlatformTest.Fixture()) {
      var p=f.store.access();var otherManager=f.user(Role.BRANCH_ADMIN,"JINTAN");
      expect(IllegalArgumentException.class,()->p.apply("123","测试","WUJIN"));
      int beforeNullRoleApplications=p.applications(f.root,true,0,100).size();long beforeNullRoleUnread=f.n().unreadCount(f.root);
      expect(IllegalArgumentException.class,()->p.apply("799000001","无管理员无角色","LIYANG",(Role)null));
      expect(IllegalArgumentException.class,()->p.apply("799000002","有管理员无角色","WUJIN",(Role)null));
      check(p.applications(f.root,true,0,100).size()==beforeNullRoleApplications&&f.n().unreadCount(f.root)==beforeNullRoleUnread,"null target role never creates application, placeholder or notice");
      expect(IllegalArgumentException.class,()->p.apply("800000001","","WUJIN"));
      expect(IllegalArgumentException.class,()->p.apply("800000001","测试","UNKNOWN"));
      p.apply("800000001","虚构申请人 <script>测试</script>","WUJIN",Role.OPERATOR);
      var r=request(p,f.root,"800000001");check(r.route().equals("BRANCH"),"route to own branch");
      expect(IllegalArgumentException.class,()->f.store.createUser(f.root,"800000001","手工冲突",Role.OPERATOR,"WUJIN"));
      check(p.applications(f.branch,true,0,25).size()==1,"own branch list");
      check(p.applications(otherManager,true,0,25).isEmpty(),"other branch list isolated");
      expect(SecurityException.class,()->p.application(otherManager,r.id()));
      expect(SecurityException.class,()->p.applications(f.op,true,0,25));
      expect(SecurityException.class,()->p.application(new ActorContext("forged","forged",Role.SUPER_ADMIN,"CZ"),r.id()));
      check(f.n().unreadCount(f.branch)==1&&f.n().unreadCount(otherManager)==0,"new application notice routed");
      var notice=f.n().inbox(f.branch,true,0,25).get(0);check(p.noticeApplication(f.branch,notice.id()).equals(r.id()),"notice target authorized");
      expect(SecurityException.class,()->p.notice(otherManager,notice.id()));expect(SecurityException.class,()->f.n().markRead(otherManager,notice.id()));
      f.n().markRead(f.branch,notice.id());f.n().markRead(f.branch,notice.id());check(f.n().unreadCount(f.branch)==0&&f.n().unreadCount(f.root)==1,"personal read idempotent");
      expect(IllegalArgumentException.class,()->p.apply("800000001","冒名重复","JINTAN"));check(request(p,f.root,"800000001").name().equals(r.name()),"duplicate never replaces existing identity or scope");
      expect(SecurityException.class,()->p.decide(f.branch,r.id(),1,"APPROVE",Role.BRANCH_ADMIN,"",id()));
      expect(IllegalArgumentException.class,()->p.decide(f.branch,r.id(),1,"REJECT",null,"",id()));
      expect(ConcurrentModificationException.class,()->p.decide(f.branch,r.id(),0,"APPROVE",Role.OPERATOR,"",id()));
      p.decide(f.branch,r.id(),1,"ESCALATE",null,"需要支行管理员权限",id());
      check(p.application(f.branch,r.id()).state().equals("ESCALATED"),"escalation persisted");
      expect(SecurityException.class,()->p.decide(f.branch,r.id(),2,"APPROVE",Role.OPERATOR,"",id()));
      String requestId=id();var decision=p.decide(f.div,r.id(),2,"APPROVE",Role.OPERATOR,"已核验",requestId);
      check(decision.created()!=null&&decision.created().user().mustChangePassword(),"approved initial password with first-login gate");
      check(f.store.authenticateUser("800000001",decision.created().initialPassword())!=null,"new password authenticates");
      var replay=p.decide(f.div,r.id(),2,"APPROVE",Role.OPERATOR,"已核验",requestId);
      check(replay.replayed()&&replay.created()==null,"repeat never redisplays credential");
      expect(IllegalArgumentException.class,()->p.decide(f.div,r.id(),2,"APPROVE",Role.BRANCH_ADMIN,"已核验",requestId));
      expect(SecurityException.class,()->p.decide(f.div,r.id(),3,"APPROVE",Role.BRANCH_ADMIN,"",id()));
      expect(IllegalArgumentException.class,()->p.apply("800000001","已有账号","WUJIN"));check(p.applications(f.root,true,0,25).isEmpty(),"existing account application generic no mutation");
      check(f.n().inbox(f.root,false,0,25).toString().contains(decision.created().initialPassword())==false,"password absent from notices");
      check(p.audit(f.root,filter("security"),0,100).toString().contains(decision.created().initialPassword())==false,"password absent from audit including details");
      p.apply("800000002","分行申请","CZ",Role.DIVISION_ADMIN);var division=request(p,f.root,"800000002");
      expect(SecurityException.class,()->p.application(f.div,division.id()));expect(SecurityException.class,()->p.decide(f.div,division.id(),1,"APPROVE",Role.DIVISION_ADMIN,"",id()));
      check(p.decide(f.root,division.id(),1,"APPROVE",Role.DIVISION_ADMIN,"",id()).created().user().role()==Role.DIVISION_ADMIN,"division application only super approves");
      expect(IllegalStateException.class,()->p.apply("800000003","无人管理支行","LIYANG",Role.OPERATOR));
      int faultCase=0;for(String point:List.of("access-account-written","notification-written","access-decision-written")) {
        String number="81000000"+(++faultCase);p.apply(number,"回滚测试","WUJIN",Role.OPERATOR);var rollback=request(p,f.root,number);int users=f.store.listUsers(f.root).size();long unread=f.n().unreadCount(f.root);int audit=p.audit(f.root,filter("security"),0,100).size();
        f.fail(point,1);expect(IllegalStateException.class,()->p.decide(f.div,rollback.id(),1,"APPROVE",Role.OPERATOR,"",id()));
        check(f.store.listUsers(f.root).size()==users&&f.n().unreadCount(f.root)==unread&&p.audit(f.root,filter("security"),0,100).size()==audit,"account notification audit roll back: "+point);
        check(p.application(f.root,rollback.id()).state().equals("PENDING"),"failed decision preserves pending application");
        p.decide(f.div,rollback.id(),1,"REJECT",null,"测试结束",id());
      }
      f.fail("notification-written",1);expect(IllegalStateException.class,()->p.apply("820000001","申请回滚","WUJIN",Role.OPERATOR));p.apply("820000001","申请重试","WUJIN",Role.OPERATOR);check(request(p,f.root,"820000001").name().equals("申请重试"),"application and number lock rollback");
      p.apply("830000001","并发申请","WUJIN",Role.OPERATOR);var race=request(p,f.root,"830000001");String key=id();var results=race(()->p.decide(f.div,race.id(),1,"APPROVE",Role.OPERATOR,"",key),()->p.decide(f.div,race.id(),1,"APPROVE",Role.OPERATOR,"",key));
      check(results.stream().filter(v->v instanceof AccessPlatform.Decision d&&d.created()!=null).count()==1,"same request race reveals password once");
      check(results.stream().filter(v->v instanceof AccessPlatform.Decision d&&d.replayed()).count()==1,"same request race idempotent");
      p.apply("830000002","竞争审批","WUJIN",Role.OPERATOR);var competition=request(p,f.root,"830000002");var competed=race(()->p.decide(f.div,competition.id(),1,"APPROVE",Role.OPERATOR,"",id()),()->p.decide(f.branch,competition.id(),1,"REJECT",null,"核验失败",id()));check(competed.stream().filter(v->v instanceof AccessPlatform.Decision).count()==1,"different decisions only one wins");
      String hash="a".repeat(64);p.acknowledge(f.op,hash,AccessPlatform.SAFETY_VERSION);p.acknowledge(f.op,hash,AccessPlatform.SAFETY_VERSION);
      expect(IllegalArgumentException.class,()->p.acknowledge(f.op,hash,"stale"));
      f.fail("access-safety-written",1);expect(IllegalStateException.class,()->p.acknowledge(f.op,"b".repeat(64),AccessPlatform.SAFETY_VERSION));
      auditTests(f);
      for(var pendingApp:p.applications(f.branch,true,0,100))p.decide(f.branch,pendingApp.id(),pendingApp.revision(),"REJECT",null,"清理合成待办",id());
      p.apply("840000001","责任待办","WUJIN",Role.OPERATOR);var responsibility=request(p,f.root,"840000001");UserAccount branchBefore=f.store.sessionUser(f.branch.userId());expect(IllegalStateException.class,()->f.store.updateUser(f.root,branchBefore.id(),branchBefore.revision(),branchBefore.name(),Role.BRANCH_ADMIN,"JINTAN",true));p.decide(f.branch,responsibility.id(),1,"REJECT",null,"责任测试结束",id());UserAccount branchUser=f.store.sessionUser(f.branch.userId());f.store.updateUser(f.root,branchUser.id(),branchUser.revision(),branchUser.name(),Role.BRANCH_ADMIN,"JINTAN",true);
      f.store.updateUser(f.root,otherManager.userId(),otherManager.identityRevision(),"迁移人员",Role.BRANCH_ADMIN,"WUJIN",true);
      expect(SecurityException.class,()->p.applications(otherManager,true,0,25));
      f.reopen();check(f.store.access().application(f.div,r.id()).state().equals("APPROVED"),"application decision survives restart");
      try(Connection db=WorkflowPlatformTest.connect(f.dir);Statement st=db.createStatement();ResultSet rs=st.executeQuery("SELECT session_id FROM security_acknowledgements")){check(rs.next()&&rs.getString(1).equals(hash)&&!rs.next(),"one hashed-session ack persisted; duplicate and failed ack absent");}
    }
    migrationV2();System.out.println("ACCESS_PLATFORM_OK assertions="+assertions+" approval isolation, races, atomic rollback, V2 upgrade and restart");
  }
  static void auditTests(WorkflowPlatformTest.Fixture f) {
    var p=f.store.access();var own=f.record("WUJIN","multi");var other=f.record("JINTAN","multi");
    WorkflowPlatformTest.publish(f.store,f.div,WorkflowPlatformTest.edit(f.store.find(f.div,own.id()),"已核验 <script>安全转义</script>"));
    check(p.audit(f.op,filter("business"),0,100).stream().allMatch(e->e.organization().equals("WUJIN")),"business audit branch scope");
    expect(SecurityException.class,()->p.audit(f.op,filter("security"),0,25));
    expect(SecurityException.class,()->p.audit(f.op,new AccessPlatform.AuditFilter("business","JINTAN","","",null,null),0,25));
    check(p.audit(f.div,filter("business"),0,100).stream().anyMatch(e->e.recordId().equals(other.id())),"division audit all branches");
    var row=p.audit(f.op,new AccessPlatform.AuditFilter("business","","multi",own.id(),null,null),0,25).stream().filter(e->e.after().contains("已核验 <script>安全转义</script>")).findFirst().orElseThrow();
    check(row.after().contains("已核验 <script>安全转义</script>")&&row.customer().contains("虚构企业"),"audit decoded before after and customer");
    var privateRecord=f.record("WUJIN","multi");f.save(f.op,privateRecord,"PRIVATE_UNSUBMITTED_A1");check(!p.audit(f.root,filter("business"),0,100).toString().contains("PRIVATE_UNSUBMITTED_A1"),"private drafts never enter shared audit");
    var pending=f.pending(f.op,f.record("WUJIN","negative"),"待复核");
    check(p.audit(f.branch,new AccessPlatform.AuditFilter("business","","",pending.id(),null,null),0,100).stream().anyMatch(e->e.submissionId().equals(pending.id())),"workflow audit linked without duplicating writes");
    expect(IllegalArgumentException.class,()->p.audit(f.root,new AccessPlatform.AuditFilter("business","","","",LocalDate.of(2026,10,1),LocalDate.of(2026,9,1)),0,25));
    check(p.audit(f.root,new AccessPlatform.AuditFilter("business","","","",LocalDate.of(2099,1,1),null),0,25).isEmpty(),"date filtering");
    check(p.audit(f.branch,filter("security"),0,100).stream().allMatch(e->e.organization().equals("WUJIN")&&!e.actor().equals(f.root.name())),"branch administrative audit includes only own branch and hides super operations");
    expect(IllegalArgumentException.class,()->p.applications(f.root,true,-1,25));expect(IllegalArgumentException.class,()->p.audit(f.root,filter("business"),0,101));
  }
  static void migrationV2()throws Exception {
    Path dir=Files.createTempDirectory("xinguan-a1-v2-");
    try(Connection db=WorkflowPlatformTest.connect(dir);Statement st=db.createStatement()) {
      st.execute("CREATE TABLE schema_migrations(version INT PRIMARY KEY,checksum VARCHAR(64) NOT NULL,applied_at VARCHAR(40) NOT NULL)");int version=0;
      for(String resource:List.of("V001__foundation.sql","V002__workflow_platform.sql")){
        String sql=new String(AccessPlatformTest.class.getResourceAsStream("/db/"+resource).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);for(String step:sql.split(";"))if(!step.isBlank())st.execute(step);
        try(PreparedStatement insert=db.prepareStatement("INSERT INTO schema_migrations VALUES(?,?,?)")){insert.setInt(1,++version);insert.setString(2,Codec.hash(sql));insert.setString(3,Instant.now().toString());insert.executeUpdate();}
      }
      st.execute("INSERT INTO access_requests VALUES('old-application','880000001','虚构旧申请','WUJIN','PENDING','2026-09-01T00:00:00Z',NULL,NULL)");
    }
    try(PlatformStore store=new PlatformStore(dir)){String password=id();store.bootstrapSuperAdmin("880000002",password);var actor=store.authenticateUser("880000002",password).actor();check(store.schemaVersion()==10,"V2 upgrades to V10");check(store.access().applications(actor,true,0,25).get(0).number().equals("880000001"),"preallocated application data preserved");expect(IllegalArgumentException.class,()->store.access().apply("880000001","重复旧申请","JINTAN",Role.OPERATOR));check(store.access().applications(actor,true,0,25).size()==1,"V2 pending numbers backfilled");}
    Path failed=Files.createTempDirectory("xinguan-a1-failed-upgrade-");expect(java.io.IOException.class,()->{try(var ignored=new PlatformStore(failed,Clock.systemUTC(),point->{if(point.equals("migration-3-step-2"))throw new IllegalStateException("synthetic fault");})) {throw new AssertionError("fault missing");}});
    expect(java.io.IOException.class,()->{try(var ignored=new PlatformStore(failed)) {throw new AssertionError("partial migration accepted");}});
  }
  static AccessPlatform.AuditFilter filter(String category){return new AccessPlatform.AuditFilter(category,"","","",null,null);}
  static AccessPlatform.Application request(AccessPlatform p,ActorContext a,String number){return p.applications(a,true,0,100).stream().filter(r->r.number().equals(number)).findFirst().orElseThrow();}
  static String id(){return UUID.randomUUID().toString();}
  interface Work{void run()throws Exception;}
  static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
  static void expect(Class<? extends Throwable> type,Work work){assertions++;try{work.run();}catch(Throwable e){if(type.isInstance(e))return;throw new AssertionError("expected "+type+" got "+e,e);}throw new AssertionError("expected "+type);}
  static List<Object> race(Callable<Object> a,Callable<Object> b)throws Exception {ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);try{List<Future<Object>> jobs=new ArrayList<>();for(var call:List.of(a,b))jobs.add(pool.submit(()->{ready.countDown();go.await();try{return call.call();}catch(RuntimeException e){return e;}}));check(ready.await(10,TimeUnit.SECONDS),"concurrent workers ready");go.countDown();return List.of(jobs.get(0).get(30,TimeUnit.SECONDS),jobs.get(1).get(30,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
}

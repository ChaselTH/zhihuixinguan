import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import xinguan.platform.*;

public final class IdentityTest {
  static int assertions;
  static final String SUPER_NUMBER="000000001";
  public static void main(String[] args)throws Exception{
    Path dir=Files.createTempDirectory("xinguan-identity-test-");String bootstrap=UUID.randomUUID().toString(),newRootPassword=UUID.randomUUID().toString();
    try(PlatformStore store=new PlatformStore(dir)){
      store.bootstrapSuperAdmin(SUPER_NUMBER,bootstrap);
      AuthService auth=new AuthService(store);AuthService.Session first=auth.authenticate("synthetic-1",SUPER_NUMBER,bootstrap);
      check(first!=null&&first.authNumber.equals(SUPER_NUMBER)&&first.actor.role()==Role.SUPER_ADMIN,"synthetic seed identity and leading zeros");
      check(!first.mustChangePassword,"super is exempt from initial password change");
      check(!first.safetyAccepted(),"super still requires safety acknowledgement");
      expect(IllegalArgumentException.class,()->auth.acknowledgeSafety(first,"obsolete"));
      check(!first.safetyAccepted(),"failed safety acknowledgement does not open gate");
      auth.acknowledgeSafety(first,AccessPlatform.SAFETY_VERSION);auth.acknowledgeSafety(first,AccessPlatform.SAFETY_VERSION);
      check(first.safetyAccepted(),"successful safety acknowledgement opens this session only");
      check(!auth.authenticate("synthetic-second",SUPER_NUMBER,bootstrap).safetyAccepted(),"each new login requires fresh safety acknowledgement");
      try(Connection db=DriverManager.getConnection("jdbc:h2:file:"+dir.resolve("platform/records").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE","sa","");Statement st=db.createStatement();ResultSet rs=st.executeQuery("SELECT session_id FROM security_acknowledgements")){check(rs.next()&&rs.getString(1).matches("[a-f0-9]{64}")&&!rs.getString(1).equals(first.token)&&!rs.next(),"persist only session hash; never raw login token; repeat ack idempotent");}
      check(store.list(first.actor,null,null,null).isEmpty()&&store.listUsers(first.actor).size()==1,"super can view data and manage users immediately");
      expect(IllegalArgumentException.class,()->auth.changePassword(first,bootstrap));
      auth.changePassword(first,newRootPassword);
      expect(SecurityException.class,()->store.listUsers(first.actor));
      ActorContext root=store.authenticateUser(SUPER_NUMBER,newRootPassword).actor();
      var div=store.createUser(root,"901000001","测试分行",Role.DIVISION_ADMIN,"CZ");var w=store.createUser(root,"901000002","测试武进",Role.BRANCH_ADMIN,"WUJIN");var j=store.createUser(root,"901000003","测试金坛",Role.OPERATOR,"JINTAN");
      check(div.initialPassword().length()>=16,"random initial password");
      check(store.authenticateUser(div.user().authNumber(),div.initialPassword())!=null,"created initial password authenticates");
      for(var created:List.of(div,w,j))check(created.user().mustChangePassword(),"non-super initial change remains mandatory");
      AuthService.Session forced=auth.authenticate("synthetic-force",div.user().authNumber(),div.initialPassword());expect(SecurityException.class,()->auth.acknowledgeSafety(forced,AccessPlatform.SAFETY_VERSION));
      expect(SecurityException.class,()->store.listUsers(div.user().actor()));
      ActorContext division=activate(store,div),branch=activate(store,w);
      check(store.listUsers(branch).isEmpty(),"branch cannot list other branches or administrators");
      var op=store.createUser(branch,"901000004","本支行操作员",Role.OPERATOR,"WUJIN");
      check(store.listUsers(branch).size()==1,"branch sees own managed staff");
      expect(SecurityException.class,()->store.createUser(branch,"901000005","越界",Role.OPERATOR,"JINTAN"));
      expect(SecurityException.class,()->store.createUser(division,"901000005","同级",Role.DIVISION_ADMIN,"CZ"));
      expect(SecurityException.class,()->store.resetUserPassword(branch,j.user().id(),j.user().revision()));
      expect(SecurityException.class,()->store.updateUser(root,root.userId(),root.identityRevision(),"超管",Role.SUPER_ADMIN,"CZ",false));
      expect(IllegalArgumentException.class,()->store.createUser(root,"901000004","重复",Role.OPERATOR,"WUJIN"));
      ActorContext oldOp=activate(store,op);
      store.importRows(division,"multi",List.of(FoundationTest.candidate("multi","WUJIN","IDENTITY")),false,"identity-import-test-001");
      check(store.list(oldOp,null,null,null).size()==1,"persisted identity data scope");
      UserAccount latest=store.sessionUser(op.user().id());store.updateUser(root,latest.id(),latest.revision(),"转金坛",Role.OPERATOR,"JINTAN",true);
      expect(SecurityException.class,()->store.list(oldOp,null,null,null));
      expect(SecurityException.class,()->store.changeOwnPassword(oldOp,UUID.randomUUID().toString()));
      ActorContext transferred=store.sessionUser(latest.id()).actor();check(store.list(transferred,null,null,null).isEmpty(),"transfer does not move business data");
      UserAccount d=store.sessionUser(div.user().id());store.updateUser(root,d.id(),d.revision(),d.name(),d.role(),d.organizationId(),false);
      expect(SecurityException.class,()->store.importRows(division,"multi",List.of(),false,"disabled-import-test-001"));
      check(store.authenticateUser(div.user().authNumber(),div.initialPassword())==null,"disabled user cannot login");
      check(store.auditEvents(root,100).stream().noneMatch(e->(e.before()+e.after()).contains(op.initialPassword())),"password omitted from audit");
      UserAccount resetTarget=store.sessionUser(j.user().id());var reset=store.resetUserPassword(root,resetTarget.id(),resetTarget.revision());check(reset.user().mustChangePassword()&&!reset.initialPassword().equals(j.initialPassword()),"reset returns new password and forces change");
      new AuthService(store);check(store.authenticateUser(SUPER_NUMBER,bootstrap)==null,"bootstrap never resets modified password");
      AuthService rate=new AuthService(store);for(int i=0;i<5;i++)check(rate.authenticate("synthetic-rate",SUPER_NUMBER,"wrong")==null,"wrong password rejected");check(rate.authenticate("synthetic-rate",SUPER_NUMBER,newRootPassword)==null,"temporary rate limit");
    }
    Path keyFile=dir.resolve("platform/initial-password.key"),backupKey=dir.resolve("platform/initial-password.key.bak");byte[] keyBytes=Files.readAllBytes(keyFile);Files.deleteIfExists(keyFile);Files.deleteIfExists(backupKey);expect(IOException.class,()->{try(var ignored=new PlatformStore(dir)){throw new AssertionError("missing key accepted");}});Files.write(keyFile,keyBytes);Files.write(backupKey,keyBytes);try(PlatformStore restored=new PlatformStore(dir)){check(restored.authenticateUser(SUPER_NUMBER,newRootPassword)!=null,"same backup key restores encrypted credentials");}
    // Reproduce the previous release's flag in this isolated test database only.
    String dbUrl="jdbc:h2:file:"+dir.resolve("platform/records").toAbsolutePath().toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE";
    try(Connection db=DriverManager.getConnection(dbUrl,"sa","");Statement statement=db.createStatement()){
      check(statement.executeUpdate("UPDATE users SET must_change_password=TRUE WHERE role='SUPER_ADMIN'")==1,"synthetic old-version super requires change");
    }
    try(PlatformStore reopened=new PlatformStore(dir)){
      UserAccount before=reopened.authenticateUser(SUPER_NUMBER,newRootPassword);
      new AuthService(reopened);UserAccount after=reopened.authenticateUser(SUPER_NUMBER,newRootPassword);
      check(after!=null&&!after.mustChangePassword(),"upgrade removes super gate while preserving changed password");
      check(after.revision()==before.revision()+1,"upgrade revokes only affected old session revision");
      check(reopened.listUsers(after.actor()).stream().anyMatch(u->u.authNumber().equals("901000003")&&u.mustChangePassword()),"upgrade retains ordinary account first-change flag");
      expect(SecurityException.class,()->reopened.listUsers(before.actor()));
      new AuthService(reopened);check(reopened.sessionUser(after.id()).revision()==after.revision(),"exemption upgrade is idempotent");
    }
    testRecentLogin();
    System.out.println("IDENTITY_TEST_OK assertions="+assertions);
  }
  static void testRecentLogin()throws Exception{
    Path dir=Files.createTempDirectory("xinguan-recent-login-test-");MutableClock clock=new MutableClock();String initial=UUID.randomUUID().toString(),changed=UUID.randomUUID().toString();
    try(PlatformStore store=new PlatformStore(dir)){
      store.bootstrapSuperAdmin(SUPER_NUMBER,initial);
      AuthService auth=new AuthService(store,clock);AuthService.Session first=auth.authenticate("recent-1",SUPER_NUMBER,initial),second=auth.authenticate("recent-2",SUPER_NUMBER,initial);
      AuthService another=new AuthService(store,clock);
      check(auth.passwordChangeExpired(null),"anonymous session cannot change password");
      check(!auth.passwordChangeExpired(first),"fresh login authorizes password change");
      AuthService.Session forged=new AuthService.Session(first.token,first.csrf,first.authenticatedAt,store.sessionUser(first.actor.userId()));
      expect(SecurityException.class,()->auth.changePassword(forged,changed));
      clock.advance(899);check(!auth.passwordChangeExpired(first),"recent login valid before 15-minute boundary");
      first.expiresAt=clock.instant().getEpochSecond()+28800;clock.advance(1);
      check(auth.passwordChangeExpired(first),"rolling session expiry does not extend recent-login proof");
      expect(SecurityException.class,()->auth.changePassword(first,changed));
      check(store.authenticateUser(SUPER_NUMBER,initial)!=null,"expired attempt does not change credentials");
      AuthService.Session fresh=auth.authenticate("recent-new",SUPER_NUMBER,initial);
      clock.advance(-1);check(auth.passwordChangeExpired(fresh),"clock moving backwards fails closed");clock.advance(1);
      check(!auth.passwordChangeExpired(fresh),"relogin restores password-change permission");
      AuthService.Session other=another.authenticate("recent-other",SUPER_NUMBER,initial);
      auth.changePassword(fresh,changed);
      check(auth.passwordChangeExpired(fresh)&&auth.passwordChangeExpired(second),"all same-account sessions revoked after password change");
      check(!another.passwordChangeExpired(other),"separate auth instance still has a fresh but revoked identity");
      expect(SecurityException.class,()->another.changePassword(other,UUID.randomUUID().toString()));
      check(store.authenticateUser(SUPER_NUMBER,initial)==null&&store.authenticateUser(SUPER_NUMBER,changed)!=null,"only new password authenticates");
      check(store.auditEvents(store.authenticateUser(SUPER_NUMBER,changed).actor(),10).stream().noneMatch(e->(e.before()+e.after()).contains(changed)),"new password is absent from audit data");
    }
  }
  static final class MutableClock extends Clock{
    private Instant time=Instant.parse("2026-09-11T00:00:00Z");
    void advance(long seconds){time=time.plusSeconds(seconds);}
    public Instant instant(){return time;}
    public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone){return this;}
  }
  static ActorContext activate(PlatformStore store,PlatformStore.CreatedUser user){store.changeOwnPassword(user.user().actor(),UUID.randomUUID().toString());return store.sessionUser(user.user().id()).actor();}
  static void check(boolean okay,String message){assertions++;if(!okay)throw new AssertionError(message);}
  interface Action{void run()throws Exception;}
  static void expect(Class<? extends Throwable> type,Action action){assertions++;try{action.run();}catch(Throwable e){if(type.isInstance(e))return;throw new AssertionError(e);}throw new AssertionError("Expected "+type);}
}

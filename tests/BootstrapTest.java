import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import xinguan.platform.*;

public final class BootstrapTest {
  static int assertions;
  public static void main(String[] args)throws Exception {
    Path app=Path.of(args[0]),dir=Files.createTempDirectory("xinguan-bootstrap-test-"),config=dir.resolve(BootstrapConfig.FILE_NAME);
    String number="000000001",password=UUID.randomUUID().toString(),changed=UUID.randomUUID().toString();
    check(!Files.exists(app.resolve(BootstrapConfig.FILE_NAME)),"normal build excludes private initialization file");
    Properties example=new Properties();try(Reader in=Files.newBufferedReader(app.resolve("bootstrap.example.properties"),StandardCharsets.UTF_8)){example.load(in);}
    check(example.getProperty("auth_number").isEmpty()&&example.getProperty("password").isEmpty(),"distributed example contains no defaults");
    try(PlatformStore users=new PlatformStore(dir.resolve("data"))) {
      expect(IOException.class,()->BootstrapConfig.initialize(users,config));
      expect(IllegalStateException.class,()->new AuthService(users));
      check(!users.hasUsers(),"missing config creates no fallback user");
      Files.copy(app.resolve("bootstrap.example.properties"),config);
      expect(IOException.class,()->BootstrapConfig.initialize(users,config));
      write(config,"not-a-number",password);expect(IOException.class,()->BootstrapConfig.initialize(users,config));
      write(config,number,"short");expect(IOException.class,()->BootstrapConfig.initialize(users,config));
      Files.writeString(config,"x".repeat(8193));expect(IOException.class,()->BootstrapConfig.initialize(users,config));
      Path directory=Files.createDirectory(dir.resolve("not-a-file"));expect(IOException.class,()->BootstrapConfig.initialize(users,directory));
      check(!users.hasUsers(),"invalid settings never create a user");
      write(config,number,password);check(BootstrapConfig.initialize(users,config),"valid local settings initialize once");
      AuthService auth=new AuthService(users);var session=auth.authenticate("synthetic-bootstrap",number,password);
      check(session!=null&&!session.mustChangePassword&&session.actor.role()==Role.SUPER_ADMIN,"local super retains leading zeros and first-change exemption");
      auth.changePassword(session,changed);
      write(config,"000000002",UUID.randomUUID().toString());
      check(!BootstrapConfig.initialize(users,config),"existing account ignores replacement config");
      check(!BootstrapConfig.initialize(users,dir.resolve("missing.properties")),"upgrade does not require original config");
      check(users.diagnostics().get("users")==1&&users.authenticateUser(number,changed)!=null&&users.authenticateUser(number,password)==null,"changed password and sole account preserved");
      check(users.auditEvents(users.authenticateUser(number,changed).actor(),10).stream().noneMatch(e->(e.before()+e.after()).contains(password)||(e.before()+e.after()).contains(changed)),"credentials absent from audit");
    }
    try(PlatformStore users=new PlatformStore(dir.resolve("data"))){new AuthService(users);check(users.authenticateUser(number,changed)!=null,"restart preserves account without reinitialization");}
    System.out.println("BOOTSTRAP_TEST_OK assertions="+assertions+" synthetic local configuration only");
  }
  static void write(Path file,String number,String password)throws Exception{Properties p=new Properties();p.setProperty("auth_number",number);p.setProperty("password",password);try(Writer out=Files.newBufferedWriter(file,StandardCharsets.UTF_8)){p.store(out,"Synthetic fixture only");}}
  static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
  interface Action{void run()throws Exception;}
  static void expect(Class<? extends Throwable> type,Action action)throws Exception{assertions++;try{action.run();}catch(Exception e){if(type.isInstance(e))return;throw e;}throw new AssertionError("Expected "+type);}
}

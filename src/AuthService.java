import com.sun.net.httpserver.*;
import java.security.*;
import java.time.Clock;
import java.util.*;
import xinguan.platform.*;

/** Persistent accounts with revocable in-memory sessions; no legacy password fallback. */
final class AuthService {
  static final String COOKIE="ZXSESSION";
  private final PlatformStore users;
  private final Clock clock;
  private final SecureRandom random=new SecureRandom();
  private final Map<String,Session> sessions=new HashMap<>();
  private final Map<String,Attempt> attempts=new LinkedHashMap<>();
  private final Map<String,Long> loginTokens=new HashMap<>();
  private final Map<String,Long> applicationTokens=new HashMap<>();
  private final Map<String,Attempt> applicationAttempts=new HashMap<>();
  AuthService(PlatformStore users){this(users,Clock.systemUTC());}
  AuthService(PlatformStore users,Clock clock){this.users=users;this.clock=clock;users.prepareSuperAdminPolicy();if(!users.hasUsers())throw new IllegalStateException("账号库尚未初始化，请配置本地初始化文件后启动");}
  private long now(){return clock.instant().getEpochSecond();}
  synchronized Session authenticate(String remote,String number,String password){
    purge();String account=number==null?"":number.strip();String[] keys={"ip:"+remote,"account:"+account};long now=now();for(String key:keys){Attempt state=attempts.get(key);if(state!=null&&state.lockedUntil>now)return null;}
    UserAccount user=users.authenticateUser(account,password);
    if(user==null){for(String key:keys){Attempt state=attempts.computeIfAbsent(key,k->new Attempt(now));state.count++;if(state.count>=5)state.lockedUntil=now+60;}return null;}
    attempts.remove("account:"+account);Session session=new Session(token(32),token(24),now,user);sessions.put(session.token,session);return session;
  }
  synchronized Session session(HttpExchange x){purge();String token=cookie(x,COOKIE);Session s=sessions.get(token);if(s==null)return null;UserAccount u=users.sessionUser(s.actor.userId());if(u==null||!u.active()||u.revision()!=s.actor.identityRevision()){sessions.remove(token);return null;}s.expiresAt=now()+28800;return s;}
  synchronized boolean passwordChangeExpired(Session s){long at=now();return s==null||sessions.get(s.token)!=s||s.expiresAt<=at||at<s.authenticatedAt||at-s.authenticatedAt>=900;}
  synchronized void changePassword(Session s,String next){
    if(passwordChangeExpired(s))throw new SecurityException("为保护账号，请重新登录后修改密码");
    users.changeOwnPassword(s.actor,next);
    sessions.entrySet().removeIf(e->e.getValue().actor.userId().equals(s.actor.userId()));
  }
  synchronized void logout(HttpExchange x){sessions.remove(cookie(x,COOKIE));}
  synchronized String applicationCsrf(HttpExchange x){purge();String old=cookie(x,"ZXAPPLY");if(old!=null&&applicationTokens.containsKey(old))return old;if(applicationTokens.size()>=10000)throw new IllegalArgumentException("申请页面繁忙，请稍后重试");String next=token(24);applicationTokens.put(next,now()+900);x.getResponseHeaders().add("Set-Cookie","ZXAPPLY="+next+"; Path=/access/apply; HttpOnly; SameSite=Strict");return next;}
  synchronized boolean consumeApplicationCsrf(HttpExchange x,String supplied){String token=cookie(x,"ZXAPPLY");Long until=token==null?null:applicationTokens.remove(token);return until!=null&&until>=now()&&equal(token,supplied);}
  synchronized boolean allowApplication(String remote){long now=now();applicationAttempts.entrySet().removeIf(e->now-e.getValue().started>=600);if(!applicationAttempts.containsKey(remote)&&applicationAttempts.size()>=10000)return false;return ++applicationAttempts.computeIfAbsent(remote,k->new Attempt(now)).count<=5;}
  synchronized void acknowledgeSafety(Session s,String version){if(s==null||sessions.get(s.token)!=s||s.expiresAt<=now()||s.mustChangePassword)throw new SecurityException("请重新登录或先修改初始密码");try{String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));users.access().acknowledge(s.actor,hash,version);s.safetyVersion=version;}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
  synchronized String loginCsrf(HttpExchange x){purge();String old=cookie(x,"ZXLOGIN");if(old!=null&&loginTokens.containsKey(old))return old;String next=token(24);loginTokens.put(next,now()+900);x.getResponseHeaders().add("Set-Cookie","ZXLOGIN="+next+"; Path=/login; HttpOnly; SameSite=Strict");return next;}
  synchronized boolean consumeLoginCsrf(HttpExchange x,String supplied){String token=cookie(x,"ZXLOGIN");Long until=token==null?null:loginTokens.remove(token);return until!=null&&until>=now()&&equal(token,supplied);}
  boolean csrf(Session s,String supplied){return s!=null&&equal(s.csrf,supplied);}
  private static boolean equal(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),b.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  String setCookie(Session s){return COOKIE+"="+s.token+"; Path=/; HttpOnly; SameSite=Strict";}
  String clearCookie(){return COOKIE+"=deleted; Path=/; Max-Age=0; HttpOnly; SameSite=Strict";}
  private String token(int size){byte[] a=new byte[size];random.nextBytes(a);return Base64.getUrlEncoder().withoutPadding().encodeToString(a);}
  private static String cookie(HttpExchange x,String name){for(String header:x.getRequestHeaders().getOrDefault("Cookie",List.of()))for(String part:header.split(";")){String[] pair=part.trim().split("=",2);if(pair.length==2&&pair[0].equals(name))return pair[1];}return null;}
  private void purge(){long now=now();sessions.entrySet().removeIf(e->e.getValue().expiresAt<now);loginTokens.entrySet().removeIf(e->e.getValue()<now);applicationTokens.entrySet().removeIf(e->e.getValue()<now);attempts.entrySet().removeIf(e->now-e.getValue().started>600);if(attempts.size()>10000)attempts.clear();if(loginTokens.size()>10000)loginTokens.clear();}
  static final class Session {
    final String token,csrf,authNumber;final ActorContext actor;final boolean mustChangePassword;final long authenticatedAt;long expiresAt;
    volatile String safetyVersion="";
    volatile long unreadCount=0;
    boolean safetyAccepted(){return AccessPlatform.SAFETY_VERSION.equals(safetyVersion);}
    Session(String token,String csrf,long authenticatedAt,UserAccount user){this.token=token;this.csrf=csrf;this.authenticatedAt=authenticatedAt;this.expiresAt=authenticatedAt+28800;actor=user.actor();authNumber=user.authNumber();mustChangePassword=user.mustChangePassword();}
  }
  private static final class Attempt{int count;long lockedUntil;final long started;Attempt(long now){started=now;}}
}

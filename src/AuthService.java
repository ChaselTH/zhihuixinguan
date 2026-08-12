import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class AuthService {
  static final String COOKIE = "ZXSESSION";
  static final String FORM_COOKIE = "ZXFORM";
  private static final String INITIAL_PASSWORD = "1999sth0520";
  private static final int ITERATIONS = 160000;
  private static final int KEY_BITS = 256;
  private static final long SESSION_SECONDS = 8 * 60 * 60;
  private final Path config;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Session> sessions = new HashMap<String, Session>();
  private final Map<String, Session> formSessions = new HashMap<String, Session>();
  private final Map<String, Attempts> attempts = new HashMap<String, Attempts>();
  private byte[] salt;
  private byte[] hash;

  AuthService(Path dataRoot) throws IOException {
    this.config = dataRoot.resolve("auth.properties");
    if (Files.isRegularFile(config)) load(); else initialize();
  }

  synchronized Session authenticate(String remote, String password) {
    purge();
    Attempts state = attempts.get(remote);
    long now = Instant.now().getEpochSecond();
    if (state != null && state.lockedUntil > now) return null;
    boolean okay = constantTime(hash, derive(password == null ? "" : password, salt));
    if (!okay) {
      if (state == null || now - state.windowStarted > 600) state = new Attempts(now);
      state.count++;
      if (state.count >= 5) state.lockedUntil = now + 60;
      attempts.put(remote, state);
      return null;
    }
    attempts.remove(remote);
    String token = randomToken(32);
    Session session = new Session(token, randomToken(24), now + SESSION_SECONDS);
    sessions.put(token, session);
    return session;
  }

  synchronized Session session(HttpExchange exchange) {
    purge();
    String token = cookie(exchange.getRequestHeaders(), COOKIE);
    Session result = token == null ? null : sessions.get(token);
    if (result != null) result.expiresAt = Instant.now().getEpochSecond() + SESSION_SECONDS;
    return result;
  }

  synchronized Session formSession(HttpExchange exchange) {
    purge();
    long now = Instant.now().getEpochSecond();
    String token = cookie(exchange.getRequestHeaders(), FORM_COOKIE);
    Session result = token == null ? null : formSessions.get(token);
    if (result == null) {
      token = randomToken(32);
      result = new Session(token, randomToken(24), now + SESSION_SECONDS);
      formSessions.put(token, result);
    } else result.expiresAt = now + SESSION_SECONDS;
    return result;
  }

  synchronized void logout(HttpExchange exchange) {
    String token = cookie(exchange.getRequestHeaders(), COOKIE);
    if (token != null) sessions.remove(token);
  }

  synchronized boolean changePassword(String current, String next) throws IOException {
    if (next == null || next.length() < 10 || next.length() > 128) return false;
    if (!constantTime(hash, derive(current == null ? "" : current, salt))) return false;
    salt = new byte[18]; random.nextBytes(salt);
    hash = derive(next, salt);
    save();
    sessions.clear();
    return true;
  }

  boolean csrf(Session session, String supplied) {
    return session != null && supplied != null && constantTime(session.csrf.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
  }

  String setCookie(Session session) {
    return COOKIE + "=" + session.token + "; Path=/; HttpOnly; SameSite=Strict";
  }

  String clearCookie() { return COOKIE + "=deleted; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"; }
  String setFormCookie(Session session) { return FORM_COOKIE + "=" + session.token + "; Path=/; HttpOnly; SameSite=Strict"; }

  private void initialize() throws IOException {
    salt = new byte[18]; random.nextBytes(salt);
    hash = derive(INITIAL_PASSWORD, salt);
    save();
  }

  private void load() throws IOException {
    Properties p = new Properties();
    try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) { p.load(reader); }
    try {
      salt = Base64.getDecoder().decode(p.getProperty("salt", ""));
      hash = Base64.getDecoder().decode(p.getProperty("hash", ""));
      if (salt.length < 16 || hash.length < 32) throw new IllegalArgumentException();
    } catch (Exception e) { throw new IOException("管理员密码配置已损坏", e); }
  }

  private void save() throws IOException {
    Properties p = new Properties();
    p.setProperty("format", "1");
    p.setProperty("algorithm", "PBKDF2WithHmacSHA256");
    p.setProperty("iterations", Integer.toString(ITERATIONS));
    p.setProperty("salt", Base64.getEncoder().encodeToString(salt));
    p.setProperty("hash", Base64.getEncoder().encodeToString(hash));
    Path temp = config.resolveSibling(config.getFileName().toString() + ".tmp");
    try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { p.store(writer, "Zhihui Xinguan administrator authentication"); }
    try { Files.move(temp, config, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
    catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, config, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
    try {
      java.util.Set<java.nio.file.attribute.PosixFilePermission> set = new java.util.HashSet<java.nio.file.attribute.PosixFilePermission>();
      set.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ); set.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
      Files.setPosixFilePermissions(config, set);
    } catch (Exception ignored) { }
  }

  private byte[] derive(String password, byte[] saltValue) {
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), saltValue, ITERATIONS, KEY_BITS);
    try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
    catch (Exception e) { throw new IllegalStateException(e); }
    finally { spec.clearPassword(); }
  }

  private static boolean constantTime(byte[] a, byte[] b) { return MessageDigest.isEqual(a, b); }
  private String randomToken(int bytes) { byte[] value = new byte[bytes]; random.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
  private static String cookie(Headers headers, String name) {
    for (String line : headers.getOrDefault("Cookie", java.util.Collections.<String>emptyList())) {
      for (String part : line.split(";")) {
        String[] pair = part.trim().split("=", 2);
        if (pair.length == 2 && name.equals(pair[0])) return pair[1];
      }
    }
    return null;
  }
  private synchronized void purge() {
    long now = Instant.now().getEpochSecond();
    Iterator<Map.Entry<String, Session>> iterator = sessions.entrySet().iterator();
    while (iterator.hasNext()) if (iterator.next().getValue().expiresAt < now) iterator.remove();
    Iterator<Map.Entry<String, Session>> forms = formSessions.entrySet().iterator();
    while (forms.hasNext()) if (forms.next().getValue().expiresAt < now) forms.remove();
  }

  static final class Session {
    final String token; final String csrf; long expiresAt;
    Session(String token, String csrf, long expiresAt) { this.token = token; this.csrf = csrf; this.expiresAt = expiresAt; }
  }
  private static final class Attempts {
    int count; final long windowStarted; long lockedUntil;
    Attempts(long now) { this.windowStarted = now; }
  }
}

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class Main {
  private static final int MAX_FORM = 2 * 1024 * 1024;
  private static final int MAX_UPLOAD = 50 * 1024 * 1024;
  private static final int MAX_BATCH_UPLOAD = 250 * 1024 * 1024;
  private final Path root;
  private final DataStore store;
  private final WorkbookImporter importer = new WorkbookImporter();
  private final ExcelExporter exporter = new ExcelExporter();
  private final AuthService auth;
  private final Html html;
  private final String version;
  private final int port;

  private Main(Path root, Path dataRoot, int port) throws Exception {
    this.root = root.toAbsolutePath().normalize();
    this.port = port;
    this.version = readVersion(this.root);
    this.store = new DataStore(dataRoot);
    this.auth = new AuthService(dataRoot);
    this.html = new Html(version);
    seedIfNeeded();
  }

  public static void main(String[] args) throws Exception {
    Map<String, String> options = arguments(args);
    Path root = Path.of(options.getOrDefault("root", "."));
    Path data = Path.of(options.getOrDefault("data-root", root.resolve("data").toString()));
    int port = Integer.parseInt(options.getOrDefault("port", "2874"));
    if (port < 1024 || port > 65535) throw new IllegalArgumentException("port must be 1024-65535");
    Main app = new Main(root, data, port);
    app.start();
  }

  private void start() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 64);
    server.createContext("/", new Handler());
    server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
      public void run() { System.out.println("\n服务状态：正在停止"); server.stop(1); }
    }, "zhihui-xinguan-shutdown"));
    server.start();
    System.out.println("服务状态：运行中");
    System.out.println("本机地址：http://127.0.0.1:" + port);
    String ip = localIp();
    if (!ip.isEmpty()) System.out.println("局域网地址：http://" + ip + ":" + port);
    System.out.println("管理入口：http://127.0.0.1:" + port + "/admin");
    System.out.println("按 Ctrl+C 关闭服务");
  }

  private final class Handler implements HttpHandler {
    public void handle(HttpExchange exchange) throws IOException {
      try {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if ("GET".equals(method) && "/".equals(path)) { dashboard(exchange); return; }
        if ("GET".equals(method) && "/details".equals(path)) { details(exchange); return; }
        if ("GET".equals(method) && "/branch".equals(path)) { branch(exchange); return; }
        if ("GET".equals(method) && "/export".equals(path)) { exportExcel(exchange); return; }
        if ("GET".equals(method) && "/health".equals(path)) { text(exchange, 200, "RUNNING V" + version + "\n", "text/plain; charset=utf-8"); return; }
        if ("GET".equals(method) && path.startsWith("/assets/")) { asset(exchange, path); return; }
        if ("GET".equals(method) && ("/admin".equals(path) || "/admin/".equals(path))) { admin(exchange); return; }
        if ("POST".equals(method) && "/admin/login".equals(path)) { login(exchange); return; }
        if ("POST".equals(method) && "/admin/logout".equals(path)) { logout(exchange); return; }
        if ("POST".equals(method) && "/admin/upload".equals(path)) { upload(exchange); return; }
        if ("POST".equals(method) && "/admin/password".equals(path)) { password(exchange); return; }
        if ("POST".equals(method) && "/update-cell".equals(path)) { updateCell(exchange); return; }
        if ("POST".equals(method) && "/update-batch".equals(path)) { updateBatch(exchange); return; }
        sendHtml(exchange, 404, html.errorPage(404, "页面不存在"));
      } catch (RequestTooLargeException e) {
        sendHtml(exchange, 413, html.errorPage(413, "一批上传内容超过 250 MB 限制"));
      } catch (Exception e) {
        e.printStackTrace(System.err);
        sendHtml(exchange, 500, html.errorPage(500, "系统处理请求时出现异常，请查看启动终端日志"));
      } finally { exchange.close(); }
    }
  }

  private void dashboard(HttpExchange exchange) throws IOException {
    Map<String, String> q = query(exchange.getRequestURI());
    List<ImportRecord> all = store.readAll();
    List<String> months = store.months();
    RangeSelection range = RangeSelection.from(q, months);
    DashboardData data = new DashboardData(range, months, all, store.readRange(range));
    sendHtml(exchange, 200, html.dashboard(data));
  }

  private void details(HttpExchange exchange) throws IOException {
    Map<String, String> q = query(exchange.getRequestURI());
    List<ImportRecord> all = store.readAll();
    List<String> months = store.months();
    RangeSelection range = RangeSelection.from(q, months);
    String dataset = "negative".equals(q.get("dataset")) ? "negative" : "multi";
    String search = limit(q.get("q"), 100);
    String branch = limit(q.get("branch"), 100);
    int page = integer(q.get("page"), 1);
    AuthService.Session form = auth.formSession(exchange);
    exchange.getResponseHeaders().add("Set-Cookie", auth.setFormCookie(form));
    sendHtml(exchange, 200, html.details(new DashboardData(range, months, all, store.readRange(range)), dataset, search, branch, page, form));
  }

  private void branch(HttpExchange exchange) throws IOException {
    Map<String, String> q = query(exchange.getRequestURI());
    String branchName = limit(q.get("branch"), 100);
    if (branchName.isEmpty()) { redirect(exchange, "/"); return; }
    List<ImportRecord> all = store.readAll(); List<String> months = store.months(); RangeSelection range = RangeSelection.from(q, months);
    DashboardData data = new DashboardData(range, months, all, store.readRange(range));
    AuthService.Session form = auth.formSession(exchange); exchange.getResponseHeaders().add("Set-Cookie", auth.setFormCookie(form));
    sendHtml(exchange, 200, html.branch(data, branchName, form));
  }

  private void exportExcel(HttpExchange exchange) throws IOException {
    Map<String, String> q = query(exchange.getRequestURI()); List<ImportRecord> all = store.readAll(); List<String> months = store.months();
    RangeSelection range = RangeSelection.from(q, months); String dataset = "negative".equals(q.get("dataset")) ? "negative" : "multi";
    String search = limit(q.get("q"), 100), branch = limit(q.get("branch"), 100);
    DashboardData data = new DashboardData(range, months, all, store.readRange(range)); List<RowRef> rows = data.filtered(dataset, search, branch);
    try {
      byte[] bytes = exporter.export(dataset, range.label + (branch.isEmpty() ? "" : " " + branch), rows);
      String name = "智慧信管_" + ("negative".equals(dataset) ? "负面闭环" : "多重预警") + "_" + range.start + "_" + range.end + ".xlsx";
      sendDownload(exchange, bytes, name);
    } catch (Exception e) { throw new IOException("生成 Excel 失败", e); }
  }

  private void updateCell(HttpExchange exchange) throws IOException {
    requireForm(exchange); AuthService.Session session = auth.formSession(exchange);
    Map<String, String> form = decodeForm(readLimited(exchange.getRequestBody(), MAX_FORM));
    if (!auth.csrf(session, form.get("csrf"))) { sendHtml(exchange, 403, html.errorPage(403, "页面已过期，请刷新后重试")); return; }
    String dataset = "negative".equals(form.get("dataset")) ? "negative" : "multi";
    int column = integer(form.get("column"), -1); String value = limit(form.get("value"), 2000);
    if (!validEdit(dataset, column, value)) { sendHtml(exchange, 400, html.errorPage(400, "填报值不符合字段要求")); return; }
    Map<Integer, String> values = new HashMap<Integer, String>(); values.put(Integer.valueOf(column), value);
    boolean okay = store.updateRow(form.getOrDefault("month", ""), form.getOrDefault("recordId", ""), integer(form.get("row"), -1), values);
    if (!okay) { sendHtml(exchange, 404, html.errorPage(404, "未找到需要更新的数据行")); return; }
    String branchName = limit(form.get("branch"), 100); String location = branchName.isEmpty() ? "/details?" : "/branch?";
    RangeSelection range = RangeSelection.from(form, store.months());
    location += range.queryString() + (branchName.isEmpty() ? "&dataset=" + url(dataset) : "&branch=" + url(branchName));
    redirect(exchange, location);
  }

  private void updateBatch(HttpExchange exchange) throws IOException {
    requireForm(exchange); AuthService.Session session = auth.formSession(exchange);
    Map<String, String> form = decodeForm(readLimited(exchange.getRequestBody(), MAX_FORM));
    if (!auth.csrf(session, form.get("csrf"))) { sendHtml(exchange, 403, html.errorPage(403, "页面已过期，请刷新后重试")); return; }
    String dataset = "negative".equals(form.get("dataset")) ? "negative" : "multi";
    int count = integer(form.get("rows"), -1);
    if (count < 0 || count > 50) { sendHtml(exchange, 400, html.errorPage(400, "本次保存的记录数量无效")); return; }
    int[] columns = "negative".equals(dataset) ? new int[]{10,11} : new int[]{17,18,19,20,21};
    List<CellUpdate> updates = new ArrayList<CellUpdate>();
    for (int item = 0; item < count; item++) {
      String month = form.getOrDefault("m" + item, "");
      String recordId = form.getOrDefault("r" + item, "");
      int row = integer(form.get("i" + item), -1);
      for (int column : columns) {
        String value = limit(form.get("v" + item + "_" + column), 2000);
        if (!validEdit(dataset, column, value)) { sendHtml(exchange, 400, html.errorPage(400, "第 " + (item + 1) + " 行的填报值不符合字段要求")); return; }
        CellUpdate update = new CellUpdate(); update.month = month; update.recordId = recordId; update.rowIndex = row; update.columnIndex = column; update.value = value; updates.add(update);
      }
    }
    int position = 0;
    while (position < updates.size()) {
      CellUpdate first = updates.get(position); Map<Integer, String> values = new HashMap<Integer, String>();
      while (position < updates.size()) {
        CellUpdate current = updates.get(position);
        if (!first.month.equals(current.month) || !first.recordId.equals(current.recordId) || first.rowIndex != current.rowIndex) break;
        values.put(Integer.valueOf(current.columnIndex), current.value); position++;
      }
      if (!store.updateRow(first.month, first.recordId, first.rowIndex, values)) { sendHtml(exchange, 404, html.errorPage(404, "部分数据已变化，请刷新页面后重新填写")); return; }
    }
    String branchName = limit(form.get("branch"), 100); String location = branchName.isEmpty() ? "/details?" : "/branch?";
    RangeSelection range = RangeSelection.from(form, store.months()); location += range.queryString();
    if (branchName.isEmpty()) location += "&dataset=" + url(dataset) + "&q=" + url(limit(form.get("q"), 100)) + "&page=" + Math.max(1, integer(form.get("page"), 1));
    else location += "&branch=" + url(branchName);
    redirect(exchange, location);
  }

  private void admin(HttpExchange exchange) throws IOException {
    AuthService.Session session = auth.session(exchange);
    if (session == null) { sendHtml(exchange, 200, html.adminLogin("")); return; }
    Map<String, String> q = query(exchange.getRequestURI());
    String notice = limit(q.get("notice"), 300);
    boolean error = "1".equals(q.get("error"));
    sendHtml(exchange, 200, html.admin(store.readAll(), store.months(), session, notice, error));
  }

  private void login(HttpExchange exchange) throws IOException {
    requireForm(exchange);
    Map<String, String> form = decodeForm(readLimited(exchange.getRequestBody(), MAX_FORM));
    String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
    AuthService.Session session = auth.authenticate(remote, form.get("password"));
    if (session == null) { sendHtml(exchange, 401, html.adminLogin("密码错误；连续失败 5 次将短暂锁定登录")); return; }
    exchange.getResponseHeaders().add("Set-Cookie", auth.setCookie(session));
    redirect(exchange, "/admin");
  }

  private void logout(HttpExchange exchange) throws IOException {
    AuthService.Session session = requireSession(exchange);
    if (session == null) return;
    Map<String, String> form = decodeForm(readLimited(exchange.getRequestBody(), MAX_FORM));
    if (!auth.csrf(session, form.get("csrf"))) { sendHtml(exchange, 403, html.errorPage(403, "请求校验失败，请重新登录")); return; }
    auth.logout(exchange); exchange.getResponseHeaders().add("Set-Cookie", auth.clearCookie()); redirect(exchange, "/admin");
  }

  private void password(HttpExchange exchange) throws IOException {
    AuthService.Session session = requireSession(exchange); if (session == null) return;
    Map<String, String> form = decodeForm(readLimited(exchange.getRequestBody(), MAX_FORM));
    if (!auth.csrf(session, form.get("csrf"))) { adminRedirect(exchange, "请求校验失败，请重新登录", true); return; }
    String next = form.getOrDefault("next", "");
    if (!next.equals(form.getOrDefault("confirm", ""))) { adminRedirect(exchange, "两次输入的新密码不一致", true); return; }
    if (!auth.changePassword(form.get("current"), next)) { adminRedirect(exchange, "当前密码错误，或新密码不足 10 位", true); return; }
    exchange.getResponseHeaders().add("Set-Cookie", auth.clearCookie());
    redirect(exchange, "/admin?notice=" + url("密码修改成功，请使用新密码重新登录"));
  }

  private void upload(HttpExchange exchange) throws IOException {
    AuthService.Session session = requireSession(exchange); if (session == null) return;
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (contentType == null || !contentType.toLowerCase().startsWith("multipart/form-data")) {
      adminRedirect(exchange, "上传请求格式不正确", true); return;
    }
    String boundary = boundary(contentType);
    if (boundary.isEmpty() || boundary.length() > 200) { adminRedirect(exchange, "上传边界无效", true); return; }
    Multipart form;
    try { form = parseMultipart(readLimited(exchange.getRequestBody(), MAX_BATCH_UPLOAD + 1024 * 1024), boundary); }
    catch (IllegalArgumentException e) { adminRedirect(exchange, "上传内容无法解析", true); return; }
    if (!auth.csrf(session, form.fields.get("csrf"))) { adminRedirect(exchange, "请求校验失败，请重新登录", true); return; }
    if (form.files.isEmpty()) { adminRedirect(exchange, "请选择需要更新的表格文件", true); return; }
    Path uploadDir = store.dataRoot().resolve("uploads"); Files.createDirectories(uploadDir);
    int success = 0, replaced = 0, rows = 0, skipped = 0; List<String> errors = new ArrayList<String>();
    for (Part part : form.files) {
      if (part.data.length == 0) continue;
      if (part.data.length > MAX_UPLOAD) { errors.add(part.filename + "：超过单文件 50 MB 限制"); continue; }
      Path temp = Files.createTempFile(uploadDir, "upload-", ".tmp");
      try {
        Files.write(temp, part.data, StandardOpenOption.TRUNCATE_EXISTING);
        ImportRecord record = importer.read(temp, part.filename, form.fields.get("month")); ImportResult result = store.save(record);
        success++; rows += record.rows.size(); if (result.replaced) replaced++;
      } catch (WorkbookImportException e) {
        if (e.getMessage().contains("预警总表")) skipped++; else errors.add(part.filename + "：" + e.getMessage());
      } finally { try { Files.deleteIfExists(temp); } catch (IOException e) { System.err.println("临时上传文件未能清理：" + temp); } }
    }
    String message = "批量处理完成：成功 " + success + " 个文件、共 " + rows + " 条，替换 " + replaced + " 个批次，跳过总表 " + skipped + " 个";
    if (!errors.isEmpty()) message += "；失败 " + errors.size() + " 个：" + join(errors, "；");
    adminRedirect(exchange, message, !errors.isEmpty() && success == 0);
  }

  private AuthService.Session requireSession(HttpExchange exchange) throws IOException {
    AuthService.Session session = auth.session(exchange);
    if (session == null) { redirect(exchange, "/admin"); return null; }
    return session;
  }

  private void asset(HttpExchange exchange, String path) throws IOException {
    String name = path.substring("/assets/".length());
    if (!("style.css".equals(name) || "html5shiv.js".equals(name))) { sendHtml(exchange, 404, html.errorPage(404, "资源不存在")); return; }
    Path file = root.resolve("web").resolve("assets").resolve(name).normalize();
    if (!file.startsWith(root.resolve("web").resolve("assets")) || !Files.isRegularFile(file)) { sendHtml(exchange, 404, html.errorPage(404, "资源不存在")); return; }
    byte[] bytes = Files.readAllBytes(file);
    String type = name.endsWith(".css") ? "text/css; charset=utf-8" : "application/javascript; charset=utf-8";
    Headers headers = exchange.getResponseHeaders(); security(headers); headers.set("Content-Type", type); headers.set("Cache-Control", "public, max-age=3600"); headers.set("Content-Length", Integer.toString(bytes.length));
    exchange.sendResponseHeaders(200, bytes.length); try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
  }

  private void seedIfNeeded() {
    try {
      if (!store.readAll().isEmpty()) return;
      Path seed = root.resolve("seed"); if (!Files.isDirectory(seed)) return;
      try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(seed)) {
        for (Path file : files) if (Files.isRegularFile(file)) {
          try { store.save(importer.read(file, file.getFileName().toString(), "")); }
          catch (WorkbookImportException e) { System.err.println("种子表格已跳过：" + file.getFileName() + " - " + e.getMessage()); }
        }
      }
    } catch (Exception e) { System.err.println("初始化表头模板失败：" + e.getMessage()); }
  }

  private static byte[] readLimited(InputStream input, int maximum) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 64 * 1024)); byte[] buffer = new byte[8192]; int total = 0; int read;
    while ((read = input.read(buffer)) >= 0) { total += read; if (total > maximum) throw new RequestTooLargeException(); output.write(buffer, 0, read); }
    return output.toByteArray();
  }
  private static void requireForm(HttpExchange exchange) throws IOException {
    String type = exchange.getRequestHeaders().getFirst("Content-Type");
    if (type == null || !type.toLowerCase().startsWith("application/x-www-form-urlencoded")) throw new IOException("invalid form content type");
  }
  private static Map<String, String> decodeForm(byte[] bytes) { return parseParameters(new String(bytes, StandardCharsets.UTF_8)); }
  private static Map<String, String> query(URI uri) { return parseParameters(uri.getRawQuery() == null ? "" : uri.getRawQuery()); }
  private static Map<String, String> parseParameters(String raw) {
    Map<String, String> result = new LinkedHashMap<String, String>();
    for (String part : raw.split("&")) if (!part.isEmpty()) { String[] pair = part.split("=", 2); result.put(decode(pair[0]), pair.length > 1 ? decode(pair[1]) : ""); }
    return result;
  }
  private static String decode(String value) { try { return URLDecoder.decode(value, StandardCharsets.UTF_8); } catch (Exception e) { return ""; } }
  private static String url(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
  private static String boundary(String contentType) {
    for (String part : contentType.split(";")) { String value = part.trim(); if (value.toLowerCase().startsWith("boundary=")) { String b = value.substring(9).trim(); if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) b = b.substring(1, b.length() - 1); return b; } }
    return "";
  }
  private static Multipart parseMultipart(byte[] data, String boundary) {
    Multipart result = new Multipart();
    byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1); int position = 0;
    while (true) {
      int start = indexOf(data, marker, position); if (start < 0) break; start += marker.length;
      if (starts(data, start, "--".getBytes(StandardCharsets.ISO_8859_1))) break;
      if (starts(data, start, "\r\n".getBytes(StandardCharsets.ISO_8859_1))) start += 2;
      int headerEnd = indexOf(data, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), start); if (headerEnd < 0) throw new IllegalArgumentException();
      String headers = new String(data, start, headerEnd - start, StandardCharsets.ISO_8859_1);
      int contentStart = headerEnd + 4; int next = indexOf(data, ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1), contentStart); if (next < 0) throw new IllegalArgumentException();
      String disposition = ""; for (String line : headers.split("\r\n")) if (line.toLowerCase().startsWith("content-disposition:")) disposition = line;
      String name = dispositionValue(disposition, "name"); String filename = dispositionValue(disposition, "filename");
      byte[] content = java.util.Arrays.copyOfRange(data, contentStart, next);
      if (!filename.isEmpty() && ("file".equals(name) || "files".equals(name))) result.files.add(new Part(filename, content));
      else if (!name.isEmpty() && content.length <= 4096) result.fields.put(name, new String(content, StandardCharsets.UTF_8));
      position = next + 2;
    }
    return result;
  }
  private static String dispositionValue(String line, String key) {
    if ("filename".equals(key)) {
      java.util.regex.Matcher extended = java.util.regex.Pattern.compile("(?:^|;)\\s*filename\\*=UTF-8''([^;]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(line);
      if (extended.find()) return cleanFilename(decode(extended.group(1)));
    }
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^|;)\\s*" + key + "=\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(line);
    if (!matcher.find()) return ""; String value = matcher.group(1);
    if ("filename".equals(key)) {
      try {
        String utf8 = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        if (!utf8.contains("�")) value = utf8;
      } catch (Exception ignored) { }
    }
    return "filename".equals(key) ? cleanFilename(value) : value.replaceAll("[\\r\\n\\t]", " ").trim();
  }
  private static String cleanFilename(String value) {
    value = value.replace('\\', '/'); int slash = value.lastIndexOf('/'); if (slash >= 0) value = value.substring(slash + 1);
    return value.replaceAll("[\\r\\n\\t]", " ").trim();
  }
  private static int indexOf(byte[] data, byte[] target, int from) { outer: for (int i = Math.max(0, from); i <= data.length - target.length; i++) { for (int j = 0; j < target.length; j++) if (data[i + j] != target[j]) continue outer; return i; } return -1; }
  private static boolean starts(byte[] data, int from, byte[] target) { if (from < 0 || from + target.length > data.length) return false; for (int i = 0; i < target.length; i++) if (data[from + i] != target[i]) return false; return true; }
  private static void sendHtml(HttpExchange exchange, int status, String content) throws IOException { text(exchange, status, content, "text/html; charset=utf-8"); }
  private static void sendDownload(HttpExchange exchange, byte[] bytes, String filename) throws IOException {
    Headers headers = exchange.getResponseHeaders(); security(headers); headers.set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    headers.set("Content-Disposition", "attachment; filename=zhihui-xinguan.xlsx; filename*=UTF-8''" + url(filename).replace("+", "%20"));
    headers.set("Cache-Control", "no-store"); headers.set("Content-Length", Integer.toString(bytes.length)); exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
  }
  private static void text(HttpExchange exchange, int status, String content, String type) throws IOException {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8); Headers headers = exchange.getResponseHeaders(); security(headers); headers.set("Content-Type", type); headers.set("Cache-Control", "no-store"); headers.set("Content-Length", Integer.toString(bytes.length));
    exchange.sendResponseHeaders(status, bytes.length); try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
  }
  private static void security(Headers headers) {
    headers.set("X-Content-Type-Options", "nosniff"); headers.set("X-Frame-Options", "DENY"); headers.set("Referrer-Policy", "no-referrer");
    headers.set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
  }
  private static void redirect(HttpExchange exchange, String location) throws IOException { security(exchange.getResponseHeaders()); exchange.getResponseHeaders().set("Location", location); exchange.sendResponseHeaders(303, -1); }
  private static void adminRedirect(HttpExchange exchange, String notice, boolean error) throws IOException { redirect(exchange, "/admin?notice=" + url(limit(notice, 300)) + (error ? "&error=1" : "")); }
  private static String readVersion(Path root) { try { return Files.readString(root.resolve("VERSION"), StandardCharsets.UTF_8).trim(); } catch (Exception e) { return "0.0.0"; } }
  private static boolean validEdit(String dataset, int column, String value) {
    if (value == null || value.isEmpty()) return "negative".equals(dataset) ? column >= 10 && column <= 11 : column >= 17 && column <= 21;
    if ("negative".equals(dataset)) return column == 11 || (column == 10 && oneOf(value, new String[]{"是","否"}));
    if (column == 17) return oneOf(value, new String[]{"是","否"});
    if (column == 18) return oneOf(value, new String[]{"无需管控","日常一半管控","重点关注管控"});
    if (column == 20) return oneOf(value, new String[]{"增加","维持","压降","退出"});
    return column == 19 || column == 21;
  }
  private static boolean oneOf(String value, String[] allowed) { for (String candidate : allowed) if (candidate.equals(value)) return true; return false; }
  private static String join(List<String> values, String delimiter) { StringBuilder result = new StringBuilder(); for (String value : values) { if (result.length() > 0) result.append(delimiter); result.append(value); } return result.toString(); }
  private static int integer(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception e) { return fallback; } }
  private static String limit(String value, int max) { if (value == null) return ""; String clean = value.replaceAll("[\\r\\n\\t]", " ").trim(); return clean.length() > max ? clean.substring(0, max) : clean; }
  private static String localIp() { try { String ip = InetAddress.getLocalHost().getHostAddress(); return ip.contains(":") || ip.startsWith("127.") ? "" : ip; } catch (Exception e) { return ""; } }
  private static Map<String, String> arguments(String[] args) { Map<String, String> result = new HashMap<String, String>(); for (int i = 0; i < args.length; i++) { if (!args[i].startsWith("--")) continue; String key = args[i].substring(2); if (i + 1 < args.length && !args[i + 1].startsWith("--")) result.put(key, args[++i]); else result.put(key, "true"); } return result; }

  private static final class Multipart { final Map<String, String> fields = new HashMap<String, String>(); final List<Part> files = new ArrayList<Part>(); }
  private static final class Part { final String filename; final byte[] data; Part(String filename, byte[] data) { this.filename = filename; this.data = data; } }
  private static final class RequestTooLargeException extends IOException { }
}

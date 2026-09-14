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

class HttpSupport {
  static byte[] readLimited(InputStream input, int maximum) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 64 * 1024)); byte[] buffer = new byte[8192]; int total = 0; int read;
    while ((read = input.read(buffer)) >= 0) { total += read; if (total > maximum) throw new RequestTooLargeException(); output.write(buffer, 0, read); }
    return output.toByteArray();
  }
  static void requireForm(HttpExchange exchange) throws IOException {
    String type = exchange.getRequestHeaders().getFirst("Content-Type");
    if (type == null || !type.toLowerCase().startsWith("application/x-www-form-urlencoded")) throw new IOException("invalid form content type");
  }
  static Map<String, String> decodeForm(byte[] bytes) { return parseParameters(new String(bytes, StandardCharsets.UTF_8)); }
  static Map<String, String> query(URI uri) { return parseParameters(uri.getRawQuery() == null ? "" : uri.getRawQuery()); }
  static Map<String, String> parseParameters(String raw) {
    Map<String, String> result = new LinkedHashMap<String, String>();
    for (String part : raw.split("&")) if (!part.isEmpty()) { String[] pair = part.split("=", 2); result.put(decode(pair[0]), pair.length > 1 ? decode(pair[1]) : ""); }
    return result;
  }
  static String decode(String value) { try { return URLDecoder.decode(value, StandardCharsets.UTF_8); } catch (Exception e) { return ""; } }
  static String url(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
  static String boundary(String contentType) {
    for (String part : contentType.split(";")) { String value = part.trim(); if (value.toLowerCase().startsWith("boundary=")) { String b = value.substring(9).trim(); if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) b = b.substring(1, b.length() - 1); return b; } }
    return "";
  }
  static Multipart parseMultipart(byte[] data, String boundary) {
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
  static String dispositionValue(String line, String key) {
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
  static String cleanFilename(String value) {
    value = value.replace('\\', '/'); int slash = value.lastIndexOf('/'); if (slash >= 0) value = value.substring(slash + 1);
    return value.replaceAll("[\\r\\n\\t]", " ").trim();
  }
  static int indexOf(byte[] data, byte[] target, int from) { outer: for (int i = Math.max(0, from); i <= data.length - target.length; i++) { for (int j = 0; j < target.length; j++) if (data[i + j] != target[j]) continue outer; return i; } return -1; }
  static boolean starts(byte[] data, int from, byte[] target) { if (from < 0 || from + target.length > data.length) return false; for (int i = 0; i < target.length; i++) if (data[from + i] != target[i]) return false; return true; }
  static void sendHtml(HttpExchange exchange, int status, String content) throws IOException { text(exchange, status, content, "text/html; charset=utf-8"); }
  static void sendDownload(HttpExchange exchange, byte[] bytes, String filename) throws IOException {
    Headers headers = exchange.getResponseHeaders(); security(headers); headers.set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    headers.set("Content-Disposition", "attachment; filename=zhihui-xinguan.xlsx; filename*=UTF-8''" + url(filename).replace("+", "%20"));
    headers.set("Cache-Control", "no-store"); headers.set("Content-Length", Integer.toString(bytes.length)); exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
  }
  static void text(HttpExchange exchange, int status, String content, String type) throws IOException {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8); Headers headers = exchange.getResponseHeaders(); security(headers); headers.set("Content-Type", type); headers.set("Cache-Control", "no-store"); headers.set("Content-Length", Integer.toString(bytes.length));
    exchange.sendResponseHeaders(status, bytes.length); try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
  }
  static void security(Headers headers) {
    headers.set("X-Content-Type-Options", "nosniff"); headers.set("X-Frame-Options", "DENY"); headers.set("Referrer-Policy", "no-referrer");
    headers.set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
  }
  static void redirect(HttpExchange exchange, String location) throws IOException { security(exchange.getResponseHeaders()); exchange.getResponseHeaders().set("Location", location); exchange.sendResponseHeaders(303, -1); }
  static void adminRedirect(HttpExchange exchange, String notice, boolean error) throws IOException { redirect(exchange, "/imports?notice=" + url(limit(notice, 300)) + (error ? "&error=1" : "")); }
  static String readVersion(Path root) { try { return Files.readString(root.resolve("VERSION"), StandardCharsets.UTF_8).trim(); } catch (Exception e) { return "0.0.0"; } }
  static String join(List<String> values, String delimiter) { StringBuilder result = new StringBuilder(); for (String value : values) { if (result.length() > 0) result.append(delimiter); result.append(value); } return result.toString(); }
  static int integer(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception e) { return fallback; } }
  static String limit(String value, int max) { if (value == null) return ""; String clean = value.replaceAll("[\\r\\n\\t]", " ").trim(); return clean.length() > max ? clean.substring(0, max) : clean; }
  static String localIp() { try { String ip = InetAddress.getLocalHost().getHostAddress(); return ip.contains(":") || ip.startsWith("127.") ? "" : ip; } catch (Exception e) { return ""; } }
  static Map<String, String> arguments(String[] args) { Map<String, String> result = new HashMap<String, String>(); for (int i = 0; i < args.length; i++) { if (!args[i].startsWith("--")) continue; String key = args[i].substring(2); if (i + 1 < args.length && !args[i + 1].startsWith("--")) result.put(key, args[++i]); else result.put(key, "true"); } return result; }

  static final class Multipart { final Map<String, String> fields = new HashMap<String, String>(); final List<Part> files = new ArrayList<Part>(); }
  static final class Part { final String filename; final byte[] data; Part(String filename, byte[] data) { this.filename = filename; this.data = data; } }
  static final class RequestTooLargeException extends IOException { }
}

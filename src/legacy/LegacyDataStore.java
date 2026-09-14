import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

final class LegacyDataStore {
  private final Path dataRoot;
  private final Path monthsRoot;

  LegacyDataStore(Path dataRoot) throws IOException {
    this.dataRoot = dataRoot.toAbsolutePath().normalize();
    this.monthsRoot = this.dataRoot.resolve("months");
    Files.createDirectories(this.monthsRoot);
    secureDirectory(this.dataRoot);
    secureDirectory(this.monthsRoot);
  }

  Path dataRoot() { return dataRoot; }

  synchronized ImportResult save(ImportRecord record) throws IOException {
    validateRecord(record);
    Path monthDir = monthsRoot.resolve(record.month);
    Files.createDirectories(monthDir);
    secureDirectory(monthDir);
    String id = shortHash(record.dataset + "|" + record.period);
    record.id = id;
    Path meta = monthDir.resolve(record.dataset + "-" + id + ".properties");
    Path rows = monthDir.resolve(record.dataset + "-" + id + ".rows");
    ImportResult result = new ImportResult();
    result.record = record;
    if (Files.isRegularFile(meta)) {
      ImportRecord previous = readOne(meta);
      result.replaced = true;
      result.previousRows = previous.rows.size();
      preserveEditable(previous, record);
    }

    String suffix = ".tmp-" + UUID.randomUUID().toString();
    Path tempMeta = monthDir.resolve(meta.getFileName().toString() + suffix);
    Path tempRows = monthDir.resolve(rows.getFileName().toString() + suffix);
    writeRows(tempRows, record.rows);
    writeMeta(tempMeta, record, rows.getFileName().toString());
    secureFile(tempMeta);
    secureFile(tempRows);
    replace(tempRows, rows);
    replace(tempMeta, meta);
    secureFile(meta);
    secureFile(rows);
    return result;
  }

  synchronized List<ImportRecord> readAll() throws IOException {
    List<ImportRecord> result = new ArrayList<ImportRecord>();
    if (!Files.isDirectory(monthsRoot)) return result;
    try (DirectoryStream<Path> months = Files.newDirectoryStream(monthsRoot)) {
      for (Path month : months) {
        if (!Files.isDirectory(month) || Files.isSymbolicLink(month)) continue;
        try (DirectoryStream<Path> metas = Files.newDirectoryStream(month, "*.properties")) {
          for (Path meta : metas) result.add(readOne(meta));
        }
      }
    }
    Collections.sort(result, new Comparator<ImportRecord>() {
      public int compare(ImportRecord a, ImportRecord b) {
        int month = b.month.compareTo(a.month);
        if (month != 0) return month;
        int imported = b.importedAt.compareTo(a.importedAt);
        if (imported != 0) return imported;
        return a.dataset.compareTo(b.dataset);
      }
    });
    return result;
  }

  synchronized List<ImportRecord> readMonth(String month) throws IOException {
    List<ImportRecord> result = new ArrayList<ImportRecord>();
    for (ImportRecord record : readAll()) if (month.equals(record.month)) result.add(record);
    return result;
  }

  synchronized List<ImportRecord> readRange(RangeSelection range) throws IOException {
    return range.filter(readAll());
  }

  synchronized boolean updateCell(CellUpdate update) throws IOException {
    if (!update.month.matches("20\\d{2}-(0[1-9]|1[0-2])") || !update.recordId.matches("[0-9a-f]{20}")) return false;
    Path monthDir = monthsRoot.resolve(update.month).normalize();
    if (!monthDir.getParent().equals(monthsRoot) || !Files.isDirectory(monthDir)) return false;
    Path meta = null;
    try (DirectoryStream<Path> metas = Files.newDirectoryStream(monthDir, "*-" + update.recordId + ".properties")) {
      for (Path candidate : metas) { if (meta != null) return false; meta = candidate; }
    }
    if (meta == null) return false;
    ImportRecord record = readOne(meta);
    if (update.rowIndex < 0 || update.rowIndex >= record.rows.size()) return false;
    if (!editable(record.dataset, update.columnIndex)) return false;
    List<String> row = record.rows.get(update.rowIndex);
    while (row.size() < record.columns.size()) row.add("");
    row.set(update.columnIndex, update.value == null ? "" : update.value);
    record.updatedAt = Instant.now().toString();
    Path rows = meta.getParent().resolve(meta.getFileName().toString().replace(".properties", ".rows"));
    String suffix = ".tmp-" + UUID.randomUUID().toString();
    Path tempRows = rows.resolveSibling(rows.getFileName().toString() + suffix);
    Path tempMeta = meta.resolveSibling(meta.getFileName().toString() + suffix);
    writeRows(tempRows, record.rows);
    writeMeta(tempMeta, record, rows.getFileName().toString());
    secureFile(tempRows); secureFile(tempMeta);
    replace(tempRows, rows); replace(tempMeta, meta);
    return true;
  }

  synchronized boolean updateRow(String month, String recordId, int rowIndex, Map<Integer, String> values) throws IOException {
    if (!month.matches("20\\d{2}-(0[1-9]|1[0-2])") || !recordId.matches("[0-9a-f]{20}") || values.isEmpty()) return false;
    Path monthDir = monthsRoot.resolve(month).normalize();
    if (!monthDir.getParent().equals(monthsRoot) || !Files.isDirectory(monthDir)) return false;
    Path meta = null;
    try (DirectoryStream<Path> metas = Files.newDirectoryStream(monthDir, "*-" + recordId + ".properties")) {
      for (Path candidate : metas) { if (meta != null) return false; meta = candidate; }
    }
    if (meta == null) return false;
    ImportRecord record = readOne(meta);
    if (rowIndex < 0 || rowIndex >= record.rows.size()) return false;
    List<String> row = record.rows.get(rowIndex);
    while (row.size() < record.columns.size()) row.add("");
    for (Map.Entry<Integer, String> entry : values.entrySet()) {
      if (!editable(record.dataset, entry.getKey().intValue())) return false;
      row.set(entry.getKey().intValue(), entry.getValue() == null ? "" : entry.getValue());
    }
    record.updatedAt = Instant.now().toString();
    Path rows = meta.getParent().resolve(meta.getFileName().toString().replace(".properties", ".rows"));
    String suffix = ".tmp-" + UUID.randomUUID().toString();
    Path tempRows = rows.resolveSibling(rows.getFileName().toString() + suffix);
    Path tempMeta = meta.resolveSibling(meta.getFileName().toString() + suffix);
    writeRows(tempRows, record.rows); writeMeta(tempMeta, record, rows.getFileName().toString());
    secureFile(tempRows); secureFile(tempMeta); replace(tempRows, rows); replace(tempMeta, meta);
    return true;
  }

  synchronized List<String> months() throws IOException {
    Set<String> values = new HashSet<String>();
    for (ImportRecord record : readAll()) values.add(record.month);
    List<String> result = new ArrayList<String>(values);
    Collections.sort(result, Collections.reverseOrder());
    return result;
  }

  private ImportRecord readOne(Path meta) throws IOException {
    Properties p = new Properties();
    try (Reader reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) { p.load(reader); }
    ImportRecord r = new ImportRecord();
    r.id = p.getProperty("id", "");
    r.month = p.getProperty("month", "");
    r.dataset = p.getProperty("dataset", "");
    r.period = p.getProperty("period", "");
    r.filename = p.getProperty("filename", "");
    r.sheetName = p.getProperty("sheetName", "");
    r.importedAt = p.getProperty("importedAt", "");
    r.updatedAt = p.getProperty("updatedAt", "");
    r.headerRows = integer(p.getProperty("headerRows"), 1);
    int count = integer(p.getProperty("columns.count"), 0);
    for (int i = 0; i < count; i++) r.columns.add(p.getProperty("column." + i, ""));
    String file = p.getProperty("rows.file", meta.getFileName().toString().replace(".properties", ".rows"));
    Path rows = meta.getParent().resolve(file).normalize();
    if (!rows.getParent().equals(meta.getParent()) || !Files.isRegularFile(rows)) {
      throw new IOException("数据文件缺失或路径异常：" + meta.getFileName());
    }
    r.rows = readRows(rows, r.columns.size());
    return r;
  }

  private void writeMeta(Path path, ImportRecord r, String finalRowsName) throws IOException {
    Properties p = new Properties();
    p.setProperty("format", "1");
    p.setProperty("id", r.id);
    p.setProperty("month", r.month);
    p.setProperty("dataset", r.dataset);
    p.setProperty("period", r.period);
    p.setProperty("filename", r.filename);
    p.setProperty("sheetName", r.sheetName);
    p.setProperty("importedAt", r.importedAt.isEmpty() ? Instant.now().toString() : r.importedAt);
    p.setProperty("updatedAt", r.updatedAt == null ? "" : r.updatedAt);
    p.setProperty("headerRows", Integer.toString(r.headerRows));
    p.setProperty("columns.count", Integer.toString(r.columns.size()));
    for (int i = 0; i < r.columns.size(); i++) p.setProperty("column." + i, r.columns.get(i));
    p.setProperty("rows.file", finalRowsName);
    try (OutputStream output = Files.newOutputStream(path);
         OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
      p.store(writer, "Zhihui Xinguan data metadata");
    }
  }

  private void writeRows(Path path, List<List<String>> rows) throws IOException {
    Base64.Encoder encoder = Base64.getEncoder();
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      for (List<String> row : rows) {
        for (int i = 0; i < row.size(); i++) {
          if (i > 0) writer.write('\t');
          String value = row.get(i) == null ? "" : row.get(i);
          if (!value.isEmpty()) writer.write(encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
        writer.newLine();
      }
    }
  }

  private List<List<String>> readRows(Path path, int width) throws IOException {
    List<List<String>> result = new ArrayList<List<String>>();
    Base64.Decoder decoder = Base64.getDecoder();
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split("\\t", -1);
        List<String> row = new ArrayList<String>();
        for (int i = 0; i < width; i++) {
          String value = i < parts.length ? parts[i] : "";
          row.add(value.isEmpty() ? "" : new String(decoder.decode(value), StandardCharsets.UTF_8));
        }
        result.add(row);
      }
    }
    return result;
  }

  private static void validateRecord(ImportRecord r) {
    if (!r.month.matches("20\\d{2}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException("月份格式无效");
    if (!("multi".equals(r.dataset) || "negative".equals(r.dataset))) throw new IllegalArgumentException("数据类型无效");
    if (r.period.isEmpty() || r.period.length() > 80) throw new IllegalArgumentException("时间段标识无效");
    if (r.columns.isEmpty() || r.columns.size() > 100) throw new IllegalArgumentException("表头数量无效");
  }

  private static boolean editable(String dataset, int column) {
    return ("multi".equals(dataset) && column >= 17 && column <= 21)
        || ("negative".equals(dataset) && column >= 10 && column <= 11);
  }

  private static void preserveEditable(ImportRecord previous, ImportRecord incoming) {
    if (!previous.dataset.equals(incoming.dataset)) return;
    Map<String, List<String>> oldRows = new java.util.HashMap<String, List<String>>();
    for (List<String> row : previous.rows) { String key = businessKey(previous.dataset, row); if (!key.isEmpty()) oldRows.put(key, row); }
    int first = "multi".equals(incoming.dataset) ? 17 : 10;
    int last = "multi".equals(incoming.dataset) ? 21 : 11;
    for (List<String> row : incoming.rows) {
      List<String> old = oldRows.get(businessKey(incoming.dataset, row)); if (old == null) continue;
      while (row.size() < incoming.columns.size()) row.add("");
      for (int column = first; column <= last; column++) {
        String current = value(row, column); String saved = value(old, column);
        if (current.isEmpty() && !saved.isEmpty()) row.set(column, saved);
      }
    }
    incoming.updatedAt = previous.updatedAt;
  }

  private static String businessKey(String dataset, List<String> row) {
    if ("multi".equals(dataset)) { String code = value(row, 3); return !code.isEmpty() ? "C|" + code : "N|" + value(row, 2); }
    return value(row, 2) + "|" + value(row, 1) + "|" + value(row, 4);
  }
  private static String value(List<String> row, int index) { return index < row.size() && row.get(index) != null ? row.get(index).trim() : ""; }

  private static int integer(String value, int fallback) {
    try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
  }

  private static String shortHash(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < 10; i++) text.append(String.format("%02x", digest[i]));
      return text.toString();
    } catch (Exception e) { throw new IllegalStateException(e); }
  }

  private static void replace(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void secureDirectory(Path path) {
    try {
      Set<PosixFilePermission> permissions = new HashSet<PosixFilePermission>();
      permissions.add(PosixFilePermission.OWNER_READ);
      permissions.add(PosixFilePermission.OWNER_WRITE);
      permissions.add(PosixFilePermission.OWNER_EXECUTE);
      Files.setPosixFilePermissions(path, permissions);
    } catch (Exception ignored) { }
  }

  private static void secureFile(Path path) {
    try {
      Set<PosixFilePermission> permissions = new HashSet<PosixFilePermission>();
      permissions.add(PosixFilePermission.OWNER_READ);
      permissions.add(PosixFilePermission.OWNER_WRITE);
      Files.setPosixFilePermissions(path, permissions);
    } catch (Exception ignored) { }
  }
}

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;

final class WorkbookImporter {
  private static final Pattern MONTH_TOKEN = Pattern.compile("(?<!\\d)(20\\d{2})(0[1-9]|1[0-2])(?!\\d)");
  private static final Pattern MONTH_DASHED = Pattern.compile("(?<!\\d)(20\\d{2})[-_.](0[1-9]|1[0-2])(?!\\d)");
  private static final Pattern RANGE = Pattern.compile("(20\\d{2})[-_.](0[1-9]|1[0-2])[-_.](0[1-9]|[12]\\d|3[01])\\s*[~～至到_]\\s*(20\\d{2})[-_.](0[1-9]|1[0-2])[-_.](0[1-9]|[12]\\d|3[01])");
  private static final Pattern PERIOD_PART = Pattern.compile("20\\d{4}[-_](1|2)(?!\\d)");
  private static final int MAX_ROWS = 200000;
  private static final int MAX_COLUMNS = 100;

  ImportRecord read(Path path, String originalFilename, String monthOverride) throws WorkbookImportException {
    String lower = originalFilename.toLowerCase(Locale.ROOT);
    if (!(lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".et"))) {
      throw new WorkbookImportException("仅支持 .xls、.xlsx 和 .et 表格文件");
    }
    if (originalFilename.contains("预警信息工行版")) {
      throw new WorkbookImportException("这是一份预警总表，已按方案跳过；请上传多重预警排查清单或负面闭环清单");
    }
    try (InputStream input = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(input)) {
      if (workbook.getNumberOfSheets() < 1) throw new WorkbookImportException("表格中没有工作表");
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      DataFormatter formatter = new DataFormatter(Locale.CHINA);
      SheetMatch match = findDatasetSheet(workbook, formatter, evaluator);
      if (match == null) {
        throw new WorkbookImportException("未识别出业务表头；应为“多重预警排查清单”或“负面闭环清单”");
      }
      ImportRecord record = new ImportRecord();
      record.dataset = match.dataset;
      record.sheetName = match.sheet.getSheetName();
      record.headerRows = match.headerRows;
      record.filename = sanitizeFilename(originalFilename);
      record.month = resolveMonth(record.filename, monthOverride);
      record.period = resolvePeriod(record.filename, record.month, match.sheet, match.headerStart, formatter, evaluator);
      record.importedAt = Instant.now().toString();
      record.columns = buildColumns(match.sheet, match.headerStart, match.headerRows, match.width, formatter, evaluator);
      record.rows = readRows(match.sheet, match.headerStart + match.headerRows, match.width, formatter, evaluator);
      return record;
    } catch (WorkbookImportException e) {
      throw e;
    } catch (Exception e) {
      throw new WorkbookImportException("表格无法读取，请确认文件未损坏且未加密", e);
    }
  }

  private SheetMatch findDatasetSheet(Workbook workbook, DataFormatter formatter, FormulaEvaluator evaluator) {
    for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
      Sheet sheet = workbook.getSheetAt(s);
      int scanRows = Math.min(sheet.getLastRowNum(), 8);
      for (int r = 0; r <= scanRows; r++) {
        List<String> cells = rowValues(sheet.getRow(r), MAX_COLUMNS, formatter, evaluator);
        String joined = join(cells);
        if (joined.contains("多重预警信息") && joined.contains("客户编码")) {
          int width = Math.min(Math.max(lastCell(sheet.getRow(r)), lastCell(sheet.getRow(r + 1))), MAX_COLUMNS);
          return new SheetMatch(sheet, "multi", r, 2, Math.max(width, 22));
        }
        if (joined.contains("预警简述") && joined.contains("企业名称") && joined.contains("情况反馈")) {
          return new SheetMatch(sheet, "negative", r, 1, Math.min(Math.max(lastCell(sheet.getRow(r)), 13), MAX_COLUMNS));
        }
      }
    }
    return null;
  }

  private List<String> buildColumns(Sheet sheet, int headerStart, int headerRows, int width, DataFormatter formatter, FormulaEvaluator evaluator) {
    List<String> columns = new ArrayList<String>();
    for (int c = 0; c < width; c++) {
      List<String> parts = new ArrayList<String>();
      for (int r = headerStart; r < headerStart + headerRows; r++) {
        String value = mergedValue(sheet, r, c, formatter, evaluator).trim();
        if (!value.isEmpty() && !parts.contains(value)) parts.add(value);
      }
      String name = joinWith(parts, " / ");
      if (name.isEmpty()) name = "第" + (c + 1) + "列";
      columns.add(name);
    }
    return columns;
  }

  private List<List<String>> readRows(Sheet sheet, int dataStart, int width, DataFormatter formatter, FormulaEvaluator evaluator) throws WorkbookImportException {
    List<List<String>> rows = new ArrayList<List<String>>();
    int last = sheet.getLastRowNum();
    for (int r = dataStart; r <= last; r++) {
      Row row = sheet.getRow(r);
      List<String> values = rowValues(row, width, formatter, evaluator);
      if (!isBlank(values)) rows.add(values);
      if (rows.size() > MAX_ROWS) throw new WorkbookImportException("单张表最多支持 " + MAX_ROWS + " 条数据");
    }
    return rows;
  }

  private static String resolveMonth(String filename, String override) throws WorkbookImportException {
    String clean = override == null ? "" : override.trim();
    if (!clean.isEmpty()) {
      if (!clean.matches("20\\d{2}-(0[1-9]|1[0-2])")) throw new WorkbookImportException("手工月份应为 YYYY-MM，例如 2026-07");
      return clean;
    }
    Matcher token = MONTH_TOKEN.matcher(filename);
    if (token.find()) return token.group(1) + "-" + token.group(2);
    Matcher dashed = MONTH_DASHED.matcher(filename);
    if (dashed.find()) return dashed.group(1) + "-" + dashed.group(2);
    Matcher range = RANGE.matcher(filename);
    if (range.find()) return range.group(1) + "-" + range.group(2);
    throw new WorkbookImportException("无法从文件名识别月份，请在上传时手工选择月份");
  }

  private static String resolvePeriod(String filename, String month, Sheet sheet, int headerStart, DataFormatter formatter, FormulaEvaluator evaluator) {
    Matcher range = RANGE.matcher(filename);
    if (range.find()) {
      return range.group(1) + "-" + range.group(2) + "-" + range.group(3)
          + "~" + range.group(4) + "-" + range.group(5) + "-" + range.group(6);
    }
    Matcher part = PERIOD_PART.matcher(filename);
    if (part.find()) return month + "-P" + part.group(1);
    for (int r = 0; r < Math.min(headerStart + 4, sheet.getLastRowNum() + 1); r++) {
      String text = join(rowValues(sheet.getRow(r), Math.min(Math.max(lastCell(sheet.getRow(r)), 1), MAX_COLUMNS), formatter, evaluator));
      Matcher inSheet = RANGE.matcher(text);
      if (inSheet.find()) return inSheet.group(1) + "-" + inSheet.group(2) + "-" + inSheet.group(3)
          + "~" + inSheet.group(4) + "-" + inSheet.group(5) + "-" + inSheet.group(6);
    }
    return month;
  }

  private static String mergedValue(Sheet sheet, int row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
    for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
      CellRangeAddress range = sheet.getMergedRegion(i);
      if (range.isInRange(row, column)) {
        Row firstRow = sheet.getRow(range.getFirstRow());
        return cellValue(firstRow == null ? null : firstRow.getCell(range.getFirstColumn()), formatter, evaluator);
      }
    }
    Row current = sheet.getRow(row);
    return cellValue(current == null ? null : current.getCell(column), formatter, evaluator);
  }

  private static List<String> rowValues(Row row, int width, DataFormatter formatter, FormulaEvaluator evaluator) {
    List<String> values = new ArrayList<String>();
    for (int c = 0; c < width; c++) values.add(cellValue(row == null ? null : row.getCell(c), formatter, evaluator));
    return values;
  }

  private static String cellValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
    if (cell == null || cell.getCellType() == CellType.BLANK) return "";
    try { return formatter.formatCellValue(cell, evaluator).trim(); }
    catch (Exception ignored) { return formatter.formatCellValue(cell).trim(); }
  }

  private static int lastCell(Row row) { return row == null ? 0 : Math.max(row.getLastCellNum(), 0); }
  private static boolean isBlank(List<String> row) { for (String value : row) if (!value.trim().isEmpty()) return false; return true; }
  private static String join(List<String> values) { return joinWith(values, "|"); }
  private static String joinWith(List<String> values, String delimiter) {
    StringBuilder result = new StringBuilder();
    for (String value : values) { if (result.length() > 0) result.append(delimiter); result.append(value); }
    return result.toString();
  }
  private static String sanitizeFilename(String filename) {
    String value = filename.replace('\\', '/');
    int slash = value.lastIndexOf('/');
    if (slash >= 0) value = value.substring(slash + 1);
    value = value.replaceAll("[\\r\\n\\t]", " ").trim();
    return value.length() > 180 ? value.substring(value.length() - 180) : value;
  }

  private static final class SheetMatch {
    final Sheet sheet; final String dataset; final int headerStart; final int headerRows; final int width;
    SheetMatch(Sheet sheet, String dataset, int headerStart, int headerRows, int width) {
      this.sheet = sheet; this.dataset = dataset; this.headerStart = headerStart; this.headerRows = headerRows; this.width = width;
    }
  }
}

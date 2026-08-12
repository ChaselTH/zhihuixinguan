import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DashboardData {
  final RangeSelection range;
  final List<String> months;
  final List<ImportRecord> records;
  final List<RowRef> multiRows = new ArrayList<RowRef>();
  final List<RowRef> negativeRows = new ArrayList<RowRef>();
  final Map<String, BranchStats> branches = new LinkedHashMap<String, BranchStats>();
  final Map<String, Integer> industryCounts = new LinkedHashMap<String, Integer>();
  final Map<String, Integer> levelCounts = new LinkedHashMap<String, Integer>();
  final List<TrendPoint> trend = new ArrayList<TrendPoint>();
  int multiCount;
  int negativeCount;
  int completeMulti;
  int completeNegative;
  BigDecimal loanBalance = BigDecimal.ZERO;
  String latestUpdate = "";

  DashboardData(RangeSelection range, List<String> months, List<ImportRecord> all, List<ImportRecord> selected) {
    this.range = range;
    this.months = months;
    this.records = selected;
    for (ImportRecord record : selected) {
      String update = record.updatedAt != null && !record.updatedAt.isEmpty() ? record.updatedAt : record.importedAt;
      if (update != null && update.compareTo(latestUpdate) > 0) latestUpdate = update;
      for (int i = 0; i < record.rows.size(); i++) {
        List<String> row = record.rows.get(i);
        RowRef ref = new RowRef(record, i, row);
        if ("multi".equals(record.dataset)) {
          multiRows.add(ref);
          loanBalance = loanBalance.add(number(cell(row, 4)));
          add(industryCounts, normalize(cell(row, 7), "未填写行业"));
          BranchStats branch = branch(normalize(cell(row, 1), "未填写机构"));
          branch.multi++;
          if (multiComplete(row)) { branch.multiComplete++; completeMulti++; }
        } else if ("negative".equals(record.dataset)) {
          negativeRows.add(ref);
          add(levelCounts, normalize(cell(row, 3), "未分级"));
          BranchStats branch = branch(normalize(cell(row, 5), "未填写机构"));
          branch.negative++;
          if (negativeComplete(row)) { branch.negativeComplete++; completeNegative++; }
        }
      }
    }
    multiCount = multiRows.size();
    negativeCount = negativeRows.size();
    sortIndustries(industryCounts, 7);
    sortLevels(levelCounts, 8);
    sortBranches();
    buildTrend(all);
  }

  String loanText() {
    if (loanBalance.abs().compareTo(new BigDecimal("10000")) >= 0) return loanBalance.divide(new BigDecimal("10000"), 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " 亿元";
    return loanBalance.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " 万元";
  }

  int totalCount() { return multiCount + negativeCount; }
  int completedCount() { return completeMulti + completeNegative; }
  int completionPercent() { return totalCount() == 0 ? 0 : completedCount() * 100 / totalCount(); }

  List<RowRef> filtered(String dataset, String query, String branchName) {
    List<RowRef> source = "negative".equals(dataset) ? negativeRows : multiRows;
    String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    String branchFilter = branchName == null ? "" : branchName.trim();
    int branchColumn = "negative".equals(dataset) ? 5 : 1;
    List<RowRef> result = new ArrayList<RowRef>();
    for (RowRef ref : source) {
      if (!branchFilter.isEmpty() && !branchFilter.equals(cell(ref.values, branchColumn).trim())) continue;
      if (q.isEmpty()) { result.add(ref); continue; }
      for (String value : ref.values) if (value.toLowerCase(Locale.ROOT).contains(q)) { result.add(ref); break; }
    }
    return result;
  }

  static boolean rowComplete(String dataset, List<String> row) { return "negative".equals(dataset) ? negativeComplete(row) : multiComplete(row); }
  static String cell(List<String> row, int index) { return index >= 0 && index < row.size() && row.get(index) != null ? row.get(index) : ""; }

  private BranchStats branch(String name) {
    BranchStats result = branches.get(name);
    if (result == null) { result = new BranchStats(name); branches.put(name, result); }
    return result;
  }
  private void sortBranches() {
    List<BranchStats> values = new ArrayList<BranchStats>(branches.values());
    Collections.sort(values, new Comparator<BranchStats>() {
      public int compare(BranchStats a, BranchStats b) { int total = Integer.compare(b.total(), a.total()); return total != 0 ? total : a.name.compareTo(b.name); }
    });
    branches.clear(); for (BranchStats value : values) branches.put(value.name, value);
  }
  private void buildTrend(List<ImportRecord> all) {
    for (String month : range.timeline) {
      TrendPoint point = new TrendPoint(month);
      for (ImportRecord record : all) if (month.equals(record.month)) {
        if ("multi".equals(record.dataset)) point.multi += record.rows.size(); else point.negative += record.rows.size();
      }
      trend.add(point);
    }
  }

  private static boolean multiComplete(List<String> row) {
    if (!yesNo(cell(row, 17))) return false;
    if (!oneOf(cell(row, 18), new String[]{"无需管控","日常一般管控","日常一半管控","重点关注管控"})) return false;
    String control = cell(row, 18).trim();
    if (("日常一般管控".equals(control) || "日常一半管控".equals(control) || "重点关注管控".equals(control)) && cell(row, 19).trim().isEmpty()) return false;
    if (!oneOf(cell(row, 20), new String[]{"增加","维持","压降","退出"})) return false;
    return true;
  }
  private static boolean negativeComplete(List<String> row) { return yesNo(cell(row, 10)) && !cell(row, 11).trim().isEmpty(); }
  private static boolean yesNo(String value) { String text = value.trim(); return "是".equals(text) || "否".equals(text); }
  private static boolean oneOf(String value, String[] values) { String text = value.trim(); for (String candidate : values) if (candidate.equals(text)) return true; return false; }
  private static String normalize(String value, String fallback) { String text = value == null ? "" : value.trim(); return text.isEmpty() ? fallback : text; }
  private static void add(Map<String, Integer> map, String key) { map.put(key, Integer.valueOf(map.containsKey(key) ? map.get(key).intValue() + 1 : 1)); }
  private static BigDecimal number(String text) {
    if (text == null) return BigDecimal.ZERO;
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(text.replace(",", "").replace("，", ""));
    try { return matcher.find() ? new BigDecimal(matcher.group()) : BigDecimal.ZERO; } catch (Exception ignored) { return BigDecimal.ZERO; }
  }
  private static void sortIndustries(Map<String, Integer> map, int visible) {
    List<Map.Entry<String, Integer>> values = sorted(map); map.clear(); int other = 0;
    for (int i = 0; i < values.size(); i++) { if (i < visible) map.put(values.get(i).getKey(), values.get(i).getValue()); else other += values.get(i).getValue().intValue(); }
    if (other > 0) map.put("其他", Integer.valueOf(other));
  }
  private static void sortLevels(Map<String, Integer> map, int visible) {
    List<Map.Entry<String, Integer>> values = sorted(map); map.clear(); for (int i = 0; i < values.size() && i < visible; i++) map.put(values.get(i).getKey(), values.get(i).getValue());
  }
  private static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> map) {
    List<Map.Entry<String, Integer>> values = new ArrayList<Map.Entry<String, Integer>>(map.entrySet());
    Collections.sort(values, new Comparator<Map.Entry<String, Integer>>() { public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) { int count = b.getValue().compareTo(a.getValue()); return count != 0 ? count : a.getKey().compareTo(b.getKey()); } });
    return values;
  }

  static final class BranchStats {
    final String name; int multi; int negative; int multiComplete; int negativeComplete;
    BranchStats(String name) { this.name = name; }
    int total() { return multi + negative; }
    int complete() { return multiComplete + negativeComplete; }
    int percent() { return total() == 0 ? 0 : complete() * 100 / total(); }
  }
  static final class TrendPoint { final String month; int multi; int negative; TrendPoint(String month) { this.month = month; } }
}

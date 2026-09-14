import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RangeSelection {
  final String scope;
  final String month;
  final String year;
  final int quarter;
  final String start;
  final String end;
  final String label;
  final List<String> timeline;

  private RangeSelection(String scope, String month, String year, int quarter, String start, String end, String label) {
    this.scope = scope;
    this.month = month;
    this.year = year;
    this.quarter = quarter;
    this.start = start;
    this.end = end;
    this.label = label;
    this.timeline = monthsBetween(start, end);
  }

  static RangeSelection from(Map<String, String> query, List<String> availableMonths) {
    String latest = availableMonths.isEmpty() ? YearMonth.now().toString() : availableMonths.get(0);
    String requestedScope = query.containsKey("scopeMode") ? query.get("scopeMode") : query.get("scope");
    String scope = "quarter".equals(requestedScope) || "year".equals(requestedScope) || "custom".equals(requestedScope) ? requestedScope : "month";
    String month = validMonth(query.get("month")) ? query.get("month") : latest;
    String year = validYear(query.get("year")) ? query.get("year") : month.substring(0, 4);
    int quarter = integer(query.get("quarter"), ((Integer.parseInt(month.substring(5)) - 1) / 3) + 1);
    if (quarter < 1 || quarter > 4) quarter = 1;
    String start;
    String end;
    String label;
    if ("year".equals(scope)) {
      start = year + "-01";
      end = year + "-12";
      label = year + "年全年";
    } else if ("quarter".equals(scope)) {
      int first = (quarter - 1) * 3 + 1;
      start = year + "-" + two(first);
      end = year + "-" + two(first + 2);
      label = year + "年第" + quarter + "季度";
    } else if ("custom".equals(scope)) {
      start = validMonth(query.get("start")) ? query.get("start") : month;
      end = validMonth(query.get("end")) ? query.get("end") : month;
      if (start.compareTo(end) > 0) { String swap = start; start = end; end = swap; }
      if (monthsBetween(start, end).size() > 120) end = YearMonth.parse(start).plusMonths(119).toString();
      label = monthLabel(start) + (start.equals(end) ? "" : " 至 " + monthLabel(end));
    } else {
      start = month;
      end = month;
      label = monthLabel(month);
    }
    return new RangeSelection(scope, month, year, quarter, start, end, label);
  }

  boolean includes(String candidate) { return validMonth(candidate) && candidate.compareTo(start) >= 0 && candidate.compareTo(end) <= 0; }

  List<ImportRecord> filter(List<ImportRecord> records) {
    List<ImportRecord> result = new ArrayList<ImportRecord>();
    for (ImportRecord record : records) if (includes(record.month)) result.add(record);
    return result;
  }

  String queryString() {
    StringBuilder result = new StringBuilder("scope=").append(PageLayout.u(scope));
    result.append("&month=").append(PageLayout.u(month));
    result.append("&year=").append(PageLayout.u(year));
    result.append("&quarter=").append(quarter);
    result.append("&start=").append(PageLayout.u(start));
    result.append("&end=").append(PageLayout.u(end));
    return result.toString();
  }

  Map<String, String> asParameters() {
    Map<String, String> result = new LinkedHashMap<String, String>();
    result.put("scope", scope); result.put("month", month); result.put("year", year);
    result.put("quarter", Integer.toString(quarter)); result.put("start", start); result.put("end", end);
    return result;
  }

  static List<String> availableYears(List<String> months) {
    List<String> result = new ArrayList<String>();
    for (String month : months) if (validMonth(month) && !result.contains(month.substring(0, 4))) result.add(month.substring(0, 4));
    if (result.isEmpty()) result.add(Integer.toString(YearMonth.now().getYear()));
    Collections.sort(result, Collections.reverseOrder());
    return result;
  }

  static boolean validMonth(String value) { return value != null && value.matches("20\\d{2}-(0[1-9]|1[0-2])"); }
  static boolean validYear(String value) { return value != null && value.matches("20\\d{2}"); }
  static String monthLabel(String value) { return validMonth(value) ? value.substring(0, 4) + "年" + value.substring(5) + "月" : value; }
  private static List<String> monthsBetween(String start, String end) {
    List<String> result = new ArrayList<String>();
    YearMonth current = YearMonth.parse(start);
    YearMonth last = YearMonth.parse(end);
    while (!current.isAfter(last) && result.size() < 120) { result.add(current.toString()); current = current.plusMonths(1); }
    return result;
  }
  private static String two(int value) { return value < 10 ? "0" + value : Integer.toString(value); }
  private static int integer(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception e) { return fallback; } }
}

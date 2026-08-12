import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class Html {
  private final String version;
  Html(String version) { this.version = version; }

  String dashboard(DashboardData data) {
    StringBuilder body = new StringBuilder();
    body.append("<div class=\"hero\"><div class=\"hero-inner clearfix\"><div class=\"brand-block\"><span class=\"brand-mark\">信</span><div class=\"brand-copy\"><h1>智慧信管</h1><p>企业风险预警 · 闭环跟踪 · 管控决策</p></div></div>")
        .append("<div class=\"hero-meta\">最近更新：").append(e(formatDate(data.latestUpdate))).append("<span class=\"meta-sep\">|</span>V").append(e(version)).append("</div></div></div>");
    body.append("<div class=\"page-shell\">").append(rangeForm("/", data, ""));
    body.append("<div class=\"metric-grid clearfix\">")
        .append(metric("预", "多重预警客户", data.multiCount, "不统计支行补充字段", "red"))
        .append(metric("闭", "负面闭环事项", data.negativeCount, "不统计 K、L 填报内容", "amber"))
        .append(metric("补", "资料补充进度", data.completionPercent() + "%", data.completedCount() + "/" + data.totalCount() + " 条已完成", "blue"))
        .append(metric("额", "预警客户贷款余额", data.loanText(), "仅多重预警清单口径", "green"))
        .append("</div>");
    if (data.records.isEmpty()) body.append(empty("所选时间范围尚未导入业务数据", "管理员上传表格后，统计、图表和明细会自动生成。", false));
    else if (data.totalCount() == 0) body.append(empty("表头模板已就绪", "当前文件仅包含表头，后续上传含数据的同类文件即可更新。", true));

    body.append("<div class=\"dashboard-columns clearfix\"><div class=\"dashboard-main\"><section class=\"panel\"><div class=\"panel-head\"><div><span class=\"eyebrow\">所选时间范围</span><h2>预警与闭环趋势</h2></div><div class=\"legend\"><span><i class=\"legend-red\"></i>多重预警</span><span><i class=\"legend-amber\"></i>负面闭环</span></div></div>")
        .append(trend(data.trend)).append("</section><section class=\"panel\"><div class=\"panel-head\"><div><span class=\"eyebrow\">多重预警</span><h2>所属行业分布</h2></div></div>")
        .append(bars(data.industryCounts, "red")).append("</section></div><div class=\"dashboard-side-column\"><section class=\"panel branch-panel\"><div class=\"panel-head\"><div><span class=\"eyebrow\">机构分布</span><h2>支行数据与填报进度</h2></div></div>")
        .append(branches(data)).append("</section><section class=\"panel\"><div class=\"panel-head\"><div><span class=\"eyebrow\">负面闭环</span><h2>预警变动级别</h2></div></div>")
        .append(bars(data.levelCounts, "amber")).append("</section></div></div>");
    body.append(preview(data, "multi", data.multiRows)).append(preview(data, "negative", data.negativeRows));
    body.append("<div class=\"data-note clearfix\"><span>数据口径：预警总表不参与展示；支行补充字段仅用于填报进度，不参与风险统计。</span><span class=\"data-note-right\">范围：").append(e(data.range.label)).append("</span></div></div>");
    return page("智慧信管", body.toString(), false);
  }

  String details(DashboardData data, String dataset, String query, String branch, int pageNo, AuthService.Session formSession) {
    boolean negative = "negative".equals(dataset);
    List<RowRef> rows = data.filtered(dataset, query, branch);
    int pageSize = 50, pages = Math.max(1, (rows.size() + pageSize - 1) / pageSize);
    if (pageNo < 1) pageNo = 1; if (pageNo > pages) pageNo = pages;
    int start = Math.min(rows.size(), (pageNo - 1) * pageSize), end = Math.min(rows.size(), start + pageSize);
    StringBuilder body = new StringBuilder(simpleHeader()).append("<div class=\"page-shell details-shell\">");
    body.append("<div class=\"breadcrumb\"><a href=\"/?").append(hrefQuery(data.range.queryString())).append("\">智慧信管</a><span>›</span><strong>").append(negative ? "负面闭环明细" : "多重预警明细").append("</strong></div>")
        .append(rangeForm("/details", data, hidden("dataset", dataset) + hidden("q", query) + hidden("branch", branch)));
    body.append("<div class=\"details-title clearfix\"><div><span class=\"eyebrow\">").append(e(data.range.label)).append("</span><h1>").append(negative ? "负面闭环事项" : "多重预警客户").append(branch.isEmpty() ? "明细" : " · " + e(branch)).append("</h1><p>共 ").append(rows.size()).append(" 条匹配记录，黄色单元格为支行补充字段</p></div>")
        .append("<div class=\"details-actions\"><a class=\"btn btn-export\" href=\"").append(exportUrl(data.range, dataset, query, branch)).append("\">导出 Excel</a></div>")
        .append("<form class=\"search-form\" method=\"get\" action=\"/details\">").append(rangeHidden(data.range)).append(hidden("dataset", dataset)).append(hidden("branch", branch)).append("<label class=\"sr-only\" for=\"q\">检索</label><input id=\"q\" name=\"q\" value=\"").append(e(query)).append("\" placeholder=\"检索企业、支行或预警信息\"><button class=\"btn btn-dark\" type=\"submit\">检索</button></form></div>");
    body.append(detailTable(dataset, rows.subList(start, end), formSession, data.range, branch, query, pageNo));
    body.append(pagination(data.range, dataset, query, branch, pageNo, pages)).append("</div>");
    return page((negative ? "负面闭环" : "多重预警") + " - 智慧信管", body.toString(), false);
  }

  String branch(DashboardData data, String branch, AuthService.Session formSession) {
    List<RowRef> multi = data.filtered("multi", "", branch), negative = data.filtered("negative", "", branch);
    DashboardData.BranchStats stats = data.branches.get(branch);
    StringBuilder body = new StringBuilder(simpleHeader()).append("<div class=\"page-shell details-shell\"><div class=\"breadcrumb\"><a href=\"/?").append(hrefQuery(data.range.queryString())).append("\">智慧信管</a><span>›</span><strong>").append(e(branch)).append("</strong></div>")
        .append(rangeForm("/branch", data, hidden("branch", branch)))
        .append("<div class=\"branch-title\"><span class=\"eyebrow\">支行明细</span><h1>").append(e(branch)).append("</h1><p>").append(e(data.range.label)).append(" · 多重预警 ").append(multi.size()).append(" 条 · 负面闭环 ").append(negative.size()).append(" 条 · 填报完成率 ").append(stats == null ? 0 : stats.percent()).append("%</p></div>")
        .append(branchSection(data, "multi", multi, branch, formSession)).append(branchSection(data, "negative", negative, branch, formSession)).append("</div>");
    return page(branch + " - 智慧信管", body.toString(), false);
  }

  String adminLogin(String error) {
    StringBuilder body = new StringBuilder("<div class=\"login-page\"><div class=\"login-brand\"><span class=\"brand-mark\">信</span><h1>智慧信管</h1><p>数据管理中心</p></div><div class=\"login-card\"><h2>管理员登录</h2><p class=\"login-help\">请输入管理员密码进入数据更新页面</p>");
    if (error != null && !error.isEmpty()) body.append("<div class=\"alert alert-error\">").append(e(error)).append("</div>");
    body.append("<form method=\"post\" action=\"/admin/login\"><label for=\"password\">管理员密码</label><input id=\"password\" name=\"password\" type=\"password\"><button class=\"btn btn-primary btn-block\" type=\"submit\">登录管理中心</button></form><a class=\"back-link\" href=\"/\">← 返回智慧信管</a></div><div class=\"login-version\">V").append(e(version)).append("</div></div>");
    return page("管理员登录 - 智慧信管", body.toString(), true);
  }

  String admin(List<ImportRecord> records, List<String> months, AuthService.Session session, String notice, boolean error) {
    StringBuilder body = new StringBuilder(simpleHeader()).append("<div class=\"page-shell admin-shell\"><div class=\"admin-nav clearfix\"><div><span class=\"eyebrow\">管理中心</span><h1>数据更新</h1><p>可一次多选多重预警和负面闭环表格，系统逐个识别并更新。</p></div><form method=\"post\" action=\"/admin/logout\">").append(hidden("csrf", session.csrf)).append("<button type=\"submit\" class=\"btn btn-light\">退出登录</button></form></div>");
    if (notice != null && !notice.isEmpty()) body.append("<div class=\"alert ").append(error ? "alert-error" : "alert-success").append("\">").append(e(notice)).append("</div>");
    body.append("<div class=\"admin-grid clearfix\"><section class=\"admin-card upload-card\"><div class=\"admin-card-head\"><span class=\"step-icon\">↑</span><div><h2>批量上传并更新</h2><p>支持 .et、.xls、.xlsx，可按 Ctrl 或 Shift 多选文件</p></div></div><form method=\"post\" action=\"/admin/upload\" enctype=\"multipart/form-data\">").append(hidden("csrf", session.csrf)).append("<label for=\"files\">选择一个或多个表格</label><input class=\"file-input\" id=\"files\" type=\"file\" name=\"files\" multiple=\"multiple\" accept=\".et,.xls,.xlsx\"><label for=\"month\">统一指定月份 <span class=\"field-note\">可留空，分别从文件名识别</span></label><input id=\"month\" name=\"month\" type=\"text\" maxlength=\"7\" placeholder=\"例如 2026-07\"><button type=\"submit\" class=\"btn btn-primary btn-block\">批量更新数据</button></form><div class=\"rule-box\"><strong>更新规则</strong><ul><li>同一类型、同一时间段替换原数据</li><li>不同月份或时间段保留并新增</li><li>预警信息工行版总表自动跳过</li></ul></div></section>")
        .append("<section class=\"admin-card password-card\"><div class=\"admin-card-head\"><span class=\"step-icon lock\">安</span><div><h2>修改管理员密码</h2><p>修改后所有登录会话将退出</p></div></div><form method=\"post\" action=\"/admin/password\">").append(hidden("csrf", session.csrf)).append("<label>当前密码</label><input name=\"current\" type=\"password\"><label>新密码</label><input name=\"next\" type=\"password\"><label>确认新密码</label><input name=\"confirm\" type=\"password\"><button type=\"submit\" class=\"btn btn-dark btn-block\">保存新密码</button></form><p class=\"security-note\">密码至少 10 位，仅保存加盐加密结果。</p></section></div>");
    body.append("<section class=\"history-panel\"><div class=\"panel-head\"><div><span class=\"eyebrow\">导入记录</span><h2>当前数据批次</h2></div><span class=\"history-count\">").append(records.size()).append(" 个批次 / ").append(months.size()).append(" 个月份</span></div><div class=\"table-scroll\"><table class=\"data-table admin-table\"><thead><tr><th>月份</th><th>数据类型</th><th>时间段</th><th>文件名</th><th>记录数</th><th>最近更新</th></tr></thead><tbody>");
    if (records.isEmpty()) body.append("<tr><td colspan=\"6\" class=\"table-empty\">尚无导入记录</td></tr>");
    for (ImportRecord r : records) body.append("<tr><td><strong>").append(e(r.month)).append("</strong></td><td>").append(e(r.datasetLabel())).append("</td><td>").append(e(r.period)).append("</td><td>").append(e(r.filename)).append("</td><td>").append(r.rows.size()).append("</td><td>").append(e(formatTime(r.updatedAt == null || r.updatedAt.isEmpty() ? r.importedAt : r.updatedAt))).append("</td></tr>");
    body.append("</tbody></table></div></section></div>"); return page("数据管理 - 智慧信管", body.toString(), true);
  }

  String errorPage(int status, String message) { return page("访问提示 - 智慧信管", simpleHeader() + "<div class=\"error-page\"><strong>" + status + "</strong><h1>访问未完成</h1><p>" + e(message) + "</p><a class=\"btn btn-dark\" href=\"/\">返回首页</a></div>", false); }

  private String rangeForm(String action, DashboardData data, String extras) {
    RangeSelection r = data.range;
    StringBuilder html = new StringBuilder("<form class=\"toolbar range-toolbar clearfix\" method=\"get\" action=\"").append(e(action)).append("\">").append(extras).append(hidden("scope", r.scope))
        .append("<div class=\"month-copy\"><span class=\"eyebrow\">当前统计范围</span><strong>").append(e(r.label)).append("</strong></div>")
        .append("<div class=\"range-controls\"><div class=\"range-tabs\"><span>统计方式</span>")
        .append(scopeButton("month", "月度", r.scope)).append(scopeButton("quarter", "季度", r.scope))
        .append(scopeButton("year", "年度", r.scope)).append(scopeButton("custom", "自定义", r.scope))
        .append("</div><div class=\"range-active-fields\">");
    if ("quarter".equals(r.scope)) {
      html.append(hidden("month", r.month)).append(hidden("start", r.start)).append(hidden("end", r.end))
          .append("<label>年度<select name=\"year\">");
      for (String year : RangeSelection.availableYears(data.months)) html.append(option(year, year + "年", r.year));
      html.append("</select></label><label>季度<select name=\"quarter\">");
      for (int q = 1; q <= 4; q++) html.append(option(Integer.toString(q), "第" + q + "季度", Integer.toString(r.quarter)));
      html.append("</select></label>");
    } else if ("year".equals(r.scope)) {
      html.append(hidden("month", r.month)).append(hidden("quarter", Integer.toString(r.quarter))).append(hidden("start", r.start)).append(hidden("end", r.end))
          .append("<label>年度<select name=\"year\">");
      for (String year : RangeSelection.availableYears(data.months)) html.append(option(year, year + "年", r.year));
      html.append("</select></label>");
    } else if ("custom".equals(r.scope)) {
      html.append(hidden("month", r.month)).append(hidden("year", r.year)).append(hidden("quarter", Integer.toString(r.quarter)))
          .append("<label>开始月份<input name=\"start\" value=\"").append(e(r.start)).append("\" placeholder=\"YYYY-MM\"></label>")
          .append("<label>结束月份<input name=\"end\" value=\"").append(e(r.end)).append("\" placeholder=\"YYYY-MM\"></label>");
    } else {
      html.append(hidden("year", r.year)).append(hidden("quarter", Integer.toString(r.quarter))).append(hidden("start", r.start)).append(hidden("end", r.end))
          .append("<label>统计月份<select name=\"month\">");
      if (data.months.isEmpty()) html.append(option(r.month, r.month, r.month)); else for (String month : data.months) html.append(option(month, month, r.month));
      html.append("</select></label>");
    }
    return html.append("<button class=\"btn btn-dark range-apply\" type=\"submit\">查看</button></div></div></form>").toString();
  }

  private String branches(DashboardData data) {
    if (data.branches.isEmpty()) return "<div class=\"chart-empty small\"><span>暂无机构数据</span></div>";
    StringBuilder html = new StringBuilder("<div class=\"branch-list\">");
    for (DashboardData.BranchStats branch : data.branches.values()) {
      boolean complete = branch.total() > 0 && branch.complete() == branch.total();
      html.append("<a class=\"branch-row\" href=\"/branch?").append(hrefQuery(data.range.queryString())).append("&amp;branch=").append(u(branch.name)).append("\"><span class=\"completion-icon ").append(complete ? "done" : "pending").append("\">").append(complete ? "✓" : "!").append("</span><span class=\"branch-name\">").append(e(branch.name)).append("<small>多重 ").append(branch.multi).append(" · 闭环 ").append(branch.negative).append("</small></span><span class=\"branch-rate\">").append(branch.complete()).append("/").append(branch.total()).append("<small>").append(branch.percent()).append("%</small></span></a>");
    }
    return html.append("</div>").toString();
  }

  private String preview(DashboardData data, String dataset, List<RowRef> rows) {
    boolean negative = "negative".equals(dataset);
    StringBuilder html = new StringBuilder("<section class=\"panel detail-preview\"><div class=\"panel-head\"><div><span class=\"eyebrow\">").append(negative ? "闭环跟踪" : "风险客户").append("</span><h2>").append(negative ? "负面闭环事项" : "多重预警重点清单").append("</h2></div><div class=\"panel-actions\"><a class=\"btn btn-export\" href=\"").append(exportUrl(data.range, dataset, "", "")).append("\">导出 Excel</a><a class=\"btn btn-light\" href=\"").append(detailUrl(data.range, dataset, "", "")).append("\">查看全部 ").append(rows.size()).append(" 条</a></div></div>");
    return html.append(previewTable(dataset, rows.subList(0, Math.min(rows.size(), 8)))).append("</section>").toString();
  }

  private String branchSection(DashboardData data, String dataset, List<RowRef> rows, String branch, AuthService.Session formSession) {
    boolean negative = "negative".equals(dataset);
    StringBuilder html = new StringBuilder("<section class=\"panel detail-preview\"><div class=\"panel-head\"><div><span class=\"eyebrow\">").append(negative ? "负面闭环" : "多重预警").append("</span><h2>").append(negative ? "负面闭环事项" : "多重预警客户").append("</h2></div><div class=\"panel-actions\"><a class=\"btn btn-export\" href=\"").append(exportUrl(data.range, dataset, "", branch)).append("\">导出 Excel</a><a class=\"btn btn-light\" href=\"").append(detailUrl(data.range, dataset, "", branch)).append("\">分页查看</a></div></div>");
    return html.append(detailTable(dataset, rows.subList(0, Math.min(rows.size(), 50)), formSession, data.range, branch, "", 1)).append("</section>").toString();
  }

  private String previewTable(String dataset, List<RowRef> refs) {
    boolean negative = "negative".equals(dataset); int[] indexes = negative ? new int[]{2,1,3,5,6,7,9} : new int[]{2,1,4,5,7,16}; String[] headers = negative ? new String[]{"企业名称","预警简述","变动级别","支行","客户经理","贷款余额","情况反馈"} : new String[]{"客户全称","支行","贷款余额","信用等级","所属行业","多重预警信息"};
    StringBuilder html = new StringBuilder("<div class=\"table-scroll\"><table class=\"data-table compact\"><thead><tr>"); for (String header : headers) html.append("<th>").append(e(header)).append("</th>"); html.append("</tr></thead><tbody>");
    if (refs.isEmpty()) html.append("<tr><td colspan=\"").append(headers.length).append("\" class=\"table-empty\">暂无数据</td></tr>");
    for (RowRef ref : refs) { html.append("<tr>"); for (int i = 0; i < indexes.length; i++) html.append("<td").append(i == 0 ? " class=\"cell-strong\"" : "").append(">").append(e(DashboardData.cell(ref.values, indexes[i]))).append("</td>"); html.append("</tr>"); }
    return html.append("</tbody></table></div>").toString();
  }

  private String detailTable(String dataset, List<RowRef> refs, AuthService.Session session, RangeSelection range, String branch, String query, int pageNo) {
    boolean negative = "negative".equals(dataset);
    String[] headers = negative ? new String[]{"序号","预警简述","企业名称","变动级别","变动详情","支行","客户经理","贷款余额","信用等级","情况反馈","近6个月还款能力影响","风险管控措施","时间顺序"} : new String[]{"序号","支行","客户全称","客户编码","贷款余额","信用等级","企业规模","所属行业","2024销售收入","2025销售收入","2024净利润","2025净利润","2024融资总额","2025融资总额","2024我行融资","2025我行融资","多重预警信息","未来6个月违约风险","后续管控分类","具体管控目标及措施","本年度融资策略","备注"};
    StringBuilder html = new StringBuilder("<form class=\"batch-edit-form\" method=\"post\" action=\"/update-batch\">").append(hidden("csrf", session.csrf)).append(rangeHidden(range)).append(hidden("dataset", dataset)).append(hidden("branch", branch)).append(hidden("q", query)).append(hidden("page", Integer.toString(pageNo))).append(hidden("rows", Integer.toString(refs.size())));
    if (!refs.isEmpty()) html.append("<div class=\"batch-edit-bar clearfix\"><span>本表黄色区域可统一填写，完成后一次保存当前 ").append(refs.size()).append(" 条</span><button class=\"btn btn-primary\" type=\"submit\">保存资料补充</button></div>");
    html.append("<div class=\"table-scroll\"><table class=\"data-table detail-table\"><thead><tr>"); for (int i = 0; i < headers.length; i++) html.append("<th class=\"").append(columnClass(dataset, i)).append(editable(dataset, i) ? " editable-head" : "").append("\">").append(e(headers[i])).append("</th>"); html.append("</tr></thead><tbody>");
    if (refs.isEmpty()) html.append("<tr><td colspan=\"").append(headers.length).append("\" class=\"table-empty\">暂无数据</td></tr>");
    for (int item = 0; item < refs.size(); item++) {
      RowRef ref = refs.get(item);
      html.append("<tr class=\"").append(DashboardData.rowComplete(dataset, ref.values) ? "row-complete" : "row-pending").append("\">");
      for (int c = 0; c < headers.length; c++) {
        String value = DashboardData.cell(ref.values, c);
        if (editable(dataset, c)) html.append(editCell(dataset, item, c, value));
        else {
          html.append("<td class=\"").append(columnClass(dataset, c)).append(c == 2 ? " cell-strong" : "").append("\">").append(e(value));
          if (c == 0) html.append(hidden("m" + item, ref.record.month)).append(hidden("r" + item, ref.record.id)).append(hidden("i" + item, Integer.toString(ref.rowIndex)));
          html.append("</td>");
        }
      }
      html.append("</tr>");
    }
    return html.append("</tbody></table></div></form>").toString();
  }

  private String editCell(String dataset, int item, int column, String value) {
    StringBuilder html = new StringBuilder("<td class=\"editable-cell ").append(columnClass(dataset, column)).append("\">");
    String[] values = null;
    if (("multi".equals(dataset) && column == 17) || ("negative".equals(dataset) && column == 10)) values = new String[]{"","是","否"};
    else if ("multi".equals(dataset) && column == 18) values = new String[]{"","无需管控","日常一半管控","重点关注管控"};
    else if ("multi".equals(dataset) && column == 20) values = new String[]{"","增加","维持","压降","退出"};
    String name = "v" + item + "_" + column;
    if (values != null) { html.append("<select name=\"").append(name).append("\">"); for (String candidate : values) html.append(option(candidate, candidate.isEmpty() ? "请选择" : candidate, value)); html.append("</select>"); }
    else html.append("<textarea name=\"").append(name).append("\" rows=\"3\">").append(e(value)).append("</textarea>");
    return html.append("</td>").toString();
  }

  private static String columnClass(String dataset, int column) {
    if (column == 2) return "col-company";
    if ("negative".equals(dataset)) {
      if (column == 4 || column == 9 || column == 11) return "col-long";
      if (column == 1) return "col-medium";
    } else {
      if (column == 16 || column == 19) return "col-long";
      if (column == 21) return "col-medium";
      if (column >= 8 && column <= 15) return "col-number";
    }
    return "col-standard";
  }

  private String trend(List<DashboardData.TrendPoint> points) {
    if (points.isEmpty()) return "<div class=\"chart-empty\"><span>暂无趋势数据</span></div>";
    int max = 1; for (DashboardData.TrendPoint p : points) max = Math.max(max, Math.max(p.multi, p.negative)); double width = Math.max(7.5, Math.min(16.666, 100.0 / points.size()));
    StringBuilder html = new StringBuilder("<div class=\"trend-chart clearfix\">"); for (DashboardData.TrendPoint p : points) { int m = Math.max(p.multi > 0 ? 2 : 0, p.multi * 100 / max), n = Math.max(p.negative > 0 ? 2 : 0, p.negative * 100 / max); html.append("<div class=\"trend-col\" style=\"width:").append(String.format(java.util.Locale.ROOT, "%.3f", width)).append("%\"><div class=\"trend-values\"><span>").append(p.multi).append("</span><span>").append(p.negative).append("</span></div><div class=\"trend-bars\"><i class=\"trend-multi\" style=\"height:").append(m).append("%\"></i><i class=\"trend-negative\" style=\"height:").append(n).append("%\"></i></div><div class=\"trend-label\">").append(e(p.month.substring(2))).append("</div></div>"); }
    return html.append("</div>").toString();
  }
  private String bars(Map<String, Integer> values, String color) { if (values.isEmpty()) return "<div class=\"chart-empty small\"><span>暂无分布数据</span></div>"; int max = 1; for (Integer v : values.values()) max = Math.max(max, v.intValue()); StringBuilder html = new StringBuilder("<div class=\"bar-chart\">"); for (Map.Entry<String, Integer> entry : values.entrySet()) { int width = Math.max(2, entry.getValue().intValue() * 100 / max); html.append("<div class=\"bar-row\"><div class=\"bar-label\"><span>").append(e(entry.getKey())).append("</span><strong>").append(entry.getValue()).append("</strong></div><div class=\"bar-track\"><i class=\"bar-fill bar-").append(color).append("\" style=\"width:").append(width).append("%\"></i></div></div>"); } return html.append("</div>").toString(); }
  private String metric(String icon, String label, int value, String note, String color) { return metric(icon, label, Integer.toString(value), note, color); }
  private String metric(String icon, String label, String value, String note, String color) { return "<div class=\"metric-card\"><span class=\"metric-icon icon-" + color + "\">" + e(icon) + "</span><div class=\"metric-body\"><span class=\"metric-label\">" + e(label) + "</span><strong>" + e(value) + "</strong><small>" + e(note) + "</small></div></div>"; }
  private String empty(String title, String note, boolean ready) { return "<div class=\"empty-banner" + (ready ? " ready" : "") + "\"><span class=\"empty-icon\">" + (ready ? "✓" : "i") + "</span><div><strong>" + e(title) + "</strong><p>" + e(note) + "</p></div></div>"; }

  private String pagination(RangeSelection range, String dataset, String query, String branch, int page, int pages) { if (pages <= 1) return ""; StringBuilder h = new StringBuilder("<div class=\"pagination\">"); if (page > 1) h.append("<a href=\"").append(detailUrl(range, dataset, query, branch)).append("&amp;page=").append(page - 1).append("\">上一页</a>"); h.append("<span>第 ").append(page).append(" / ").append(pages).append(" 页</span>"); if (page < pages) h.append("<a href=\"").append(detailUrl(range, dataset, query, branch)).append("&amp;page=").append(page + 1).append("\">下一页</a>"); return h.append("</div>").toString(); }
  private String page(String title, String body, boolean noIndex) { return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"robots\" content=\"" + (noIndex ? "noindex,nofollow" : "noindex") + "\"><title>" + e(title) + "</title><link rel=\"stylesheet\" href=\"/assets/style.css?v=" + e(version) + "\"><!--[if lt IE 9]><script src=\"/assets/html5shiv.js\"></script><![endif]--></head><body>" + body + "<footer><span>智慧信管</span><span>V" + e(version) + "</span></footer></body></html>"; }
  private String simpleHeader() { return "<div class=\"simple-header\"><div class=\"simple-inner clearfix\"><a class=\"simple-brand\" href=\"/\"><span>信</span><strong>智慧信管</strong></a><div class=\"simple-version\">V" + e(version) + "</div></div></div>"; }
  private static String detailUrl(RangeSelection range, String dataset, String query, String branch) { return "/details?" + hrefQuery(range.queryString()) + "&amp;dataset=" + u(dataset) + "&amp;q=" + u(query) + "&amp;branch=" + u(branch); }
  private static String exportUrl(RangeSelection range, String dataset, String query, String branch) { return "/export?" + hrefQuery(range.queryString()) + "&amp;dataset=" + u(dataset) + "&amp;q=" + u(query) + "&amp;branch=" + u(branch); }
  private static String hrefQuery(String query) { return query.replace("&", "&amp;"); }
  private static String rangeHidden(RangeSelection r) { return hidden("scope", r.scope) + hidden("month", r.month) + hidden("year", r.year) + hidden("quarter", Integer.toString(r.quarter)) + hidden("start", r.start) + hidden("end", r.end); }
  private static String scopeButton(String value, String label, String selected) { return "<button class=\"range-tab" + (value.equals(selected) ? " active" : "") + "\" type=\"submit\" name=\"scopeMode\" value=\"" + e(value) + "\"" + (value.equals(selected) ? " aria-current=\"true\"" : "") + ">" + e(label) + "</button>"; }
  private static String hidden(String name, String value) { return "<input type=\"hidden\" name=\"" + e(name) + "\" value=\"" + e(value) + "\">"; }
  private static String option(String value, String label, String selected) { return "<option value=\"" + e(value) + "\"" + (value.equals(selected) ? " selected=\"selected\"" : "") + ">" + e(label) + "</option>"; }
  private static boolean editable(String dataset, int c) { return "negative".equals(dataset) ? c >= 10 && c <= 11 : c >= 17 && c <= 21; }
  static String e(String text) { if (text == null) return ""; return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
  static String u(String text) { return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8); }
  private static String formatDate(String value) { if (value == null || value.isEmpty()) return "尚未更新"; try { return DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(Instant.parse(value)); } catch (Exception ignored) { return value; } }
  private static String formatTime(String value) { if (value == null || value.isEmpty()) return "尚未更新"; try { return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value)); } catch (Exception ignored) { return value; } }
}

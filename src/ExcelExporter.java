import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.util.CellRangeAddressList;

final class ExcelExporter {
  byte[] export(String dataset, String title, List<RowRef> rows) throws Exception {
    boolean negative = "negative".equals(dataset);
    String[] headers = negative
        ? new String[]{"序号","预警简述","企业名称","变动级别","变动详情","支行","客户经理","贷款余额（万元）","信用等级","情况反馈","预测对企业近6个月的还款能力是否产生实质性影响","如有风险，请说明管控措施","时间顺序"}
        : new String[]{"序号","支行","客户全称","客户编码","贷款余额（万元）","信用等级","企业规模","所属行业","2024年销售收入","2025年销售收入","2024年净利润","2025年净利润","2024年融资总额","2025年融资总额","2024年我行融资","2025年我行融资","多重预警信息","未来6个月内是否存在违约风险","后续管控分类","具体管控目标及措施","本年度融资策略","备注"};
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet(negative ? "负面闭环" : "多重预警");
      sheet.createFreezePane(negative ? 3 : 4, 1);
      sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, headers.length - 1));
      CellStyle header = headerStyle(workbook);
      CellStyle body = bodyStyle(workbook);
      CellStyle editable = editableStyle(workbook);
      Row top = sheet.createRow(0); top.setHeightInPoints(32);
      for (int c = 0; c < headers.length; c++) { Cell cell = top.createCell(c); cell.setCellValue(headers[c]); cell.setCellStyle(header); }
      for (int r = 0; r < rows.size(); r++) {
        Row row = sheet.createRow(r + 1); row.setHeightInPoints(25);
        List<String> values = rows.get(r).values;
        for (int c = 0; c < headers.length; c++) {
          Cell cell = row.createCell(c); cell.setCellValue(DashboardData.cell(values, c));
          cell.setCellStyle(isEditable(dataset, c) ? editable : body);
        }
      }
      for (int c = 0; c < headers.length; c++) {
        int width = c == 0 ? 8 : (c == 2 ? 26 : (headers[c].length() > 15 ? 36 : 18));
        sheet.setColumnWidth(c, Math.min(width, 50) * 256);
      }
      if (!rows.isEmpty()) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        if (negative) addList(helper, sheet, 10, rows.size(), new String[]{"是","否"});
        else {
          addList(helper, sheet, 17, rows.size(), new String[]{"是","否"});
          addList(helper, sheet, 18, rows.size(), new String[]{"无需管控","日常一半管控","重点关注管控"});
          addList(helper, sheet, 20, rows.size(), new String[]{"增加","维持","压降","退出"});
        }
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream(); workbook.write(output); return output.toByteArray();
    }
  }

  private static void addList(DataValidationHelper helper, Sheet sheet, int column, int rowCount, String[] values) {
    DataValidationConstraint constraint = helper.createExplicitListConstraint(values);
    CellRangeAddressList addresses = new CellRangeAddressList(1, rowCount, column, column);
    DataValidation validation = helper.createValidation(constraint, addresses);
    validation.setShowErrorBox(true); validation.setErrorStyle(DataValidation.ErrorStyle.STOP); validation.createErrorBox("请选择有效值", "请从下拉列表中选择");
    sheet.addValidationData(validation);
  }
  private static boolean isEditable(String dataset, int column) { return "negative".equals(dataset) ? column >= 10 && column <= 11 : column >= 17 && column <= 21; }
  private static CellStyle headerStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle(); style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex()); style.setFillPattern(FillPatternType.SOLID_FOREGROUND); style.setAlignment(HorizontalAlignment.CENTER); style.setVerticalAlignment(VerticalAlignment.CENTER); style.setWrapText(true);
    Font font = workbook.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex()); style.setFont(font); borders(style); return style;
  }
  private static CellStyle bodyStyle(Workbook workbook) { CellStyle style = workbook.createCellStyle(); style.setVerticalAlignment(VerticalAlignment.TOP); style.setWrapText(true); borders(style); return style; }
  private static CellStyle editableStyle(Workbook workbook) { CellStyle style = bodyStyle(workbook); style.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex()); style.setFillPattern(FillPatternType.SOLID_FOREGROUND); return style; }
  private static void borders(CellStyle style) { style.setBorderBottom(BorderStyle.THIN); style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex()); }
}

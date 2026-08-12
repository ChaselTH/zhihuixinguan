import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

public final class FixtureBuilder {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Path output = Path.of(args[1]);
    Files.createDirectories(output);
    build(root.resolve("触发多重预警排查清单202607.et"), output.resolve("触发多重预警排查清单202608-1.et"), true);
    build(root.resolve("负面闭环202607-2.et"), output.resolve("负面闭环202608-1.et"), false);
  }

  private static void build(Path source, Path target, boolean multi) throws Exception {
    try (InputStream input = Files.newInputStream(source); Workbook workbook = WorkbookFactory.create(input)) {
      Sheet sheet = workbook.getSheetAt(0);
      if (multi) {
        put(sheet.createRow(2), new String[]{"1","城区支行","常州测试科技有限公司","C001","12500.50","AA","中型","制造业","32000","35000","1200","980","21000","23500","8000","9000","司法风险、征信逾期","是","重点关注管控","压降敞口并按月回访","审慎维持","联调测试数据"});
        put(sheet.createRow(3), new String[]{"2","武进支行","常州样例装备有限公司","C002","3600","A+","小型","装备制造","8000","9200","520","610","6200","5800","2600","2300","经营波动预警","否","日常一般管控","按季跟踪订单和回款","维持","联调测试数据"});
      } else {
        put(sheet.createRow(1), new String[]{"1","涉诉信息变动","常州测试科技有限公司","一级","新增被执行信息","城区支行","张经理","12500.50","AA","已联系企业核实并收集材料","是","暂停新增授信，持续跟踪司法进展","2026-08-15"});
        put(sheet.createRow(2), new String[]{"2","经营异常","常州样例装备有限公司","二级","主要客户订单下降","武进支行","李经理","3600","A+","企业已提交订单说明","否","保持日常跟踪","2026-08-20"});
      }
      try (OutputStream outputStream = Files.newOutputStream(target)) { workbook.write(outputStream); }
    }
  }
  private static void put(Row row, String[] values) { for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]); }
}

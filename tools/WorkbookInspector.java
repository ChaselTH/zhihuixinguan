import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;

public final class WorkbookInspector {
  public static void main(String[] args) throws Exception {
    DataFormatter formatter = new DataFormatter(Locale.CHINA);
    for (String arg : args) {
      Path path = Path.of(arg);
      System.out.println("FILE\t" + path.getFileName());
      try (InputStream input = Files.newInputStream(path);
           Workbook workbook = WorkbookFactory.create(input)) {
        System.out.println("SHEETS\t" + workbook.getNumberOfSheets());
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
          Sheet sheet = workbook.getSheetAt(s);
          System.out.println("SHEET\t" + s + "\t" + sheet.getSheetName()
              + "\tlastRow=" + sheet.getLastRowNum()
              + "\tmerged=" + sheet.getNumMergedRegions());
          for (int m = 0; m < sheet.getNumMergedRegions(); m++) {
            CellRangeAddress r = sheet.getMergedRegion(m);
            System.out.println("MERGE\t" + r.formatAsString());
          }
          int maxRow = Math.min(sheet.getLastRowNum(), 24);
          for (int r = 0; r <= maxRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int maxCell = Math.min(Math.max(row.getLastCellNum(), 0), 80);
            StringBuilder line = new StringBuilder("ROW\t").append(r + 1);
            boolean has = false;
            for (int c = 0; c < maxCell; c++) {
              Cell cell = row.getCell(c);
              String value = cell == null ? "" : formatter.formatCellValue(cell);
              if (!value.isEmpty()) has = true;
              line.append("\t").append(c + 1).append('=').append(value.replace("\t", " ").replace("\r", " ").replace("\n", " / "));
            }
            if (has) System.out.println(line);
          }
        }
      }
    }
  }
}

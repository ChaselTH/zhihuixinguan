import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;

/** Read-only inspection: headers/styles only; never prints business example values. */
public final class TemplateInspector {
  public static void main(String[] args) throws Exception {
    System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
    try (InputStream in = Files.newInputStream(Path.of(args[0])); Workbook wb = WorkbookFactory.create(in)) {
      DataFormatter f = new DataFormatter(Locale.CHINA);
      for (Sheet s : wb) {
        int headers = s.getNumMergedRegions() == 0 ? 1 : 2;
        System.out.println("SHEET " + s.getSheetName() + " headerRows=" + headers);
        int width = Math.max(s.getRow(0).getLastCellNum(), headers == 2 ? s.getRow(1).getLastCellNum() : 0);
        for (int c=0;c<width;c++) {
          StringBuilder text = new StringBuilder(CellReference.convertNumToColString(c));
          for(int r=0;r<=headers;r++) {
            Cell cell=s.getRow(r)==null?null:s.getRow(r).getCell(c);
            if(cell==null) continue;
            CellStyle st=cell.getCellStyle();
            Color color=st.getFillForegroundColorColor();
            String rgb=color instanceof org.apache.poi.hssf.util.HSSFColor ? ((org.apache.poi.hssf.util.HSSFColor)color).getHexString() : String.valueOf(color);
            text.append(" | r").append(r+1).append(" fill=").append(st.getFillForegroundColor()).append(" rgb=").append(rgb).append(" pattern=").append(st.getFillPattern());
            if(r<headers) text.append(" text=").append(f.formatCellValue(cell).replace('\n',' '));
            else {String hint=f.formatCellValue(cell);if(hint.length()<=6&&(hint.contains("填写")||hint.contains("填报")))text.append(" templateHint=").append(hint);}
          }
          System.out.println(text);
        }
        for(int r=headers;r<=Math.min(s.getLastRowNum(),headers+4);r++){Row row=s.getRow(r);if(row==null)continue;StringBuilder shape=new StringBuilder("BODY_SHAPE r"+(r+1));for(Cell cell:row){String v=f.formatCellValue(cell).strip();if(!v.isEmpty())shape.append(" ").append(CellReference.convertNumToColString(cell.getColumnIndex())).append(":len=").append(v.length()).append(":example=").append(v.contains("示例")).append(":instruction=").append(v.contains("填写")||v.contains("填报")||v.contains("必填")||v.contains("格式"));}System.out.println(shape);}
      }
    }
  }
}

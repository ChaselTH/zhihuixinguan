import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import xinguan.platform.*;

final class ExcelExporter {
  byte[] export(String dataset,String title,List<RowRef> refs)throws Exception{List<List<String>> rows=new ArrayList<>();for(RowRef ref:refs)rows.add(ref.values);return workbook(dataset,rows);}
  byte[] template(String dataset)throws Exception{return workbook(dataset,List.of());}
  private byte[] workbook(String dataset,List<List<String>> rows)throws Exception{
    DatasetSchema schema=DatasetSchema.get(dataset);
    try(Workbook wb=new XSSFWorkbook()){
      Sheet sheet=wb.createSheet(schema.label);CellStyle normal=wb.createCellStyle();normal.setWrapText(true);normal.setVerticalAlignment(VerticalAlignment.TOP);
      CellStyle header=wb.createCellStyle();header.cloneStyleFrom(normal);header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      Font font=wb.createFont();font.setBold(true);header.setFont(font);
      CellStyle yellow=wb.createCellStyle();yellow.cloneStyleFrom(normal);yellow.setFillForegroundColor(IndexedColors.YELLOW.getIndex());yellow.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      CellStyle yellowHeader=wb.createCellStyle();yellowHeader.cloneStyleFrom(yellow);yellowHeader.setFont(font);
      for(int r=0;r<schema.headerRows;r++)sheet.createRow(r).setHeightInPoints(r==0?48:30);
      for(int c=0;c<schema.width();c++){
        boolean grouped="multi".equals(dataset)&&c>=8&&c<=15||"cross".equals(dataset)&&c>=6&&c<=9;
        String title=schema.fields.get(c).title();Cell top=sheet.getRow(0).createCell(c);top.setCellStyle(schema.editable(c)?yellowHeader:header);
        if(schema.headerRows==2){Cell lower=sheet.getRow(1).createCell(c);lower.setCellStyle(schema.editable(c)?yellowHeader:header);
          if(grouped)lower.setCellValue("cross".equals(dataset)?(c%2==0?"关注":"不良"):title);
          else{top.setCellValue(title);sheet.addMergedRegion(new CellRangeAddress(0,1,c,c));}
        }else top.setCellValue(title);
        sheet.setColumnWidth(c,Math.min(c==0?8:title.length()>20?48:title.length()>10?30:20,60)*256);
      }
      if("multi".equals(dataset)){group(sheet,8,11,"销售与利润情况（万元）");group(sheet,12,15,"融资情况（万元）");}
      if("cross".equals(dataset)){group(sheet,6,7,"工行违约情况");group(sheet,8,9,"他行违约情况");}
      for(int r=0;r<rows.size();r++){Row row=sheet.createRow(schema.headerRows+r);row.setHeightInPoints(40);for(int c=0;c<schema.width();c++){Cell cell=row.createCell(c);cell.setCellValue(schema.value(rows.get(r),c));cell.setCellStyle(schema.editable(c)?yellow:normal);}}
      sheet.createFreezePane(schema.customerColumn+1,schema.headerRows);int max=Math.max(schema.headerRows,rows.size()+schema.headerRows-1);
      for(int c=0;c<schema.width();c++)if(!schema.fields.get(c).options().isEmpty()){
        DataValidationHelper helper=sheet.getDataValidationHelper();DataValidation validation=helper.createValidation(helper.createExplicitListConstraint(schema.fields.get(c).options().toArray(new String[0])),new CellRangeAddressList(schema.headerRows,Math.max(max,1000),c,c));validation.setShowErrorBox(true);sheet.addValidationData(validation);
      }
      ByteArrayOutputStream out=new ByteArrayOutputStream();wb.write(out);return out.toByteArray();
    }
  }
  private static void group(Sheet s,int from,int to,String label){s.getRow(0).getCell(from).setCellValue(label);s.addMergedRegion(new CellRangeAddress(0,0,from,to));}
}

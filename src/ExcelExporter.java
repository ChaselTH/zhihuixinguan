import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import xinguan.platform.*;

final class ExcelExporter {
  byte[] export(String dataset,String title,List<RowRef> refs)throws Exception{List<List<String>> rows=new ArrayList<>();for(RowRef ref:refs)rows.add(ref.values);return workbook(dataset,rows,false);}
  byte[] template(String dataset)throws Exception{return workbook(dataset,List.of(),true);}
  private byte[] workbook(String dataset,List<List<String>> rows,boolean template)throws Exception{
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
      if(template){
        Sheet guide=wb.createSheet("模板说明");String[] notes={schema.label+" · 模板 v1（2026-09 表头）","请勿改动数据工作表表头、列顺序及合并单元格。黄色列是支行填报列，任意黄色格非空即为已填写。","客户编码必须按文本保存，保留前导零。请勿将长编码存为数值后再转文本。","机构：武进、金坛、溧阳、新区、经开、天宁、钟楼、营业部、中吴。",schema.periodColumn<0?"本表没有来源期次列，请在上传页面指定所属月份或期次；首次违约时间不是数据月份。":"时间顺序按表内各行确定；缺失时使用上传页面补充期次或所属月份。","下拉字段只允许模板选项。日期期次示例：20260901-20260915。","一次可上传 1～10 个同格式文件；单文件 20 MB、整批 50 MB／20000 条。任何文件错误则整批不导入。","同一来源重复时默认保留已有非空填写、仅补空白。选择覆盖会把上传空白也覆盖为清空。","工作表公式使用已保存的缓存值，不联网计算；有错误或无有效缓存时先重算并保存。","本页是说明，不会作为业务数据导入。"};
        guide.setColumnWidth(0,100*256);guide.setDisplayGridlines(false);for(int i=0;i<notes.length;i++){Row row=guide.createRow(i);row.setHeightInPoints(i==0?28:42);Cell cell=row.createCell(0);cell.setCellValue(notes[i]);cell.setCellStyle(i==0?header:normal);}
      }
      ByteArrayOutputStream out=new ByteArrayOutputStream();wb.write(out);return out.toByteArray();
    }
  }
  private static void group(Sheet s,int from,int to,String label){s.getRow(0).getCell(from).setCellValue(label);s.addMergedRegion(new CellRangeAddress(0,0,from,to));}
}

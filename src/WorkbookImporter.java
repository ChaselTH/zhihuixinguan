import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import xinguan.platform.*;

final class WorkbookImporter {
  record Parsed(List<BusinessRecord> rows,int skippedExamples) {}
  Parsed read(Path path,String filename,String month,String periodOverride,String dataset)throws WorkbookImportException {
    DatasetSchema schema=DatasetSchema.get(dataset);
    if(!filename.toLowerCase(Locale.ROOT).matches(".*\\.(et|xls|xlsx)$"))throw new WorkbookImportException("仅支持 .et、.xls、.xlsx");
    try(InputStream in=Files.newInputStream(path);Workbook workbook=WorkbookFactory.create(in)) {
      DataFormatter formatter=new DataFormatter(Locale.CHINA);Sheet selected=null;int start=0;
      for(Sheet sheet:workbook){int candidate=findHeader(sheet,schema,formatter);if(candidate>=0){if(selected!=null)throw new WorkbookImportException("文件中有多张匹配工作表，请只保留一张 "+schema.label);selected=sheet;start=candidate;}}
      if(selected==null)throw new WorkbookImportException("与“"+schema.label+"”入口不匹配，或表头缺失／顺序错误；请下载该入口模板");
      List<BusinessRecord> result=new ArrayList<>();int skipped=0;
      for(int r=start+schema.headerRows;r<=selected.getLastRowNum();r++) {
        Row row=selected.getRow(r);if(row==null)continue;List<String> values=new ArrayList<>();boolean any=false;
        for(int c=0;c<schema.width();c++){String v=value(row.getCell(c),formatter);values.add(v);any|=!v.isBlank();}
        if(!any)continue;
        if(values.get(0).contains("示例")||values.get(0).startsWith("说明")||values.get(0).startsWith("注：")||templateInstruction(schema,values)){skipped++;continue;}
        try{
          if(values.get(schema.customerColumn).isBlank()&&values.get(schema.codeColumn).isBlank())throw new IllegalArgumentException("缺少客户名称和客户编码，不能识别该记录");
          for(int c=schema.width();c<row.getLastCellNum();c++)if(!value(row.getCell(c),formatter).isBlank())throw new IllegalArgumentException("存在模板以外的非空列");
          String org=Organizations.resolve(values.get(schema.branchColumn));values.set(schema.branchColumn,Organizations.label(org));
          String periodValue=schema.value(values,schema.periodColumn);if(periodValue.isBlank())periodValue=periodOverride;
          if((periodValue==null||periodValue.isBlank())&&(month==null||month.isBlank()))periodValue=filenamePeriod(filename);
          String resolvedMonth=month==null||month.isBlank()?filenameMonth(filename):month;
          xinguan.platform.Period p=xinguan.platform.Period.parse(periodValue,resolvedMonth);
          if(schema.periodColumn>=0)values.set(schema.periodColumn,p.key());
          for(int c=0;c<schema.width();c++)if(schema.editable(c))schema.validateEdit(c,values.get(c));
          String now=Instant.now().toString();result.add(new BusinessRecord("",0,dataset,p,org,values,sanitize(filename),now,"",Map.of()));
        }catch(RuntimeException e){throw new WorkbookImportException("文件“"+sanitize(filename)+"” / "+selected.getSheetName()+" / 第 "+(r+1)+" 行："+e.getMessage());}
        if(result.size()>20000)throw new WorkbookImportException("单次表格最多 20000 条，请分批导入");
      }
      return new Parsed(List.copyOf(result),skipped);
    }catch(WorkbookImportException e){throw e;}catch(Exception e){throw new WorkbookImportException("文件无法读取，请确认未加密、未损坏且格式正确",e);}
  }
  private int findHeader(Sheet s,DatasetSchema schema,DataFormatter f) {
    for(int r=0;r<=Math.min(s.getLastRowNum(),8);r++){
      if(!"序号".equals(value(s.getRow(r)==null?null:s.getRow(r).getCell(0),f)))continue;
      boolean matches=true;
      for(int c=0;c<schema.width();c++)if(!normal(header(s,r,schema.headerRows,c,f)).equals(normal(schema.fields.get(c).title()))){matches=false;break;}
      for(int hr=r;matches&&hr<r+schema.headerRows;hr++){Row row=s.getRow(hr);if(row!=null)for(int c=schema.width();c<row.getLastCellNum();c++)if(!value(row.getCell(c),f).isBlank()){matches=false;break;}}
      if(matches)return r;
    }return -1;
  }
  private static boolean templateInstruction(DatasetSchema s,List<String> row){
    boolean hint=false;
    for(int c=0;c<s.width();c++){String v=row.get(c);if(v.isBlank()||c==s.periodColumn)continue;if(!s.editable(c))return false;if(v.equals("支行填写")){hint=true;continue;}if(!s.fields.get(c).options().contains(v))return false;}
    return hint;
  }
  private String header(Sheet s,int row,int count,int col,DataFormatter f){
    List<String> parts=new ArrayList<>();
    for(int r=row;r<row+count;r++){
      int rr=r,cc=col;for(CellRangeAddress m:s.getMergedRegions())if(m.isInRange(r,col)){rr=m.getFirstRow();cc=m.getFirstColumn();break;}
      String v=value(s.getRow(rr)==null?null:s.getRow(rr).getCell(cc),f);if(!v.isBlank()&&!parts.contains(v))parts.add(v);
    }
    if(parts.size()>1&&(parts.get(0).contains("销售与利润")||parts.get(0).contains("融资情况")))return parts.get(parts.size()-1);
    return String.join(" / ",parts);
  }
  private static String normal(String s){return s.replaceAll("[\\s　]","").replace('（','(').replace('）',')').replace('，',',');}
  private static String value(Cell c,DataFormatter f){
    if(c==null||c.getCellType()==CellType.BLANK)return "";
    if(c.getCellType()==CellType.FORMULA){return switch(c.getCachedFormulaResultType()){case STRING->c.getStringCellValue().strip();case NUMERIC->f.formatRawCellContents(c.getNumericCellValue(),c.getCellStyle().getDataFormat(),c.getCellStyle().getDataFormatString());case BOOLEAN->Boolean.toString(c.getBooleanCellValue());default->throw new IllegalArgumentException("公式没有有效缓存结果，请在表格软件中重算并保存");};}
    return f.formatCellValue(c).strip();
  }
  private static String filenameMonth(String name){Matcher m=Pattern.compile("(20\\d{2})[-_年]?(0[1-9]|1[0-2])").matcher(name);return m.find()?m.group(1)+"-"+m.group(2):"";}
  private static String filenamePeriod(String name){Matcher m=Pattern.compile("20\\d{2}[-.]?\\d{2}[-.]?\\d{2}\\s*[-~～至到_]\\s*20\\d{2}[-.]?\\d{2}[-.]?\\d{2}").matcher(name);return m.find()?m.group():"";}
  static String sanitize(String name){String s=name.replace('\\','/');s=s.substring(s.lastIndexOf('/')+1).replaceAll("[\\r\\n\\t]"," ");return s.length()>180?s.substring(s.length()-180):s;}
}

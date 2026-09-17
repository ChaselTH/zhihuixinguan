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
  record Issue(String filename,String sheet,int row,String column,String message) {}
  record Report(List<ImportPlatform.SourceRow> sources,int skippedExamples,List<Issue> errors) {
    Report {sources=List.copyOf(sources);errors=List.copyOf(errors);}
  }
  Parsed read(Path path,String filename,String month,String periodOverride,String dataset)throws WorkbookImportException {
    try{Report report=inspect(Files.readAllBytes(path),filename,month,periodOverride,dataset);if(!report.errors().isEmpty()){Issue i=report.errors().get(0);throw new WorkbookImportException(i.filename()+" / "+i.sheet()+" / 第 "+i.row()+" 行 / "+i.column()+"："+i.message());}return new Parsed(report.sources().stream().map(ImportPlatform.SourceRow::record).toList(),report.skippedExamples());}
    catch(IOException e){throw new WorkbookImportException("文件无法读取",e);}
  }
  Report inspect(byte[] bytes,String filename,String month,String periodOverride,String dataset) {
    DatasetSchema schema=DatasetSchema.get(dataset);
    filename=sanitize(filename);List<Issue> errors=new ArrayList<>();List<ImportPlatform.SourceRow> result=new ArrayList<>();int skipped=0;
    if(!filename.toLowerCase(Locale.ROOT).matches(".*\\.(et|xls|xlsx)$"))return new Report(List.of(),0,List.of(new Issue(filename,"",0,"","仅支持 .et、.xls、.xlsx")));
    String contextConflict=dataset.equals("cross")?"":contextConflict(month,periodOverride);if(!contextConflict.isEmpty())return new Report(List.of(),0,List.of(new Issue(filename,"",0,"上传期次／月份",contextConflict)));
    String suppliedMonth=cleanMonth(month),fileMonth=filenameMonth(filename),filePeriod=filenamePeriod(filename);
    try(InputStream in=new ByteArrayInputStream(bytes);Workbook workbook=WorkbookFactory.create(in)) {
      DataFormatter formatter=new DataFormatter(Locale.CHINA);Sheet selected=null;int start=0;
      for(Sheet sheet:workbook){int candidate=findHeader(sheet,schema,formatter);if(candidate>=0){if(selected!=null)throw new WorkbookImportException("文件中有多张匹配工作表，请只保留一张 "+schema.label);selected=sheet;start=candidate;}}
      if(selected==null){headerErrors(workbook,schema,formatter,filename,errors);return new Report(List.of(),0,errors);}
      if(selected.getLastRowNum()>20020)throw new WorkbookImportException("工作表超过 20000 行，请删除尾部多余行或分批上传");
      long characters=0;for(int r=start+schema.headerRows;r<=selected.getLastRowNum();r++) {
        Row row=selected.getRow(r);if(row==null)continue;List<String> values=new ArrayList<>();boolean any=false;int beforeErrors=errors.size();
        for(int c=0;c<schema.width();c++){
          try{Cell cell=row.getCell(c);String v=value(cell,formatter);if(v.length()>10000)throw new IllegalArgumentException("单元格不能超过 10000 字");if(c==schema.codeColumn&&numeric(cell)&&Math.abs(cell.getNumericCellValue())>=1e15)throw new IllegalArgumentException("长客户编码必须使用文本，数值格式可能已丢失精度，请核对原始编码");values.add(v);any|=!v.isBlank();}
          catch(RuntimeException e){values.add("");errors.add(issue(filename,selected,r,c,e.getMessage()));}
        }
        if(errors.size()!=beforeErrors){if(errors.size()>=100)break;continue;}
        for(String v:values)characters+=v.length();if(characters>8_000_000)throw new WorkbookImportException("单元格文字合计超过 800 万字，请分批上传");
        if(!any)continue;
        if(values.get(0).contains("示例")||values.get(0).startsWith("说明")||values.get(0).startsWith("注：")||templateInstruction(schema,values)){skipped++;continue;}
        int column=schema.customerColumn;
        try{
          if(values.get(schema.customerColumn).isBlank()&&values.get(schema.codeColumn).isBlank())throw new IllegalArgumentException("缺少客户名称和客户编码，不能识别该记录");
          for(int c=schema.width();c<row.getLastCellNum();c++){column=c;if(!value(row.getCell(c),formatter).isBlank())throw new IllegalArgumentException("存在模板以外的非空列");}
          column=schema.branchColumn;
          String org=Organizations.resolve(values.get(schema.branchColumn));values.set(schema.branchColumn,Organizations.label(org));
          column=schema.periodColumn;
          xinguan.platform.Period p;
          if(dataset.equals("cross")){
            column=11;String date=firstDefaultDate(row.getCell(11),values.get(11),workbook);
            values.set(11,date);p=xinguan.platform.Period.parse(date.substring(0,7),"");
          }else{
          String periodValue=schema.value(values,schema.periodColumn);if(periodValue.isBlank()){
            if(periodOverride!=null&&!periodOverride.isBlank()){
              String overrideMonth=periodMonth(periodOverride);if(!overrideMonth.isEmpty()&&!fileMonth.isEmpty()&&!overrideMonth.equals(fileMonth))throw new IllegalArgumentException("补充期次与文件名月份冲突，请按文件分别补充期次或清空公共期次");
              periodValue=periodOverride;
            }else if(!filePeriod.isEmpty()){
              if(!suppliedMonth.isEmpty()&&!suppliedMonth.equals(fileMonth))throw new IllegalArgumentException("公共月份与文件名月份冲突，请按文件分别指定月份；本批未导入");
              periodValue=filePeriod;
            }else if(!suppliedMonth.isEmpty()&&!fileMonth.isEmpty()&&!suppliedMonth.equals(fileMonth))throw new IllegalArgumentException("公共月份与文件名月份冲突，请按文件分别指定月份；本批未导入");
          }
          String resolvedMonth=suppliedMonth.isEmpty()?fileMonth:suppliedMonth;
          p=xinguan.platform.Period.parse(periodValue,resolvedMonth);
          }
          if(schema.periodColumn>=0)values.set(schema.periodColumn,p.key());
          for(int c=0;c<schema.width();c++)if(schema.editable(c)){column=c;schema.validateEdit(c,values.get(c));}
          String now=Instant.now().toString();result.add(new ImportPlatform.SourceRow(new BusinessRecord("",0,dataset,p,org,values,filename,now,"",Map.of()),selected.getSheetName(),r+1));
        }catch(RuntimeException e){errors.add(issue(filename,selected,r,column,e.getMessage()));}
        if(errors.size()>=100)break;
      }
    }catch(WorkbookImportException e){errors.add(new Issue(filename,"",0,"",e.getMessage()));}catch(Exception e){errors.add(new Issue(filename,"",0,"","文件无法读取，请确认未加密、未损坏且格式正确"));}
    return new Report(errors.isEmpty()?result:List.of(),skipped,errors.subList(0,Math.min(errors.size(),100)));
  }
  /** Parse one workbook against every supported data sheet; errors in any sheet reject the whole batch. */
  Report inspectBundle(byte[] bytes,String filename,String month,String periodOverride) {
    String contextConflict=contextConflict(month,periodOverride);if(!contextConflict.isEmpty())return new Report(List.of(),0,List.of(new Issue(sanitize(filename),"",0,"上传期次／月份",contextConflict)));
    List<ImportPlatform.SourceRow> sources=new ArrayList<>();List<Issue> errors=new ArrayList<>();int skipped=0;
    for(DatasetSchema schema:DatasetSchema.all()){
      Report report=inspect(bytes,filename,month,periodOverride,schema.id);sources.addAll(report.sources());skipped+=report.skippedExamples();errors.addAll(report.errors());
    }
    return new Report(errors.isEmpty()?sources:List.of(),skipped,errors.subList(0,Math.min(errors.size(),100)));
  }
  private static Issue issue(String file,Sheet sheet,int row,int col,String message){return new Issue(file,sheet.getSheetName(),row+1,col<0?"上传期次／月份":CellReference.convertNumToColString(col),message);}
  private void headerErrors(Workbook workbook,DatasetSchema schema,DataFormatter f,String filename,List<Issue> errors){
    Sheet best=null;int at=0,score=-1;
    for(Sheet sheet:workbook)for(int r=0;r<=Math.min(sheet.getLastRowNum(),8);r++){
      if(!"序号".equals(value(sheet.getRow(r)==null?null:sheet.getRow(r).getCell(0),f)))continue;
      int matches=0;for(int c=0;c<schema.width();c++)if(normal(header(sheet,r,schema.headerRows,c,f)).equals(normal(schema.fields.get(c).title())))matches++;
      if(matches>score){score=matches;best=sheet;at=r;}
    }
    if(best==null){errors.add(new Issue(filename,"",0,"表头","未找到“序号”开头的表头；请使用当前入口的空白模板"));return;}
    for(int c=0;c<schema.width();c++){String actual=header(best,at,schema.headerRows,c,f),expected=schema.fields.get(c).title();if(!normal(actual).equals(normal(expected)))errors.add(issue(filename,best,at,c,"表头缺失、重复或顺序错误。应为“"+expected+"”，实际为“"+actual+"”"));}
    if(errors.isEmpty())errors.add(issue(filename,best,at,schema.width(),"表头存在模板以外的非空列，请核对入口和模板"));
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
  private static boolean numeric(Cell c){return c!=null&&(c.getCellType()==CellType.NUMERIC||(c.getCellType()==CellType.FORMULA&&c.getCachedFormulaResultType()==CellType.NUMERIC));}
  private static String value(Cell c,DataFormatter f){
    if(c==null||c.getCellType()==CellType.BLANK)return "";
    if(c.getCellType()==CellType.ERROR)throw new IllegalArgumentException("单元格包含表格错误，请修正后重传");
    if(c.getCellType()==CellType.FORMULA){
      if(c instanceof org.apache.poi.xssf.usermodel.XSSFCell xc&&(xc.getRawValue()==null||(xc.getRawValue().isEmpty()&&c.getCachedFormulaResultType()!=CellType.STRING)))throw new IllegalArgumentException("公式没有有效缓存结果，请在表格软件中重算并保存");
      return switch(c.getCachedFormulaResultType()){case STRING->c.getStringCellValue().strip();case NUMERIC->f.formatRawCellContents(c.getNumericCellValue(),c.getCellStyle().getDataFormat(),c.getCellStyle().getDataFormatString());case BOOLEAN->Boolean.toString(c.getBooleanCellValue());default->throw new IllegalArgumentException("公式没有有效缓存结果，请在表格软件中重算并保存");};}
    return f.formatCellValue(c).strip();
  }
  private static String firstDefaultDate(Cell cell,String value,Workbook workbook){
    try{
      LocalDate date;
      if(numeric(cell)&&DateUtil.isCellDateFormatted(cell)){
        double serial=cell.getNumericCellValue();if(!DateUtil.isValidExcelDate(serial))throw new IllegalArgumentException();
        boolean window1904=workbook instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook x?x.isDate1904():workbook instanceof org.apache.poi.hssf.usermodel.HSSFWorkbook h&&h.getInternalWorkbook().isUsing1904DateWindowing();
        date=DateUtil.getLocalDateTime(serial,window1904).toLocalDate();
      }else{
        String text=value.strip();Matcher m=Pattern.compile("^(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})(?:日)?(?:[ T]\\d{1,2}:\\d{2}(?::\\d{2})?)?$").matcher(text);
        if(m.matches())date=LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)));
        else if(text.matches("20\\d{6}"))date=LocalDate.parse(text,java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        else throw new IllegalArgumentException();
      }
      if(date.getYear()<2000||date.getYear()>2099)throw new IllegalArgumentException();
      return date.toString();
    }catch(RuntimeException e){throw new IllegalArgumentException("违约首次出现时间必填且必须是有效日期，例如 2026-09-17；将按该日期所属月份归档");}
  }
  private static String filenameMonth(String name){Matcher m=Pattern.compile("(20\\d{2})[-_年]?(0[1-9]|1[0-2])").matcher(name);return m.find()?m.group(1)+"-"+m.group(2):"";}
  private static String filenamePeriod(String name){Matcher m=Pattern.compile("20\\d{2}[-.]?\\d{2}[-.]?\\d{2}\\s*[-~～至到_]\\s*20\\d{2}[-.]?\\d{2}[-.]?\\d{2}").matcher(name);return m.find()?m.group():"";}
  private static String cleanMonth(String value){return value==null?"":value.strip();}
  private static String periodMonth(String value){
    String text=cleanMonth(value);Matcher range=Pattern.compile("(20\\d{2})[-./]?(\\d{2})[-./]?\\d{2}\\s*[-~～至到_]\\s*20\\d{2}[-./]?\\d{2}[-./]?\\d{2}").matcher(text);
    if(range.matches())return range.group(1)+"-"+range.group(2);
    return text.matches("20\\d{2}-(0[1-9]|1[0-2])(?:-P[12])?")?text.substring(0,7):"";
  }
  private static String contextConflict(String month,String periodOverride){
    String m=cleanMonth(month),p=periodMonth(periodOverride);if(!m.isEmpty()&&!p.isEmpty()&&!m.equals(p))return "所属月份与补充期次冲突，请只保留一个一致的公共期次或按文件／行补充";return "";
  }
  static String sanitize(String name){String s=name.replace('\\','/');s=s.substring(s.lastIndexOf('/')+1).replaceAll("[\\r\\n\\t]"," ");return s.length()>180?s.substring(s.length()-180):s;}
}

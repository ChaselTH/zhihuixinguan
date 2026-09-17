import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import xinguan.platform.*;

/** Generated workbook fixtures only; no real customer spreadsheet is read. */
public final class ImportWorkbookTest {
  static int assertions;
  public static void main(String[] args)throws Exception {
    WorkbookImporter reader=new WorkbookImporter();
    for(var schema:DatasetSchema.all()){
      byte[] template=new ExcelExporter().template(schema.id);
      try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(template))){
        check(wb.getSheetAt(0).getSheetName().equals(schema.label)&&wb.getSheet("模板说明")!=null,"original format plus versioned guide");
        check(wb.getSheet("模板说明").getRow(0).getCell(0).getStringCellValue().contains("v1"),"guide template version");
        for(int c=0;c<schema.width();c++)check(wb.getSheetAt(0).getRow(0).getCell(c).getCellStyle().getFillForegroundColor()==(schema.editable(c)?IndexedColors.YELLOW.getIndex():IndexedColors.GREY_25_PERCENT.getIndex()),"yellow schema unchanged");
        check(wb.getSheetAt(0).getPaneInformation()!=null,"template freeze pane preserved");
        check(!wb.getSheetAt(0).getDataValidations().isEmpty(),"template dropdown constraints preserved");
      }
      check(reader.inspect(template,"blank.xlsx","2026-09","",schema.id).sources().isEmpty(),"guide not imported as rows");
      byte[] valid=book(schema.id,wb->{var row=wb.getSheetAt(0).getRow(schema.headerRows);row.getCell(schema.codeColumn).setCellValue("000001234567890123");});
      var report=reader.inspect(valid,"C:\\private\\synthetic.et","2026-09","",schema.id);
      check(report.errors().isEmpty()&&report.sources().size()==1,"three entries detect workbook independent of extension");
      var source=report.sources().get(0);check(source.record().values().get(schema.codeColumn).equals("000001234567890123")&&source.record().filename().equals("synthetic.et"),"leading zeros and sanitized filename");
      check(source.row()==schema.headerRows+1&&source.sheet().equals(schema.label),"one-based source location");
      var bad=reader.inspect(book(schema.id,wb->wb.getSheetAt(0).getRow(schema.headerRows).getCell(schema.branchColumn).setCellValue("未知机构<script>")),"bad.xlsx","2026-09","",schema.id);
      check(bad.errors().get(0).column().equals(org.apache.poi.ss.util.CellReference.convertNumToColString(schema.branchColumn))&&bad.sources().isEmpty(),"invalid organization locates column and rejects batch");
      byte[] wrong=book(schema.id,wb->wb.getSheetAt(0).getRow(0).getCell(schema.codeColumn).setCellValue("重复企业名称"));
      var head=reader.inspect(wrong,"header.xlsx","2026-09","",schema.id);check(!head.errors().isEmpty()&&head.errors().get(0).message().contains("表头"),"wrong header detailed report");
      String option=schema.fields.stream().filter(f->!f.options().isEmpty()).findFirst().orElseThrow().key();int index=schema.index(option);
      var enumError=reader.inspect(book(schema.id,wb->wb.getSheetAt(0).getRow(schema.headerRows).getCell(index).setCellValue("未知选项")),"enum.xlsx","2026-09","",schema.id);
      check(enumError.errors().size()==1&&enumError.errors().get(0).column().equals(org.apache.poi.ss.util.CellReference.convertNumToColString(index)),"dropdown validation locates column");
      var extras=reader.inspect(book(schema.id,wb->wb.getSheetAt(0).getRow(schema.headerRows).createCell(schema.width()).setCellValue("extra")),"extra.xlsx","2026-09","",schema.id);check(extras.errors().get(0).message().contains("模板以外"),"unexpected source column rejected");
    }
    check(!reader.inspect(new byte[]{1,2,3},"corrupt.xlsx","2026-09","","multi").errors().isEmpty(),"corrupt file structured error");
    check(!reader.inspect(new byte[]{1},"bad.csv","2026-09","","multi").errors().isEmpty(),"unconfigured format rejected");
    var duplicate=reader.inspect(book("multi",wb->wb.cloneSheet(0)),"sheets.xlsx","2026-09","","multi");check(duplicate.errors().get(0).message().contains("多张"),"ambiguous sheets rejected");
    check(reader.inspect(book("cross",wb->wb.getSheetAt(0).getRow(2).getCell(11).setBlank()),"cross.xlsx","","","cross").errors().get(0).column().equals("L"),"missing first default date identified in column L");
    var errors=reader.inspect(book("multi",wb->{Sheet s=wb.getSheetAt(0);s.getRow(2).getCell(1).setCellValue("bad");Row r=s.createRow(3);var values=FoundationTest.candidate("multi","WUJIN","second").values();for(int i=0;i<values.size();i++)r.createCell(i).setCellValue(values.get(i));r.getCell(22).setCellValue("20260230-20260301");}),"several.xlsx","2026-09","","multi");
    check(errors.errors().size()==2&&errors.errors().get(0).row()==3&&errors.errors().get(1).row()==4,"multiple row errors collected without partial data");
    var mixedPeriods=reader.inspect(book("multi",wb->{Sheet s=wb.getSheetAt(0);Row first=s.getRow(2);first.getCell(22).setCellValue("20260901-20260915");Row second=s.createRow(3);var values=FoundationTest.candidate("multi","WUJIN","second-period").values();for(int i=0;i<values.size();i++)second.createCell(i).setCellValue(values.get(i));second.getCell(22).setCellValue("20260916-20260930");}),"mixed-periods.xlsx","","","multi");
    check(mixedPeriods.errors().isEmpty()&&mixedPeriods.sources().size()==2&&mixedPeriods.sources().get(0).record().period().key().equals("2026-09-01~2026-09-15")&&mixedPeriods.sources().get(1).record().period().key().equals("2026-09-16~2026-09-30"),"explicit row periods may coexist in one batch");
    check(reader.inspect(HttpSmokeTest.workbook("cross"),"2026-09-cross.xlsx","","","cross").errors().isEmpty(),"cross sheet uses its first default date without an upload month");
    var numeric=reader.inspect(book("multi",wb->{Cell c=wb.getSheetAt(0).getRow(2).getCell(3);c.setCellValue(123);CellStyle style=wb.createCellStyle();style.setDataFormat(wb.createDataFormat().getFormat("000000"));c.setCellStyle(style);}),"formatted.xlsx","2026-09","","multi");check(numeric.sources().get(0).record().values().get(3).equals("000123"),"numeric displayed leading zero format preserved");
    var longCode=reader.inspect(book("multi",wb->wb.getSheetAt(0).getRow(2).getCell(3).setCellValue(123456789012345678d)),"long.xlsx","2026-09","","multi");check(!longCode.errors().isEmpty(),"unsafe long numeric identifier rejected");
    var formulaCode=reader.inspect(book("multi",wb->{wb.getSheetAt(0).getRow(2).getCell(3).setCellFormula("123456789012345678");wb.getCreationHelper().createFormulaEvaluator().evaluateAll();}),"long-formula.xlsx","2026-09","","multi");check(!formulaCode.errors().isEmpty()&&formulaCode.errors().get(0).column().equals("D"),"long numeric formula identifier rejected like literal numeric identifier");
    var noCache=reader.inspect(book("multi",wb->{var cell=(org.apache.poi.xssf.usermodel.XSSFCell)wb.getSheetAt(0).getRow(2).getCell(8);cell.setCellFormula("1+2");cell.getCTCell().unsetV();}),"cache.xlsx","2026-09","","multi");check(!noCache.errors().isEmpty(),"formula without cache cannot silently become zero");
    var cached=reader.inspect(book("multi",wb->{wb.getSheetAt(0).getRow(2).getCell(8).setCellFormula("1+2");wb.getCreationHelper().createFormulaEvaluator().evaluateAll();}),"cached.xlsx","2026-09","","multi");check(cached.sources().get(0).record().values().get(8).equals("3"),"saved formula cache read without external evaluation");
    var formulaError=reader.inspect(book("multi",wb->{wb.getSheetAt(0).getRow(2).getCell(8).setCellFormula("1/0");wb.getCreationHelper().createFormulaEvaluator().evaluateAll();}),"error.xlsx","2026-09","","multi");check(!formulaError.errors().isEmpty()&&formulaError.errors().get(0).column().equals("I"),"formula error locates cell");
    var bounded=reader.inspect(book("multi",wb->{Sheet s=wb.getSheetAt(0);for(int i=2;i<110;i++){Row row=s.createRow(i);var v=FoundationTest.candidate("multi","WUJIN","errors"+i).values();for(int c=0;c<v.size();c++)row.createCell(c).setCellValue(v.get(c));row.getCell(1).setCellValue("unknown");}}),"bounded.xlsx","2026-09","","multi");check(bounded.errors().size()==100,"error output bound");
    byte[] bundle=new ExcelExporter().templateBundle();try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(bundle))){check(wb.getNumberOfSheets()==4&&wb.getSheet("模板说明")!=null,"unified template contains three data sheets and guide");for(var schema:DatasetSchema.all())check(wb.getSheet(schema.label)!=null,"unified template keeps "+schema.label);}
    var emptyBundle=reader.inspectBundle(bundle,"bundle.xlsx","2026-09","");check(emptyBundle.errors().isEmpty()&&emptyBundle.sources().isEmpty(),"unified blank template parses without importing guide");
    var populatedBundle=reader.inspectBundle(bundleRows(),"bundle.xlsx","2026-09","");check(populatedBundle.errors().isEmpty()&&populatedBundle.sources().size()==3,"unified workbook parses all three data sheets atomically");
    var inferredBundle=reader.inspectBundle(bundleRows("20261001-20261015"),"2026-10-cross.xlsx","","");
    check(inferredBundle.errors().isEmpty()&&inferredBundle.sources().stream().filter(s->s.record().dataset().equals("cross")).allMatch(s->s.record().period().key().equals("2026-10")),"cross sheet uses row first-default date");
    var conflictingBundle=reader.inspectBundle(bundleRows("20261001-20261015"),"2026-10-cross.xlsx","2026-09","");
    check(conflictingBundle.errors().isEmpty()&&conflictingBundle.sources().stream().allMatch(i->i.record().period().key().startsWith("2026-10")),"explicit row period and first-default date remain authoritative");
    dateRules(reader);
    exports();System.out.println("IMPORT_WORKBOOK_OK assertions="+assertions+" synthetic templates, parser diagnostics, identifiers, cache and authorized XLSX exports");
  }
  static void dateRules(WorkbookImporter reader)throws Exception{
    for(String text:List.of("2025-2-3","2025/2/3","2025.02.03","2025年2月3日","20250203","2025-02-03 12:34:56")){
      var r=reader.inspect(book("cross",wb->wb.getSheetAt(0).getRow(2).getCell(11).setCellValue(text)),"2026-09-wrong-filename.xlsx","2026-09","","cross");
      check(r.errors().isEmpty()&&r.sources().get(0).record().period().key().equals("2025-02"),"first default date overrides filename and legacy upload month: "+text);
      check(r.sources().get(0).record().values().get(11).equals("2025-02-03"),"date normalized for duplicate matching");
    }
    for(String bad:List.of("","2026-02-30","2026-09","not-a-date")){
      var r=reader.inspect(book("cross",wb->wb.getSheetAt(0).getRow(2).getCell(11).setCellValue(bad)),"2026-09.xlsx","","","cross");
      check(r.sources().isEmpty()&&r.errors().get(0).column().equals("L"),"invalid first date cannot fall back to filename");
    }
    for(boolean date1904:List.of(false,true))for(boolean formula:List.of(false,true)){
      var r=reader.inspect(book("cross",wb->{
        var x=(org.apache.poi.xssf.usermodel.XSSFWorkbook)wb;x.getCTWorkbook().getWorkbookPr().setDate1904(date1904);
        Cell c=wb.getSheetAt(0).getRow(2).getCell(11);double serial=DateUtil.getExcelDate(java.time.LocalDateTime.of(2025,2,3,0,0),date1904);
        if(formula){c.setCellFormula(""+(int)serial);wb.getCreationHelper().createFormulaEvaluator().evaluateAll();}else c.setCellValue(serial);
        CellStyle st=wb.createCellStyle();st.setDataFormat(wb.createDataFormat().getFormat("yyyy/mm/dd"));c.setCellStyle(st);
      }),"2026-09.xlsx","","","cross");
      check(r.errors().isEmpty()&&r.sources().get(0).record().period().key().equals("2025-02"),"numeric/date formula supports workbook date epoch");
    }
  }
  static void exports()throws Exception{
    try(DataStore store=new DataStore(Files.createTempDirectory("xinguan-a2-export-"))){
      var division=FoundationTest.DIV;List<BusinessRecord> records=new ArrayList<>();
      for(String dataset:List.of("multi","negative","cross"))for(String org:Organizations.BRANCHES.keySet())for(String month:List.of("2026-01","2026-04","2026-09")){
        var r=FoundationTest.candidate(dataset,org,org+month);var p=xinguan.platform.Period.parse("",month);var v=new ArrayList<>(r.values());var s=DatasetSchema.get(dataset);if(s.periodColumn>=0)v.set(s.periodColumn,p.key());v.set(s.codeColumn,"000012345678901234");v.set(s.index(dataset.equals("cross")?"cross_feedback":"feedback"),"=HYPERLINK(\"not-executed\")");records.add(new BusinessRecord("",0,dataset,p,org,v,"synthetic.xlsx",r.importedAt(),"",Map.of()));
      }
      for(var schema:DatasetSchema.all())store.platform.importRows(division,schema.id,records.stream().filter(r->r.dataset().equals(schema.id)).toList(),false,UUID.randomUUID().toString());
      var service=new AuthorizedExportService(store);
      for(var role:Role.values()){
        String org=role==Role.SUPER_ADMIN||role==Role.DIVISION_ADMIN?"CZ":"WUJIN";ActorContext actor=new ActorContext("synthetic-"+role,"虚构导出",role,org);int branches=AccessPolicy.all(actor)?9:1;
        for(String dataset:List.of("multi","negative","cross")){
          var result=service.export(actor,Map.of("dataset",dataset,"scope","year","year","2026"));check(result.count()==branches*3,"annual official scope "+role+dataset);
          check(service.export(actor,Map.of("dataset",dataset,"scope","quarter","year","2026","quarter","1")).count()==branches,"quarter scope");
          check(service.export(actor,Map.of("dataset",dataset,"scope","custom","start","2026-04","end","2026-09")).count()==branches*2,"custom month range");
          check(service.export(actor,Map.of("dataset",dataset,"month","2026-09","q","WUJIN")).count()==1,"search same as view filter");
          try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(result.bytes()))){check(wb.getNumberOfSheets()==1&&!wb.isSheetHidden(0),"no hidden or unselected sheets exported");var s=DatasetSchema.get(dataset);Row row=wb.getSheetAt(0).getRow(s.headerRows);check(row.getCell(s.codeColumn).getStringCellValue().equals("000012345678901234"),"export leading zeros");check(row.getCell(s.index(dataset.equals("cross")?"cross_feedback":"feedback")).getCellType()==CellType.STRING,"formula-looking text never executable formula");}
        }
        if(AccessPolicy.all(actor)){
          var progress=service.exportProgress(actor,Map.of("scope","year","year","2026","pageSize","10"));check(progress.count()==27,"progress covers nine branches and three lists, not pagination");
          try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(progress.bytes()))){
            Sheet sh=wb.getSheetAt(0);check(sh.getLastRowNum()==28,"27 progress lines plus total");
            check(sh.getRow(28).getCell(2).getNumericCellValue()==81&&sh.getRow(28).getCell(3).getNumericCellValue()==81,"progress totals all three risk datasets");
          }
          var scoped=service.exportProgress(actor,Map.of("scope","quarter","year","2026","quarter","3","branch","武进"));
          try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(scoped.bytes()))){check(wb.getSheetAt(0).getLastRowNum()==4&&wb.getSheetAt(0).getRow(4).getCell(2).getNumericCellValue()==3,"progress follows time and branch filter");}
        }else expect(SecurityException.class,()->service.exportProgress(actor,Map.of("scope","year","year","2026")));
        if(!AccessPolicy.all(actor))expect(SecurityException.class,()->service.export(actor,Map.of("branch","金坛","month","2026-09")));
      }
      expect(SecurityException.class,()->service.export(null,Map.of()));expect(IllegalArgumentException.class,()->service.export(division,Map.of("dataset","unknown")));
    }
  }
  interface Edit{void run(Workbook wb)throws Exception;}
  static byte[] book(String type,Edit edit)throws Exception{try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(HttpSmokeTest.workbook(type)))){edit.run(wb);ByteArrayOutputStream out=new ByteArrayOutputStream();wb.write(out);return out.toByteArray();}}
  static byte[] bundleRows()throws Exception{return bundleRows("20260901-20260915");}
  static byte[] bundleRows(String period)throws Exception{try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(new ExcelExporter().templateBundle()))){for(var schema:DatasetSchema.all()){Row row=wb.getSheet(schema.label).createRow(schema.headerRows);var values=FoundationTest.candidate(schema.id,"WUJIN","bundle-"+schema.id).values();for(int c=0;c<values.size();c++)row.createCell(c).setCellValue(values.get(c));if(schema.periodColumn>=0)row.getCell(schema.periodColumn).setCellValue(period);else row.getCell(11).setCellValue(period.substring(0,4)+"-"+period.substring(4,6)+"-"+period.substring(6,8));}ByteArrayOutputStream out=new ByteArrayOutputStream();wb.write(out);return out.toByteArray();}}
  interface Work{void run()throws Exception;}
  static void check(boolean v,String text){assertions++;if(!v)throw new AssertionError(text);}
  static void expect(Class<? extends Throwable> type,Work work){assertions++;try{work.run();}catch(Throwable e){if(type.isInstance(e))return;throw new AssertionError("expected "+type+" got "+e,e);}throw new AssertionError("expected "+type);}
}

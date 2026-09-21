import java.io.*;
import org.apache.poi.ss.usermodel.*;
import xinguan.platform.*;

/** Purely generated spreadsheets. Sheet labels must never hide valid or unknown business data. */
public final class Feedback009WorkbookReviewTest {
  public static void main(String[] args)throws Exception {
    var reader=new WorkbookImporter();int checks=0;
    for(var schema:DatasetSchema.all())for(String name:new String[]{"多重预警模板","填写说明","readme data","目录业务"}){
      byte[] bytes=ImportWorkbookTest.book(schema.id,wb->wb.setSheetName(0,name));
      var report=reader.inspectAuto(bytes,"synthetic.xlsx","2026-10");
      if(!report.errors().isEmpty()||report.sources().size()!=1)throw new AssertionError("R4 valid business sheet silently skipped: "+schema.id+" / "+name);checks++;
    }
    byte[] renamed=ImportWorkbookTest.book("multi",wb->{wb.setSheetName(0,"业务模板");wb.cloneSheet(0);});
    if(reader.inspectAuto(renamed,"same-type.xlsx","2026-10").sources().size()!=2)throw new AssertionError("R4 multiple same-kind renamed sheets");checks++;
    try(var wb=WorkbookFactory.create(new ByteArrayInputStream(ImportWorkbookTest.bundleRows()))){
      for(int i=0;i<3;i++)wb.setSheetName(i,"模板说明业务"+i);
      var out=new ByteArrayOutputStream();wb.write(out);var report=reader.inspectAuto(out.toByteArray(),"three.xlsx","2026-10");
      if(!report.errors().isEmpty()||report.sources().size()!=3)throw new AssertionError("R4 three renamed business sheets");checks++;
    }
    for(boolean useGuide:new boolean[]{false,true}){
      byte[] unknown=ImportWorkbookTest.book("multi",wb->{Sheet sheet=useGuide?wb.getSheet("模板说明"):wb.createSheet("说明页");sheet.createRow(25).createCell(3).setCellValue("UNKNOWN-BUSINESS-DATA");});
      if(reader.inspectAuto(unknown,"unknown.xlsx","2026-10").errors().isEmpty())throw new AssertionError("R4 unknown data hidden behind guide name/content");checks++;
    }
    System.out.println("FEEDBACK009_WORKBOOK_REVIEW_OK checks="+checks);
  }
}

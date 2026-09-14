import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import xinguan.platform.*;

/** Exercises the existing dashboard and actual Excel export, not just repository values. */
public final class WorkflowReadModelTest {
  private static int assertions,sequence;
  public static void main(String[] args)throws Exception {
    try(DataStore data=new DataStore(Files.createTempDirectory("xinguan-workflow-readmodel-"))) {
      PlatformStore store=data.platform;String password=id();store.bootstrapSuperAdmin("000000001",password);
      ActorContext root=store.authenticateUser("000000001",password).actor();
      ActorContext div=user(store,root,Role.DIVISION_ADMIN,"CZ"),op=user(store,root,Role.OPERATOR,"WUJIN"),
        reviewer=user(store,root,Role.REVIEWER,"WUJIN"),other=user(store,root,Role.OPERATOR,"JINTAN");
      var workflow=store.workflow();int completed=0;
      for(var schema:DatasetSchema.all()) {
        String dataset=schema.id,key="fixture-"+dataset,field=dataset.equals("cross")?"cross_feedback":"feedback";
        store.importRows(div,dataset,List.of(FoundationTest.candidate(dataset,"WUJIN",key),FoundationTest.candidate(dataset,"JINTAN","other-"+key)),false,id());
        BusinessRecord record=store.list(op,dataset,null,null).get(0);
        String text="仅提交后复核生效-"+dataset;
        var draft=workflow.saveDraft(op,"",0,dataset,List.of(new RecordChange(record.id(),record.version(),Map.of(field,text))),"",id());
        assertReadModel(data,op,other,root,dataset,field,"",completed,"private draft");
        var preview=workflow.previewDraft(op,draft.id(),1);
        assertReadModel(data,op,other,root,dataset,field,"",completed,"preview");
        var submission=workflow.confirm(op,preview.id(),id());
        assertReadModel(data,op,other,root,dataset,field,"",completed,"pending");
        workflow.reject(reviewer,submission.id(),"虚构退回原因",id());
        assertReadModel(data,op,other,root,dataset,field,"",completed,"returned");
        var resaved=workflow.saveDraft(op,draft.id(),1,dataset,List.of(new RecordChange(record.id(),record.version(),Map.of(field,text))),submission.id(),id());
        var again=workflow.confirm(op,workflow.previewDraft(op,resaved.id(),resaved.version()).id(),id());
        workflow.approve(reviewer,again.id(),id());completed++;
        assertReadModel(data,op,other,root,dataset,field,text,completed,"approved");
        BusinessRecord current=store.find(reviewer,record.id());
        var direct=workflow.previewDirect(reviewer,dataset,List.of(new RecordChange(record.id(),current.version(),Map.of(field,""))));
        assertReadModel(data,op,other,root,dataset,field,text,completed,"direct preview");
        workflow.confirm(reviewer,direct.id(),id());completed--;
        assertReadModel(data,op,other,root,dataset,field,"",completed,"clear last formal value");
      }
    }
    System.out.println("WORKFLOW_READMODEL_OK assertions="+assertions+" dashboard counts and real XLSX exports across draft, pending, returned, approved and direct states");
  }
  private static ActorContext user(PlatformStore store,ActorContext root,Role role,String org) {
    var u=store.createUser(root,String.format("%09d",++sequence+10),"虚构展示测试"+sequence,role,org).user();
    store.changeOwnPassword(u.actor(),id());return store.sessionUser(u.id()).actor();
  }
  private static DashboardData dashboard(DataStore data,ActorContext actor) {
    List<String> months=data.months(actor);var range=RangeSelection.from(Map.of("scope","year","year","2026"),months);
    return new DashboardData(range,months,data.readAll(actor),data.readRange(range,actor));
  }
  private static void assertReadModel(DataStore data,ActorContext op,ActorContext other,ActorContext root,String dataset,String field,String expected,int complete,String stage)throws Exception {
    var own=dashboard(data,op);var all=dashboard(data,root);var foreign=dashboard(data,other);
    check(own.completedCount()==complete&&all.completedCount()==complete,stage+" official counts only");
    check(own.branches.get("武进").completed==complete,stage+" branch completion progress");
    check(foreign.completedCount()==0&&foreign.branches.get("武进").total==0,stage+" scoped aggregate");
    DatasetSchema schema=DatasetSchema.get(dataset);int col=schema.index(field);
    for(var view:List.of(own,all,foreign)) {
      List<RowRef> rows=view.filtered(dataset,"","");
      byte[] exported=new ExcelExporter().export(dataset,"虚构导出",rows);
      try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(exported))) {
        Sheet sheet=wb.getSheetAt(0);check(sheet.getLastRowNum()+1==rows.size()+schema.headerRows,stage+" exported scope count");
        for(int i=0;i<rows.size();i++) {
          String org=rows.get(i).values.get(schema.branchColumn);
          String actual=sheet.getRow(schema.headerRows+i).getCell(col).getStringCellValue();
          check(actual.equals(org.equals("武进")?expected:""),stage+" export contains formal values only");
        }
      }
    }
  }
  private static String id(){return UUID.randomUUID().toString();}
  private static void check(boolean valid,String message){assertions++;if(!valid)throw new AssertionError(message);}
}

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import xinguan.platform.*;

/** Synthetic data only. No network or private workbook is required by CI. */
public final class FoundationTest {
  static int assertions;
  static final ActorContext DIV=new ActorContext("division-test","测试分行管理员",Role.DIVISION_ADMIN,Organizations.DIVISION);
  static final ActorContext SUPER=new ActorContext("super-test","测试超级管理员",Role.SUPER_ADMIN,Organizations.DIVISION);
  static final ActorContext OP=new ActorContext("operator-test","测试操作员",Role.OPERATOR,"WUJIN");
  static final ActorContext REVIEW=new ActorContext("review-test","测试复核员",Role.REVIEWER,"WUJIN");
  static final ActorContext BRANCH=new ActorContext("branch-test","测试支行管理员",Role.BRANCH_ADMIN,"WUJIN");
  public static void main(String[] args)throws Exception {
    Path work=Files.createTempDirectory("zhihuixinguan-foundation-test-");
    schemaAndPolicy();templates(work,args.length>0?Path.of(args[0]):null);repository(work.resolve("db"));migration(work.resolve("legacy"));
    System.out.println("FOUNDATION_TEST_OK assertions="+assertions+" synthetic fixtures only");
  }
  static void schemaAndPolicy(){
    check(Organizations.BRANCHES.size()==9,"nine branches");
    for(var e:Organizations.BRANCHES.entrySet()){check(Organizations.resolve(e.getValue()).equals(e.getKey()),"short org");check(Organizations.resolve("常州"+e.getValue()+"支行").equals(e.getKey()),"full org alias");}
    expect(IllegalArgumentException.class,()->Organizations.resolve("未知支行"));
    check(DatasetSchema.get("multi").fields.stream().filter(DatasetSchema.Field::editable).count()==13,"multi yellow columns");
    check(DatasetSchema.get("negative").fields.stream().filter(DatasetSchema.Field::editable).count()==3,"negative yellow columns");
    check(DatasetSchema.get("cross").fields.stream().filter(DatasetSchema.Field::editable).count()==3,"cross yellow columns");
    for(DatasetSchema schema:DatasetSchema.all()){
      List<String> row=new ArrayList<>(Collections.nCopies(schema.width(),""));row.set(schema.customerColumn,"非填报来源内容");check(!schema.complete(row),"source does not complete");
      for(int i=0;i<schema.width();i++)if(schema.editable(i)){row.set(i,"0");check(schema.complete(row),"any filled yellow cell completes");row.set(i," \t　");check(!schema.complete(row),"whitespace not completed");row.set(i,"");}
    }
    check(!AccessPolicy.can(SUPER,AccessPolicy.Action.UPLOAD,"WUJIN"),"super cannot upload");
    check(!AccessPolicy.can(SUPER,AccessPolicy.Action.DIRECT_EDIT,"WUJIN"),"super business read only");
    check(AccessPolicy.can(DIV,AccessPolicy.Action.UPLOAD,"JINTAN"),"division upload");
    check(!AccessPolicy.can(OP,AccessPolicy.Action.DIRECT_EDIT,"WUJIN"),"operator cannot publish");
    check(AccessPolicy.can(OP,AccessPolicy.Action.SAVE_DRAFT,"WUJIN"),"operator draft capability");
    check(AccessPolicy.can(REVIEW,AccessPolicy.Action.REVIEW,"WUJIN"),"reviewer review capability");
    check(!AccessPolicy.can(REVIEW,AccessPolicy.Action.REVIEW,"JINTAN"),"reviewer cannot cross branch");
    check(!AccessPolicy.canManage(BRANCH,Role.BRANCH_ADMIN,"WUJIN"),"cannot manage peers");
    check(!AccessPolicy.canManage(BRANCH,Role.OPERATOR,"JINTAN"),"cannot manage another branch");
    check(AccessPolicy.canManage(DIV,Role.BRANCH_ADMIN,"JINTAN"),"division manages branch admin");
    check(!AccessPolicy.canManage(SUPER,Role.DIVISION_ADMIN,"WUJIN"),"division admin must belong to division");
    check(!AccessPolicy.canManage(DIV,Role.OPERATOR,"UNKNOWN"),"managed user must have known branch");
    expect(SecurityException.class,()->AccessPolicy.require(null,AccessPolicy.Action.VIEW,"WUJIN"));
    expect(IllegalArgumentException.class,()->new ActorContext("x","x",Role.OPERATOR,Organizations.DIVISION));
  }
  static void templates(Path work,Path userTemplate)throws Exception {
    WorkbookImporter importer=new WorkbookImporter();ExcelExporter exporter=new ExcelExporter();
    for(DatasetSchema s:DatasetSchema.all()){
      Path blank=work.resolve(s.id+"-template.xlsx");Files.write(blank,exporter.template(s.id));
      check(importer.read(blank,blank.getFileName().toString(),"2026-09","",s.id).rows().isEmpty(),"blank template roundtrip");
      try(Workbook wb=WorkbookFactory.create(blank.toFile())){
        Sheet sheet=wb.getSheetAt(0);List<String> values=new ArrayList<>(candidate(s.id,"WUJIN","A").values());
        Row row=sheet.createRow(s.headerRows);for(int c=0;c<values.size();c++)row.createCell(c).setCellValue(values.get(c));
        Path filled=work.resolve(s.id+"-filled.xlsx");try(OutputStream out=Files.newOutputStream(filled)){wb.write(out);}
        List<BusinessRecord> result=importer.read(filled,filled.getFileName().toString(),"2026-09","",s.id).rows();check(result.size()==1,"filled template roundtrip");check(!result.get(0).complete(),"source-only template remains incomplete");
        String wrong=s.id.equals("multi")?"negative":"multi";expect(WorkbookImportException.class,()->importer.read(filled,"file.xlsx","2026-09","",wrong));
        if(s.id.equals("cross"))expect(WorkbookImportException.class,()->importer.read(filled,"no-date.xlsx","","",s.id));
        for(int c=0;c<s.width();c++)check(sheet.getRow(0).getCell(c).getCellStyle().getFillForegroundColor()==(s.editable(c)?IndexedColors.YELLOW.getIndex():IndexedColors.GREY_25_PERCENT.getIndex()),"template fill consistent");
      }
      if(userTemplate!=null)check(importer.read(userTemplate,userTemplate.getFileName().toString(),"2026-09","",s.id).rows().isEmpty(),"real user template has no imported examples");
    }
    var quarterly=xinguan.platform.Period.parse("20260101-20260331","");check(quarterly.start().equals(LocalDate.of(2026,1,1))&&quarterly.end().equals(LocalDate.of(2026,3,31)),"quarter span");
    expect(RuntimeException.class,()->xinguan.platform.Period.parse("20260230-20260331",""));
    expect(RuntimeException.class,()->xinguan.platform.Period.parse("20260930-20260901",""));
  }
  static void repository(Path dir)throws Exception {
    String keptId;
    try(PlatformStore store=new PlatformStore(dir)){
      BusinessRecord a=candidate("multi","WUJIN","A"),b=candidate("multi","JINTAN","B");
      var first=store.importRows(DIV,"multi",List.of(a,b),false,"initial-import-0001");check(first.added()==2,"two imports");
      check(store.importRows(DIV,"multi",List.of(a,b),false,"initial-import-0001").batchId().equals(first.batchId()),"idempotent import");
      check(store.list(OP,null,null,null).size()==1,"branch scoped list");expect(SecurityException.class,()->store.list(null,null,null,null));
      BusinessRecord officialA=store.list(OP,"multi",null,null).get(0);keptId=officialA.id();BusinessRecord officialB=store.list(DIV,"multi",null,null).stream().filter(r->r.organizationId().equals("JINTAN")).findFirst().orElseThrow();
      expect(SecurityException.class,()->store.find(OP,officialB.id()));
      RecordChange edit=new RecordChange(officialA.id(),1,Map.of("feedback","已核实测试内容"));
      String previewBaseline=PlatformStore.baseline(store.list(DIV,"multi",null,null));
      expect(SecurityException.class,()->store.publishDirect(OP,List.of(edit),"operator-write-0001"));
      expect(SecurityException.class,()->store.publishDirect(SUPER,List.of(edit),"super-write-0001"));
      store.publishDirect(BRANCH,List.of(edit),"branch-write-0001");check(store.find(OP,keptId).complete(),"single feedback completes");
      check(store.find(OP,keptId).version()==2,"revision advanced");
      expect(ConcurrentModificationException.class,()->store.importRows(DIV,"multi",List.of(a),true,"stale-preview-0001",previewBaseline));
      check(store.find(OP,keptId).values().get(17).equals("已核实测试内容"),"stale preview cannot overwrite newer feedback");
      store.publishDirect(BRANCH,List.of(edit),"branch-write-0001");check(store.find(OP,keptId).version()==2,"idempotent save despite stale base");
      expect(ConcurrentModificationException.class,()->store.publishDirect(BRANCH,List.of(edit),"stale-write-0001"));
      expect(IllegalArgumentException.class,()->store.publishDirect(BRANCH,List.of(new RecordChange(keptId,2,Map.of("customer_name","tamper"))),"readonly-field-0001"));
      int audits=store.diagnostics().get("audit_events");
      expect(ConcurrentModificationException.class,()->store.publishDirect(DIV,List.of(new RecordChange(officialA.id(),2,Map.of("feedback","must rollback")),new RecordChange(officialB.id(),999,Map.of("feedback","stale"))),"atomic-rollback-0001"));
      check(store.find(OP,keptId).values().get(17).equals("已核实测试内容"),"whole batch rolled back");check(store.diagnostics().get("audit_events")==audits,"audit rollback");
      check(store.auditEvents(OP,100).stream().allMatch(e->e.organization().equals("WUJIN")),"audit scope");
      store.importRows(DIV,"multi",List.of(b,a),false,"reordered-import-0001");check(store.find(OP,keptId).id().equals(keptId),"stable id after source reordering");
      check(store.find(OP,keptId).values().get(17).equals("已核实测试内容"),"blank reimport preserves fills");
      expect(SecurityException.class,()->store.importRows(SUPER,"multi",List.of(a),false,"super-import-0001"));
      store.importRows(DIV,"multi",List.of(a),true,"overwrite-clear-0001");check(!store.find(OP,keptId).complete(),"explicit blank overwrite clears completion");
      int count=store.list(DIV,null,null,null).size();BusinessRecord invalid=new BusinessRecord("",0,"multi",a.period(),"INVALID",a.values(),a.filename(),a.importedAt(),"",Map.of());
      expect(IllegalArgumentException.class,()->store.importRows(DIV,"multi",List.of(candidate("multi","WUJIN","C"),invalid),false,"bad-batch-rollback-0001"));check(store.list(DIV,null,null,null).size()==count,"import batch rolls back");
      var longPeriod=xinguan.platform.Period.parse("20260101-20260331","");List<String> v=new ArrayList<>(a.values());v.set(0,"quarter");v.set(22,longPeriod.key());
      store.importRows(DIV,"multi",List.of(new BusinessRecord("",0,"multi",longPeriod,"WUJIN",v,"synthetic.xlsx",a.importedAt(),"",Map.of())),false,"quarter-import-0001");
      check(store.list(OP,"multi",LocalDate.of(2026,2,1),LocalDate.of(2026,2,28)).size()==1,"interval overlap in middle month");
    }
    try(PlatformStore reopened=new PlatformStore(dir)){check(reopened.find(OP,keptId).id().equals(keptId),"persistent id after restart");}
  }
  static void migration(Path dir)throws Exception {
    LegacyDataStore legacy=new LegacyDataStore(dir);ImportRecord m=new ImportRecord();m.dataset="multi";m.month="2026-07";m.period="2026-07-P1";m.filename="synthetic-legacy.et";m.importedAt="2026-07-01T00:00:00Z";m.columns=new ArrayList<>(Collections.nCopies(22,"旧表头"));
    List<String> v=new ArrayList<>(Collections.nCopies(22,""));v.set(0,"1");v.set(1,"武进支行");v.set(2,"虚构迁移企业");v.set(3,"0000123");v.set(17,"否");v.set(18,"日常一半管控");v.set(19,"历史措施");v.set(20,"维持");v.set(21,"必须保留的历史备注");m.rows.add(v);legacy.save(m);
    ImportRecord n=new ImportRecord();n.dataset="negative";n.month="2026-07";n.period="2026-07-P1";n.filename="synthetic-legacy-negative.et";n.importedAt=m.importedAt;n.columns=new ArrayList<>(Collections.nCopies(13,"旧表头"));List<String> nv=new ArrayList<>(Collections.nCopies(13,""));nv.set(0,"1");nv.set(2,"虚构负面企业");nv.set(5,"未映射测试机构");nv.set(6,"历史经理");nv.set(9,"已有反馈");n.rows.add(nv);legacy.save(n);
    Map<Path,String> before=new HashMap<>();try(var files=Files.walk(dir.resolve("months"))){for(Path p:files.filter(Files::isRegularFile).toList())before.put(p,Codec.hash(Base64.getEncoder().encodeToString(Files.readAllBytes(p))));}
    try(DataStore migrated=new DataStore(dir)){
      var all=migrated.platform.list(DIV,null,null,null);check(all.size()==2,"legacy row count");BusinessRecord r=all.stream().filter(x->x.dataset().equals("multi")).findFirst().orElseThrow();
      check(r.values().get(3).equals("0000123"),"leading zeros preserved");check(r.values().get(18).equals("否"),"moved default risk column");check(r.values().get(19).equals("日常一般管控"),"legacy enum alias mapped");check(r.legacyExtras().get("原多重预警备注").equals("必须保留的历史备注"),"legacy notes preserved");
      check(migrated.platform.list(OP,null,null,null).size()==1,"unmapped record not assigned to branch");check(migrated.platform.diagnostics().get("unassigned_records")==1,"quarantine count");
    }
    try(DataStore repeated=new DataStore(dir)){check(repeated.platform.list(DIV,null,null,null).size()==2,"migration idempotent");}
    for(var e:before.entrySet())check(e.getValue().equals(Codec.hash(Base64.getEncoder().encodeToString(Files.readAllBytes(e.getKey())))),"legacy file unchanged");
  }
  static BusinessRecord candidate(String dataset,String org,String key){
    DatasetSchema s=DatasetSchema.get(dataset);List<String> row=new ArrayList<>(Collections.nCopies(s.width(),""));row.set(0,key);row.set(s.customerColumn,"虚构测试企业 "+key);row.set(s.codeColumn,"00000"+key);row.set(s.branchColumn,Organizations.label(org));
    var p=xinguan.platform.Period.parse("20260901-20260915","");if(s.periodColumn>=0)row.set(s.periodColumn,p.key());
    return new BusinessRecord("",0,dataset,p,org,row,"synthetic.xlsx","2026-09-11T00:00:00Z","",Map.of());
  }
  static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
  interface Throwing{void run()throws Exception;}
  static void expect(Class<? extends Throwable> type,Throwing f){assertions++;try{f.run();}catch(Throwable e){if(type.isInstance(e))return;throw new AssertionError("Expected "+type+" but got "+e,e);}throw new AssertionError("Expected "+type);}
}

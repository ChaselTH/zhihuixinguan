import java.nio.file.*;
import java.time.*;
import java.util.*;
import xinguan.platform.*;

/** Pages use the new repository; old files are read only for one-time migration. */
final class DataStore implements AutoCloseable {
  final PlatformStore platform;
  final Path root;
  DataStore(Path root)throws Exception {
    this.root=root.toAbsolutePath().normalize();Files.createDirectories(this.root);
    platform=new PlatformStore(this.root);
    try{migrate();}catch(Exception e){platform.close();throw e;}
  }
  Path dataRoot(){return root;}
  List<ImportRecord> readAll(ActorContext actor){return adapt(platform.list(actor,null,null,null),actor);}
  List<ImportRecord> readRange(RangeSelection range,ActorContext actor){return adapt(platform.list(actor,null,YearMonth.parse(range.start).atDay(1),YearMonth.parse(range.end).atEndOfMonth()),actor);}
  List<String> months(ActorContext actor){
    Set<String> months=new TreeSet<>(Comparator.reverseOrder());
    for(BusinessRecord r:platform.list(actor,null,null,null))for(YearMonth m=YearMonth.from(r.period().start());!m.isAfter(YearMonth.from(r.period().end()));m=m.plusMonths(1))months.add(m.toString());
    return new ArrayList<>(months);
  }
  private List<ImportRecord> adapt(List<BusinessRecord> rows,ActorContext actor){
    var deadlines=platform.deadlines().visible(actor);Instant asOf=Instant.now();
    List<ImportRecord> result=new ArrayList<>();
    for(BusinessRecord row:rows){
      ImportRecord r=new ImportRecord();r.id=row.id();r.dataset=row.dataset();r.period=row.period().key();r.month=YearMonth.from(row.period().start()).toString();r.filename=row.filename();r.importedAt=row.importedAt();r.updatedAt=row.updatedAt();
      var deadline=deadlines.get(new FeedbackDeadlines.Key(row.dataset(),row.period().key()));r.feedbackAsOf=asOf;if(deadline!=null){r.feedbackDeadline=deadline.dueDate();r.deadlineRevision=deadline.revision();}
      r.columns=DatasetSchema.get(row.dataset()).fields.stream().map(DatasetSchema.Field::title).toList();r.rows.add(row.values());r.versions.add(row.version());r.organizationId=row.organizationId();r.legacyExtras=row.legacyExtras();result.add(r);
    }
    return result;
  }
  private void migrate()throws Exception {
    if(!Files.isDirectory(root.resolve("months")))return;
    List<PlatformStore.LegacyItem> items=new ArrayList<>();
    for(ImportRecord old:new LegacyDataStore(root).readAll()) {
      DatasetSchema schema=DatasetSchema.get(old.dataset);
      for(int rowNo=0;rowNo<old.rows.size();rowNo++) {
        List<String> row=old.rows.get(rowNo);List<String> values=new ArrayList<>(Collections.nCopies(schema.width(),""));Map<String,String> extras=new LinkedHashMap<>();
        if("multi".equals(old.dataset)){
          for(int c=0;c<=16;c++)values.set(c,cell(row,c));
          values.set(18,cell(row,17));values.set(19,cell(row,18).replace("日常一半管控","日常一般管控"));values.set(20,cell(row,19));values.set(21,cell(row,20));
          if(!cell(row,21).isBlank())extras.put("原多重预警备注",cell(row,21));values.set(22,old.period);
        }else{
          for(int c=0;c<=2;c++)values.set(c,cell(row,c));
          values.set(4,cell(row,3));values.set(5,cell(row,4));values.set(6,cell(row,5));
          for(int c=7;c<=12;c++)values.set(c,cell(row,c));
          if(!cell(row,6).isBlank())extras.put("原客户经理",cell(row,6));
        }
        String org;try{org=Organizations.resolve(values.get(schema.branchColumn));values.set(schema.branchColumn,Organizations.label(org));}
        catch(IllegalArgumentException e){org=Organizations.UNASSIGNED;extras.put("原机构名称",values.get(schema.branchColumn));}
        xinguan.platform.Period p;
        try{p=xinguan.platform.Period.parse(schema.value(values,schema.periodColumn),old.month);}catch(RuntimeException e){extras.put("原时间顺序",schema.value(values,schema.periodColumn));p=xinguan.platform.Period.parse(old.period,old.month);}
        if(schema.periodColumn>=0)values.set(schema.periodColumn,p.key());
        String imported=old.importedAt.isBlank()?Instant.now().toString():old.importedAt;
        BusinessRecord b=new BusinessRecord("",0,old.dataset,p,org,values,old.filename,imported,old.updatedAt==null?"":old.updatedAt,extras);
        String key=old.month+"/"+old.id+"/"+rowNo;items.add(new PlatformStore.LegacyItem(key,b,Codec.hash(Codec.encode(row))));
      }
    }
    int count=platform.migrateLegacy(items);
    if(count>0)System.out.println("旧数据迁移完成："+count+" 条；原文件保持不变，历史备注和客户经理已单独保留。");
    int unassigned=platform.diagnostics().get("unassigned_records");
    if(unassigned>0)System.out.println("需核对机构："+unassigned+" 条，仅分行范围可见，未自动分给任何支行。");
  }
  private static String cell(List<String> row,int c){return c<row.size()&&row.get(c)!=null?row.get(c):"";}
  @Override public void close()throws Exception{platform.close();}
}

package xinguan.platform;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static xinguan.platform.WorkflowContracts.*;

/** Compiled and executed against the actual rc.10 source, never a downgraded new database. */
public final class Schema8ReviewFixture {
  static int sequence=20;
  static String request(){return UUID.randomUUID().toString();}
  static ActorContext user(PlatformStore store,ActorContext root,Role role,String org){var created=store.createUser(root,String.format("%09d",++sequence),"synthetic-"+role,role,org);store.changeOwnPassword(created.user().actor(),request());return store.sessionUser(created.user().id()).actor();}
  static RecordChange edit(BusinessRecord row,String value){return new RecordChange(row.id(),row.version(),Map.of(row.dataset().equals("cross")?"cross_feedback":"feedback",value));}
  static BusinessRecord row(PlatformStore store,ActorContext div,String type,String org,String month){var schema=DatasetSchema.get(type);var values=new ArrayList<>(Collections.nCopies(schema.width(),""));String key="SYNTHETIC-"+(++sequence);values.set(0,key);values.set(schema.customerColumn,key);values.set(schema.codeColumn,key);values.set(schema.branchColumn,Organizations.label(org));if(schema.periodColumn>=0)values.set(schema.periodColumn,month);if(type.equals("cross"))values.set(11,month+"-03");store.importRows(div,type,List.of(new BusinessRecord("",0,type,Period.parse(month,""),org,values,"synthetic.xlsx",Instant.now().toString(),"",Map.of())),false,request());return store.list(div,type,null,null).stream().filter(r->r.values().get(0).equals(key)).findFirst().orElseThrow();}
  static Submission submit(PlatformStore store,ActorContext op,BusinessRecord row,String value,String prior){var w=store.workflow();var d=w.saveDraft(op,"",0,row.dataset(),List.of(edit(row,value)),prior,request());return w.confirm(op,w.previewDraft(op,d.id(),1).id(),request());}
  public static void main(String[] args)throws Exception {
    Path dir=Path.of(args[0]);Properties p=new Properties();
    if(args.length>1&&args[1].equals("open-schema10")){try(var store=new PlatformStore(dir)){if(store.schemaVersion()!=10)throw new AssertionError("expected original PR schema 10");}System.out.println("ORIGINAL_PR_SCHEMA10_FIXTURE_OK");return;}
    try(var store=new PlatformStore(dir)){
      if(store.schemaVersion()!=8)throw new AssertionError("Fixture must execute actual schema 8 application");
      String password=request();store.bootstrapSuperAdmin("000000001",password);var root=store.authenticateUser("000000001",password).actor();
      var div=user(store,root,Role.DIVISION_ADMIN,"CZ");var op=user(store,root,Role.OPERATOR,"WUJIN");var review=user(store,root,Role.REVIEWER,"WUJIN");user(store,root,Role.BRANCH_ADMIN,"WUJIN");user(store,root,Role.OPERATOR,"JINTAN");user(store,root,Role.REVIEWER,"JINTAN");
      var op2=user(store,root,Role.OPERATOR,"WUJIN");
      p.setProperty("root",root.userId());p.setProperty("div",div.userId());p.setProperty("op",op.userId());p.setProperty("review",review.userId());
      for(String type:List.of("multi","negative","cross")){
        var row=row(store,div,type,"WUJIN","2026-08");var rejected=submit(store,op,row,"first rejected "+type,"");store.workflow().reject(review,rejected.id(),"OBSOLETE-RETURN-"+type,request());
        var revised=submit(store,op,store.find(op,row.id()),"APPROVED-HISTORY-"+type,rejected.id());store.workflow().approve(review,revised.id(),request());
        p.setProperty(type,row.id());p.setProperty(type+".submission",revised.id());p.setProperty(type+".revision",""+store.find(div,row.id()).version());
      }
      var returned=row(store,div,"negative","WUJIN","2026-08");var rejected=submit(store,op,returned,"return me","");store.workflow().reject(review,rejected.id(),"CURRENT-RETURN",request());p.setProperty("returned",returned.id());
      var pending=row(store,div,"cross","WUJIN","2026-08");var pendingSubmission=submit(store,op,pending,"OLD-PENDING","");p.setProperty("pending",pending.id());p.setProperty("pending.submission",pendingSubmission.id());
      var draftRow=row(store,div,"multi","WUJIN","2026-08");var draft=store.workflow().saveDraft(op,"",0,"multi",List.of(edit(draftRow,"PRIVATE-OLD-DRAFT")),"",request());p.setProperty("draft",draft.id());
      var deleted=row(store,div,"negative","JINTAN","2026-07");var deletion=store.maintenance().previewMonth(div,"2026-07");store.maintenance().confirm(div,deletion.token(),"month");p.setProperty("deleted",deleted.id());
      store.completionRules().save(div,"multi",Set.of("feedback"),0);store.deadlines().save(div,"multi","2026-08","2099-12-31",0);
      // Old staging deliberately lacks the newly introduced selected_month column.
      var staged=store.importing().stage(div,"multi",List.of(new ImportPlatform.SourceRow(store.find(div,p.getProperty("multi")),"synthetic",3)),0);p.setProperty("import",staged.id());
      if(args.length<2||!args[1].equals("single-pending")){
        var collision=row(store,div,"multi","WUJIN","2026-08");var first=submit(store,op,collision,"legacy-first","");var second=submit(store,op2,collision,"legacy-second","");
        p.setProperty("collision",collision.id());p.setProperty("collision.first",first.id());p.setProperty("collision.second",second.id());
      }
    }
    try(var db=connect(dir)){for(String table:preservedTables())p.setProperty("hash."+table,tableHash(db,table));}
    try(var out=Files.newOutputStream(dir.resolve("synthetic-fixture.properties"))){p.store(out,"Generated schema 8 verification identifiers and hashes; no login credentials");}
    System.out.println("SCHEMA8_REAL_FIXTURE_OK");
  }
  static Connection connect(Path dir)throws Exception{return DriverManager.getConnection("jdbc:h2:file:"+dir.resolve("platform/records").toAbsolutePath().toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE","sa","");}
  static List<String> preservedTables(){return List.of("official_records","submissions","drafts","audit_events","users","notification_events","notification_receipts","record_deletions","feedback_deadlines","completion_rules","workflow_audit_links","pending_submission_records");}
  static String tableHash(Connection db,String table)throws Exception {List<String> rows=new ArrayList<>();try(var st=db.createStatement();var rs=st.executeQuery("SELECT * FROM "+table)){while(rs.next()){List<String> cells=new ArrayList<>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)cells.add(Objects.toString(rs.getString(i),"<NULL>"));rows.add(Codec.encode(cells));}}Collections.sort(rows);return Codec.hash(Codec.encode(rows));}
}

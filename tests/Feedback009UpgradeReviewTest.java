package xinguan.platform;

import java.nio.file.*;
import java.util.*;
import static xinguan.platform.WorkflowContracts.*;

public final class Feedback009UpgradeReviewTest {
  public static void main(String[] args)throws Exception {
    Path dir=Path.of(args[0]);var p=new Properties();try(var in=Files.newInputStream(dir.resolve("synthetic-fixture.properties"))){p.load(in);}
    if(args.length>1&&args[1].equals("reject-original-pr")){
      try(var ignored=new PlatformStore(dir)){throw new AssertionError("modified unpublished V009 must not silently accept original-PR migrated DB");}catch(java.io.IOException expected){}
      try(var db=Schema8ReviewFixture.connect(dir)){for(String table:Schema8ReviewFixture.preservedTables())if(!p.getProperty("hash."+table).equals(Schema8ReviewFixture.tableHash(db,table)))throw new AssertionError("failed migration modified preserved table "+table);}
      System.out.println("ORIGINAL_PR_MIGRATION_REJECTED_WITH_HISTORY_PRESERVED");return;
    }
    for(int start=0;start<2;start++)try(var store=new PlatformStore(dir)){
      var div=store.sessionUser(p.getProperty("div")).actor();var op=store.sessionUser(p.getProperty("op")).actor();
      for(String type:List.of("multi","negative","cross")){
        var r=store.find(div,p.getProperty(type));
        if(r.workflowStage()!=RowStage.LEGACY_PUBLISHED||!r.workflowReason().isEmpty())throw new AssertionError("R2 historical return reopens later approved "+type+": "+r.workflowStage());
        if(r.version()!=Long.parseLong(p.getProperty(type+".revision"))||!Schema8ReviewFixture.edit(r,"unused").values().keySet().stream().allMatch(k->r.values().get(DatasetSchema.get(type).index(k)).equals("APPROVED-HISTORY-"+type)))throw new AssertionError("legacy values/version changed");
      }
      if(store.find(op,p.getProperty("returned")).workflowStage()!=RowStage.RETURNED||!store.find(op,p.getProperty("returned")).workflowReason().equals("CURRENT-RETURN"))throw new AssertionError("live returned task lost");
      if(store.find(op,p.getProperty("pending")).workflowStage()!=RowStage.BRANCH_REVIEW)throw new AssertionError("old pending must require both stages");
      if(!store.workflow().draft(op,p.getProperty("draft")).rows().get(0).change().values().containsValue("PRIVATE-OLD-DRAFT"))throw new AssertionError("old private draft lost");
      try(var db=Schema8ReviewFixture.connect(dir)){for(String table:Schema8ReviewFixture.preservedTables())if(!p.getProperty("hash."+table).equals(Schema8ReviewFixture.tableHash(db,table)))throw new AssertionError("migration modified preserved table "+table);}
    }
    try(var store=new PlatformStore(dir)){
      var div=store.sessionUser(p.getProperty("div")).actor();var review=store.sessionUser(p.getProperty("review")).actor();
      store.workflow().approve(review,p.getProperty("pending.submission"),Schema8ReviewFixture.request());
      if(store.find(div,p.getProperty("pending")).workflowStage()!=RowStage.DIVISION_REVIEW)throw new AssertionError("legacy branch approval skipped final review");
      store.workflow().approve(div,p.getProperty("pending.submission"),Schema8ReviewFixture.request());
      if(p.containsKey("collision")){
        String first=p.getProperty("collision.first"),second=p.getProperty("collision.second");
        try{store.workflow().approve(review,first,Schema8ReviewFixture.request());throw new AssertionError("legacy competing pending task approved");}catch(WorkflowException expected){}
        store.workflow().reject(review,first,"明确退回旧版重复任务",Schema8ReviewFixture.request());
        if(store.workflow().submission(div,second).state()!=State.SUBMITTED)throw new AssertionError("returning duplicate must preserve other pending snapshot");
        store.workflow().approve(review,second,Schema8ReviewFixture.request());store.workflow().approve(div,second,Schema8ReviewFixture.request());
        if(!store.find(div,p.getProperty("collision")).values().contains("legacy-second"))throw new AssertionError("surviving legacy pending proposal not published");
      }
      var r=store.find(div,p.getProperty("multi"));store.workflow().reopenCompleted(div,r.id(),r.version(),"legacy reopening",Schema8ReviewFixture.request());
      try{store.importing().confirm(div,p.getProperty("import"),1,"saved",false,false);throw new AssertionError("old monthless preview accepted");}catch(ConcurrentModificationException expected){}
    }
    System.out.println("FEEDBACK009_UPGRADE_REVIEW_OK actual schema8 -> schema10, restart, exact historic table hashes, two stages, legacy reopen");
  }
}

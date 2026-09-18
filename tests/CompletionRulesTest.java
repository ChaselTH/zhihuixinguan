package xinguan.platform;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import static xinguan.platform.WorkflowPlatformTest.*;

public class CompletionRulesTest {
  static int assertions;
  public static void main(String[] args)throws Exception {
    try(var f=new Fixture()){
      var rules=f.store.completionRules();
      check(rules.visible(f.op).size()==3&&rules.visible(f.root).values().stream().allMatch(s->s.revision()==0&&s.requiredFields().isEmpty()),"upgrade default keeps all fields optional");
      for(var actor:List.of(f.root,f.branch,f.op,f.review,f.otherOp))expect(SecurityException.class,()->rules.save(actor,"multi",Set.of("feedback"),0));
      expect(SecurityException.class,()->rules.save(null,"multi",Set.of(),0));expect(SecurityException.class,()->rules.visible(null));
      for(String field:List.of("customer_name","period","unknown","cross_feedback"))expect(IllegalArgumentException.class,()->rules.save(f.div,"multi",Set.of(field),0));
      expect(IllegalArgumentException.class,()->rules.save(f.div,"bad",Set.of(),0));expect(IllegalArgumentException.class,()->rules.save(f.div,"multi",Set.of(),-1));
      for(var schema:DatasetSchema.all()){
        var keys=new LinkedHashSet<String>();for(var field:schema.fields)if(field.editable())keys.add(field.key());
        var values=new ArrayList<>(Collections.nCopies(schema.width(),""));values.set(schema.customerColumn,"source only");
        check(!schema.complete(values)&&!schema.complete(values,keys),"empty source not complete");
        for(String key:keys){values.set(schema.index(key),"0");check(schema.complete(values),"legacy any-yellow supports zero");values.set(schema.index(key),"\t　 ");check(!schema.complete(values,Set.of(key)),"whitespace required remains missing");values.set(schema.index(key),"");}
        for(String key:keys)values.set(schema.index(key),"0");check(schema.complete(values,keys),"all required filled; zero is content");
        for(String key:keys){values.set(schema.index(key),"");check(!schema.complete(values,keys),"each required field contributes");values.set(schema.index(key),"0");}
      }
      var row=f.record("WUJIN","multi");int audits=f.store.diagnostics().get("audit_events");
      var first=rules.save(f.div,"multi",Set.of("feedback","default_risk"),0);
      check(first.revision()==1&&rules.visible(f.otherOp).get("multi").equals(first),"one dataset rule applies across all branches");
      check(rules.save(f.div,"multi",Set.of("default_risk","feedback"),1).equals(first)&&f.store.diagnostics().get("audit_events")==audits+1,"same setting is a no-op regardless of key order");
      expect(ConcurrentModificationException.class,()->rules.save(f.div,"multi",Set.of(),0));
      check(f.store.find(f.op,row.id()).version()==row.version(),"rules do not mutate official row versions");
      var pending=f.pending(f.op,row,"partial feedback");check(!f.store.find(f.op,row.id()).complete(first),"draft and pending remain unofficial");
      f.w().approve(f.review,pending.id(),id());var partial=f.store.find(f.op,row.id());
      check(!partial.complete(first)&&value(partial).equals("partial feedback"),"missing required fields do not block operator submission or approval");
      var preview=f.w().previewDirect(f.branch,"multi",List.of(new RecordChange(partial.id(),partial.version(),Map.of("default_risk","否"))));f.w().confirm(f.branch,preview.id(),id());
      var filled=f.store.find(f.op,row.id());check(filled.complete(first),"required yes/no false counts as filled");
      var clear=f.w().previewDirect(f.review,"multi",List.of(new RecordChange(filled.id(),filled.version(),Map.of("feedback","","default_risk",""))));f.w().confirm(f.review,clear.id(),id());
      check(!f.store.find(f.op,row.id()).complete(first),"clearing all required content may be confirmed and stays incomplete");
      for(String checkpoint:List.of("completion-rule-written","completion-rule-audited")){
        audits=f.store.diagnostics().get("audit_events");f.fail(checkpoint,1);
        expect(IllegalStateException.class,()->rules.save(f.div,"multi",Set.of("feedback"),1));
        check(rules.visible(f.root).get("multi").equals(first)&&f.store.diagnostics().get("audit_events")==audits,"setting and audit rollback together");
      }
      var result=AccessPlatformTest.race(()->rules.save(f.div,"multi",Set.of("feedback"),1),()->rules.save(f.div,"multi",Set.of("default_risk"),1));
      check(result.stream().filter(v->v instanceof CompletionRules.Setting).count()==1&&result.stream().filter(v->v instanceof ConcurrentModificationException).count()==1,"concurrent setting changes conflict safely");
      var reset=rules.save(f.div,"multi",Set.of(),2);check(reset.revision()==3&&rules.visible(f.op).get("multi").requiredFields().isEmpty(),"clear keeps revision and decodes empty field list");
      f.reopen();check(f.store.completionRules().visible(f.op).get("multi").equals(reset),"reset persists after restart");
      var auditFilter=new AccessPlatform.AuditFilter("business","","","COMPLETION_RULE_SET",null,null);
      check(!f.store.access().audit(f.div,auditFilter,0,100).isEmpty()&&f.store.access().audit(f.branch,auditFilter,0,100).isEmpty(),"global rule audit follows division domain");
      f.store.updateUser(f.root,f.div.userId(),f.div.identityRevision(),"revoked",Role.DIVISION_ADMIN,"CZ",true);
      expect(SecurityException.class,()->f.store.completionRules().save(f.div,"multi",Set.of("feedback"),3));
    }
    try(var f=new Fixture()){
      var r=f.record("WUJIN","multi");var pending=f.pending(f.op,r,"keep pending");
      f.store.deadlines().save(f.div,"multi",r.period().key(),"2099-12-31",0);int audits=f.store.diagnostics().get("audit_events");
      f.store.close();try(var c=connect(f.dir);var st=c.createStatement()){st.execute("DROP TABLE completion_rules");st.execute("DELETE FROM schema_migrations WHERE version=8");st.execute("DELETE FROM schema_migration_attempts WHERE version=8");}
      f.open();check(f.store.schemaVersion()==8&&f.store.find(f.op,r.id()).values().equals(r.values())&&f.store.diagnostics().get("audit_events")==audits,"V7 to V8 preserves rows and audit");
      check(f.w().submission(f.op,pending.id()).state()==WorkflowContracts.State.SUBMITTED&&f.store.deadlines().visible(f.op).size()==1,"V7 pending snapshots and deadlines preserved");
      check(f.store.completionRules().visible(f.op).values().stream().allMatch(s->s.requiredFields().isEmpty()),"V7 migration initializes optional rules");
      f.store.completionRules().save(f.div,"multi",Set.of("default_risk"),0);f.w().approve(f.review,pending.id(),id());
      check(!f.store.find(f.op,r.id()).complete(f.store.completionRules().visible(f.op).get("multi")),"rule change after submit does not invalidate snapshot, only live completion");
      f.reopen();check(f.store.completionRules().visible(f.op).get("multi").requiredFields().equals(Set.of("default_risk")),"nonempty config persists");
    }
    Path failed=Files.createTempDirectory("xinguan-required-migration-fail-");
    expect(java.io.IOException.class,()->{try(var ignored=new PlatformStore(failed,Clock.systemUTC(),p->{if(p.equals("migration-8-step-1"))throw new IllegalStateException("synthetic fault");})){throw new AssertionError("fault missing");}});
    expect(java.io.IOException.class,()->{try(var ignored=new PlatformStore(failed)){throw new AssertionError("partial migration accepted");}});
    System.out.println("COMPLETION_RULES_OK assertions="+assertions+" authorization, rules, atomic audit, races, restart, partial workflow and V7 upgrade");
  }
  static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
  interface Work{void run()throws Exception;}
  static void expect(Class<? extends Throwable> type,Work work){assertions++;try{work.run();}catch(Throwable t){if(type.isInstance(t))return;throw new AssertionError(t);}throw new AssertionError("expected "+type);}
}

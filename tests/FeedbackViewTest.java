import java.nio.file.*;
import java.time.*;
import java.util.*;
import xinguan.platform.*;

public class FeedbackViewTest {
  static int assertions;
  public static void main(String[] args)throws Exception{
    var clock=new TestClock();
    try(DataStore data=new DataStore(Files.createTempDirectory("xinguan-feedback-view-"),clock)){
      var store=data.platform;store.bootstrapSuperAdmin("909000001","synthetic-feedback-only");
      var root=store.authenticateUser("909000001","synthetic-feedback-only").actor();
      var div=user(store,root,"909000002",Role.DIVISION_ADMIN,"CZ");
      var op=user(store,root,"909000003",Role.OPERATOR,"WUJIN");
      String period=FoundationTest.candidate("multi","WUJIN","sample").period().key();
      for(String type:List.of("multi","negative","cross")){
        var own=FoundationTest.candidate(type,"WUJIN","DEADLINE-OWN-"+type);
        var other=FoundationTest.candidate(type,"JINTAN","DEADLINE-OTHER-"+type);
        store.importRows(div,type,List.of(own,other),false,UUID.randomUUID().toString());
      }
      var foreign=FoundationTest.candidate("multi","JINTAN","FOREIGN-PERIOD");var separate=xinguan.platform.Period.parse("20261001-20261015","");
      store.importRows(div,"multi",List.of(new BusinessRecord("",0,"multi",separate,"JINTAN",foreign.values(),"synthetic.xlsx",Instant.now().toString(),"",Map.of())),false,UUID.randomUUID().toString());
      store.deadlines().save(root,"multi",period,"2026-09-15",0);
      store.deadlines().save(div,"negative",period,"2099-12-31",0);
      store.deadlines().save(root,"multi",separate.key(),"2099-12-31",0);
      clock.now=Instant.parse("2026-09-17T02:00:00Z"); // Time passes after a valid future deadline was configured.
      var d=view(data,op);var session=new AuthService.Session("test","test",Instant.now().getEpochSecond(),store.sessionUser(op.userId()));session.safetyVersion=AccessPlatform.SAFETY_VERSION;
      var pages=new RiskPages("test",session);
      check(d.filtered("multi","","","overdue").size()==1&&d.filtered("negative","","","overdue").isEmpty()&&d.filtered("cross","","","overdue").isEmpty(),"late, future and unconfigured rows");
      String home=pages.dashboard(d);check(home.contains("本期反馈时间")&&home.contains("已超期")&&home.contains("剩余")&&home.contains("未设置")&&!home.contains("FOREIGN-PERIOD"),"home reminders scoped to own records");
      check(!home.contains("设置反馈截止日期"),"branch cannot see management shortcut");
      String settings=new FeedbackPages("test",session).settings(d,"");check(!settings.contains("/deadlines/save")&&!settings.contains(separate.key()),"branch settings read-only and foreign-only periods omitted");
      String detail=pages.details(d,BusinessFilter.from(op,Map.of("dataset","multi","period",period,"completion","overdue")),1,BusinessWorkflowState.empty());
      check(detail.contains("row-overdue")&&detail.contains("超期反馈")&&detail.contains("editable-cell")&&detail.contains("name=\"period\""),"late row marking and exact period retained");
      var service=new AuthorizedExportService(data);
      check(service.export(op,Map.of("dataset","multi","scope","year","year","2026","period",period,"completion","overdue")).count()==1,"overdue view and export agree");
      check(service.export(root,Map.of("dataset","multi","scope","year","year","2026","period",separate.key())).count()==1,"period filtering isolates similar source periods");
      var row=store.list(op,"multi",null,null).get(0);
      var draft=store.workflow().saveDraft(op,"",0,"multi",List.of(new RecordChange(row.id(),row.version(),Map.of("feedback","draft-only"))),"",UUID.randomUUID().toString());
      check(view(data,op).filtered("multi","","","overdue").size()==1,"draft not completed");
      store.workflow().confirm(div,store.workflow().previewDirect(div,"multi",List.of(new RecordChange(row.id(),row.version(),Map.of("feedback","正式反馈")))).id(),UUID.randomUUID().toString());
      var complete=view(data,op);check(complete.filtered("multi","","","overdue").isEmpty(),"completed row no longer overdue");
      check(pages.details(complete,new BusinessFilter("multi","","","all"),1,BusinessWorkflowState.empty()).contains("row-complete"),"completed stays green even after deadline");
      check(service.export(op,Map.of("dataset","multi","scope","year","year","2026","completion","overdue")).count()==0,"completed excluded from overdue export");
      store.deadlines().save(root,"multi",period,"2099-12-31",1);check(view(data,root).filtered("multi","","","overdue").isEmpty(),"extension removes overdue on other unfinished branch");
      store.deadlines().save(root,"multi",period,"",2);check(view(data,root).records.stream().filter(r->r.dataset.equals("multi")&&r.period.equals(period)).allMatch(r->r.feedbackDeadline==null),"clearing removes countdown without business edits");
    }
    System.out.println("FEEDBACK_VIEW_OK assertions="+assertions+" real scoped data, period filters, draft isolation, completion and exports");
  }
  static final class TestClock extends Clock {
    Instant now=Instant.parse("2026-09-14T02:00:00Z");
    public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
  }
  static DashboardData view(DataStore data,ActorContext actor){var months=data.months(actor);var range=RangeSelection.from(Map.of("scope","year","year","2026"),months);return new DashboardData(range,months,List.of(),data.readRange(range,actor));}
  static ActorContext user(PlatformStore store,ActorContext root,String number,Role role,String org){var u=store.createUser(root,number,"虚构日期测试",role,org);store.changeOwnPassword(u.user().actor(),"synthetic-"+number);return store.sessionUser(u.user().id()).actor();}
  static void check(boolean v,String label){assertions++;if(!v)throw new AssertionError(label);}
}

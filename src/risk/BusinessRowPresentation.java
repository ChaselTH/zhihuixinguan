import java.time.YearMonth;
import xinguan.platform.*;
import xinguan.platform.WorkflowContracts.*;

final class BusinessRowPresentation extends PageLayout {
  BusinessRowPresentation(AuthService.Session session){super("",session);}
  String status(RowRef row,BusinessWorkflowState states){
    var state=states.get(row.record.id);boolean complete=DatasetSchema.get(row.record.dataset).complete(row.values);
    StringBuilder b=new StringBuilder("<span class=\"business-badge ").append(complete?"complete":"incomplete").append("\">").append(complete?"✓ 正式已完成":"! 正式未完成").append("</span>");
    if(state.draft()!=null)b.append("<span class=\"business-badge draft\">我的草稿").append(state.stale()?"（需核对版本）":"").append("</span>");
    if(state.pending()!=null)b.append("<a class=\"business-badge reviewing\" href=\"/workflow/submission?id=").append(u(state.pending().id())).append("\">待复核</a>");
    else if(state.latest()!=null)b.append("<a class=\"business-badge history\" href=\"/workflow/submission?id=").append(u(state.latest().id())).append("\">最近提交：").append(state.latest().state()==State.RETURNED?"已退回":state.latest().mode()==Mode.DIRECT?"直接生效":"已通过").append("</a>");
    return b.toString();
  }
  String actions(RowRef row,BusinessWorkflowState states,RangeSelection range){
    StringBuilder b=new StringBuilder();var state=states.get(row.record.id);var actor=currentSession.actor;
    if(actor.role()!=Role.SUPER_ADMIN&&Organizations.BRANCHES.containsKey(row.record.organizationId)){
      if(state.pending()==null){String href="/workflow/edit?dataset="+u(row.record.dataset)+"&organization="+u(row.record.organizationId)+"&record="+u(row.record.id)+"&from="+YearMonth.parse(range.start).atDay(1)+"&through="+YearMonth.parse(range.end).atEndOfMonth();
        if(state.draft()!=null)href+="&draft="+u(state.draft().id());
        b.append("<a href=\"").append(e(href)).append("\">").append(state.draft()!=null?"继续我的草稿":actor.role()==Role.OPERATOR?"填写并提交":"修改并确认").append("</a> ");
      }else b.append("<a href=\"/workflow/submission?id=").append(u(state.pending().id())).append("\">").append(actor.role()==Role.REVIEWER?"去复核":"查看待复核单").append("</a> ");
    }
    b.append("<a href=\"/records/history?id=").append(u(row.record.id)).append("\">查看追溯</a>");return b.toString();
  }
}

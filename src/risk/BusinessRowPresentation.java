import java.time.YearMonth;
import xinguan.platform.*;
import xinguan.platform.WorkflowContracts.*;

final class BusinessRowPresentation extends PageLayout {
  BusinessRowPresentation(AuthService.Session session){super("",session);}
  String status(RowRef row,BusinessWorkflowState states){
    var state=states.get(row.record.id);boolean complete=row.complete();
    StringBuilder b=new StringBuilder("<span class=\"business-badge ").append(complete?"complete":row.overdue()?"overdue":"incomplete").append("\">").append(complete?"✓ 正式已完成":row.overdue()?"! 超期反馈":"! 正式未完成").append("</span>");
    if(!complete&&!row.record.requiredFields.isEmpty()){
      var schema=DatasetSchema.get(row.record.dataset);var missing=schema.fields.stream().filter(f->row.record.requiredFields.contains(f.key())&&schema.value(row.values,schema.index(f.key())).isBlank()).map(DatasetSchema.Field::title).toList();
      if(!missing.isEmpty())b.append("<small class=\"required-missing\" title=\"").append(e(String.join("；",missing))).append("\">正式值缺少 ").append(missing.size()).append(" 项必填，仍可提交</small>");
    }
    if(row.record.feedbackDeadline!=null)b.append("<small class=\"feedback-due\">截止：").append(e(row.record.feedbackDeadline.toString())).append(complete?"":" · "+e(FeedbackTiming.remaining(row.record.feedbackDeadline,row.record.feedbackAsOf))).append("</small>");
    if(state.draft()!=null)b.append("<span class=\"business-badge draft\">我的草稿").append(state.stale()?"（需核对版本）":"").append("</span>");
    b.append("<span class=\"business-badge workflow-stage\">").append(stage(row.record.workflowStage)).append("</span>");
    if(row.record.workflowStage==RowStage.RETURNED&&!row.record.workflowReason.isBlank())b.append("<small class=\"workflow-return-reason\">退回原因：").append(e(row.record.workflowReason)).append("</small>");
    Submission latest=state.pending()!=null?state.pending():state.latest();
    if(latest!=null&&(row.record.workflowStage==RowStage.BRANCH_REVIEW||row.record.workflowStage==RowStage.DIVISION_REVIEW||row.record.workflowStage==RowStage.RETURNED))b.append("<a class=\"business-badge history\" href=\"/workflow/submission?id=").append(u(latest.id())).append("\">查看提交回执").append(row.record.workflowStage==RowStage.RETURNED&&!latest.reason().isBlank()?" · "+e(latest.reason()):"").append("</a>");
    return b.toString();
  }
  private static String stage(RowStage stage){return switch(stage){case READY->"待支行处理";case BRANCH_REVIEW->"待支行复核 · 只读";case DIVISION_REVIEW->"待分行终审";case RETURNED->"退回待修改";case PUBLISHED->"终审已发布";case LEGACY_PUBLISHED->"历史正式数据";};}
  String actions(RowRef row,BusinessWorkflowState states,RangeSelection range){
    StringBuilder b=new StringBuilder();var state=states.get(row.record.id);var actor=currentSession.actor;
    // Editing and trace are now page-level actions. Keep only the necessary review
    // queue link here; never expose another branch's row or private draft contents.
    if(state.pending()!=null)b.append("<a href=\"/workflow/submission?id=").append(u(state.pending().id())).append("\">").append(actor.role()==Role.REVIEWER?"去复核":"查看待复核单").append("</a>");
    if(actor.role()==Role.DIVISION_ADMIN&&(row.record.workflowStage==RowStage.PUBLISHED||row.record.workflowStage==RowStage.LEGACY_PUBLISHED)&&row.complete())b.append("<a href=\"/workflow/reopen?record=").append(u(row.record.id)).append("\">终审退回修改</a>");
    if(actor.role()!=Role.SUPER_ADMIN&&row.record.workflowStage==RowStage.RETURNED)b.append("<a href=\"/workflow/reconfirm?record=").append(u(row.record.id)).append("\">核对后原值重提</a>");
    return b.toString();
  }
}

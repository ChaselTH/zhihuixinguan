import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import xinguan.platform.*;
import xinguan.platform.WorkflowContracts.*;

/** Server-rendered workflow views. Core actions remain ordinary HTML forms. */
final class WorkflowPages extends PageLayout {
  private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
  private final AuthService.Session session;
  WorkflowPages(String version,AuthService.Session session){super(version,session);this.session=session;}

  String home(List<Draft> drafts,List<Submission> submissions,List<Submission> pending,String notice) {
    ActorContext actor=session.actor;StringBuilder b=new StringBuilder(message(notice,false));
    b.append("<div class=\"workflow-hero clearfix\"><div><span class=\"workflow-eyebrow\">WORKFLOW DESK</span><h1>工作流工作台</h1><p>").append(e(roleGuide(actor.role()))).append("</p></div>");
    if(actor.role()==Role.OPERATOR)b.append("<a class=\"btn btn-primary\" href=\"/details?dataset=multi\">开始或继续填报</a>");
    else if(canDirect(actor))b.append("<a class=\"btn btn-primary\" href=\"/details?dataset=multi\">预览直接修改</a>");
    b.append("</div><div class=\"workflow-metrics clearfix\">")
      .append(metric("最近草稿",drafts.size(),actor.role()==Role.OPERATOR?"最多显示 10 条；全部草稿见列表":"当前角色不使用草稿"))
      .append(metric("近期提交",submissions.size(),"正式快照与状态"))
      .append(metric(actor.role()==Role.DIVISION_ADMIN?"近期分行终审待办":"近期复核待办",pending.size(),actor.role()==Role.REVIEWER||actor.role()==Role.DIVISION_ADMIN?"打开审核待办查看待处理内容":"当前角色无需审核"))
      .append("</div>");
    if(actor.role()==Role.OPERATOR)b.append(draftPanel(drafts));
    if(actor.role()==Role.REVIEWER||actor.role()==Role.DIVISION_ADMIN)b.append(submissionPanel(actor.role()==Role.REVIEWER?"本支行复核待办":"分行终审待办",pending,true));
    b.append(submissionPanel(actor.role()==Role.OPERATOR?"我的提交":"近期提交",submissions,false));
    return shell("工作流工作台","home",b.toString());
  }

  String editor(String dataset,String organization,LocalDate from,LocalDate through,int page,int pages,int total,List<BusinessRecord> rows,Draft draft,String prior,String notice) {
    return editor(dataset,organization,from,through,page,pages,total,rows,draft,prior,notice,"");
  }
  String editor(String dataset,String organization,LocalDate from,LocalDate through,int page,int pages,int total,List<BusinessRecord> rows,Draft draft,String prior,String notice,String focus) {
    if(draft!=null)return draftEditor(draft,rows,page,pages,total,prior,notice);
    DatasetSchema schema=DatasetSchema.get(dataset);boolean draftMode=session.actor.role()==Role.OPERATOR;
    Map<String,SnapshotRow> saved=new LinkedHashMap<>();if(draft!=null)for(SnapshotRow row:draft.rows())saved.put(row.before().id(),row);
    StringBuilder b=new StringBuilder(message(notice,false));
    b.append("<div class=\"workflow-title clearfix\"><div><span class=\"workflow-eyebrow\">").append(draftMode?"PRIVATE DRAFT":"DIRECT CHANGE").append("</span><h1>").append(draftMode?"填报草稿":"本人直接修改").append(" · ").append(e(schema.label)).append("</h1><p>")
      .append(draftMode?"草稿正文仅本人可见；保存的是完整差异集合，切换分页不会丢失已保存修改。":"确认前只生成服务端差异；正式值尚未改变。").append("</p></div>");
    if(draft!=null)b.append("<div class=\"workflow-version\"><span>草稿版本</span><strong>").append(draft.version()).append("</strong><small>").append(saved.size()).append(" 条差异记录</small></div>");
    b.append("</div>").append(editorFilter(dataset,organization,from,through,draft,prior));
    if(!focus.isEmpty())b.append("<p class=\"workflow-callout\">当前定位单条记录；保存仍保留草稿内其他记录。上方筛选可切回整期列表。</p>");
    if(organization.isEmpty()){b.append(empty("请选择支行后查看可编辑正式记录。"));return shell("工作流编辑","edit",b.toString());}
    if(!prior.isEmpty())b.append("<div class=\"workflow-callout warning\">本草稿将关联已退回单 <a href=\"/workflow/submission?id=").append(u(prior)).append("\">").append(e(shortId(prior))).append("</a>，重新提交会创建新单并保留旧单历史。</div>");
    b.append("<form class=\"workflow-edit-form\" method=\"post\" action=\"").append(draftMode?"/workflow/draft/save":"/workflow/direct/preview").append("\">")
      .append(hidden("csrf",session.csrf)).append(hidden("requestId",UUID.randomUUID().toString())).append(hidden("dataset",dataset)).append(hidden("organization",organization))
      .append(hidden("from",from==null?"":from.toString())).append(hidden("through",through==null?"":through.toString())).append(hidden("page",Integer.toString(page))).append(hidden("rows",Integer.toString(rows.size())))
      .append(hidden("draftId",draft==null?"":draft.id())).append(hidden("draftVersion",draft==null?"0":Long.toString(draft.version()))).append(hidden("priorSubmissionId",prior)).append(hidden("record",focus));
    if(!rows.isEmpty())b.append(actionBar(draftMode));
    b.append("<div class=\"workflow-table-scroll\"><table class=\"workflow-table workflow-edit-table\"><thead><tr><th>企业／客户</th><th>客户编码</th><th>机构</th><th>来源期次</th>");
    for(DatasetSchema.Field field:schema.fields)if(field.editable())b.append("<th class=\"workflow-editable-head\">").append(e(field.title())).append("</th>");
    b.append("</tr></thead><tbody>");
    for(int i=0;i<rows.size();i++) {
      BusinessRecord row=rows.get(i);SnapshotRow savedRow=saved.get(row.id());long expected=savedRow==null?row.version():savedRow.change().expectedVersion();boolean stale=expected!=row.version();
      b.append("<tr").append(stale?" class=\"workflow-stale\"":"").append("><td class=\"workflow-company\">").append(hidden("id"+i,row.id())).append(hidden("version"+i,Long.toString(expected))).append(e(schema.value(row.values(),schema.customerColumn)));
      if(stale)b.append("<span class=\"workflow-row-warning\">正式记录已变化，保存时将要求重新核对</span>");
      b.append("</td><td>").append(e(schema.value(row.values(),schema.codeColumn))).append("</td><td>").append(e(Organizations.label(row.organizationId()))).append("</td><td>").append(e(row.period().key())).append("</td>");
      for(DatasetSchema.Field field:schema.fields)if(field.editable()) {
        String value=fieldValue(schema,row,savedRow,field);String name="value_"+i+"_"+field.key();b.append("<td class=\"workflow-editable-cell\">");
        if(field.options().isEmpty())b.append("<textarea rows=\"4\" name=\"").append(e(name)).append("\">").append(e(value)).append("</textarea>");
        else {b.append("<select name=\"").append(e(name)).append("\">").append(option("","请选择",value));if(!value.isBlank()&&!field.options().contains(value))b.append(option(value,value+"（历史值）",value));for(String choice:field.options())b.append(option(choice,choice,value));b.append("</select>");}
        b.append("</td>");
      }
      b.append("</tr>");
    }
    if(rows.isEmpty())b.append("<tr><td class=\"workflow-empty-cell\" colspan=\"").append(4+editableCount(schema)).append("\">当前筛选范围没有正式记录</td></tr>");
    b.append("</tbody></table></div></form>");
    b.append(pager(editLink(dataset,organization,from,through,draft==null?"":draft.id(),prior),page,pages,total));
    return shell(draftMode?"填报草稿":"直接修改","edit",b.toString());
  }

  String drafts(List<Draft> rows,String dataset,int page) {
    StringBuilder b=new StringBuilder("<h1>我的全部草稿</h1><p>草稿正文仅本人可见。按表种筛选后可翻页恢复历史草稿。</p><form class=\"workflow-filter\" method=\"get\" action=\"/workflow/drafts\"><label>表种<select name=\"dataset\">").append(option("","全部表种",dataset));
    for(var schema:DatasetSchema.all())b.append(option(schema.id,schema.label,dataset));
    b.append("</select></label><button class=\"btn btn-dark\" type=\"submit\">查看</button></form>").append(draftPanel(rows));
    b.append(pager("/workflow/drafts?dataset="+u(dataset),page,page+(rows.size()==30?1:0),-1));
    return shell("我的全部草稿","drafts",b.toString());
  }

  String preview(Preview preview,String notice) {
    return preview(preview,notice,"");
  }
  String preview(Preview preview,String notice,String returnUrl) {
    DatasetSchema schema=DatasetSchema.get(preview.dataset());int changes=countChanges(preview.rows());StringBuilder b=new StringBuilder(message(notice,false));
    b.append("<div class=\"workflow-title clearfix\"><div><span class=\"workflow-eyebrow\">SERVER PREVIEW</span><h1>确认本次修改</h1><p>差异由服务端根据正式记录生成；确认只提交预览编号，不回传替换内容。</p></div><div class=\"workflow-version\"><span>变更字段</span><strong>").append(changes).append("</strong><small>").append(preview.rows().size()).append(" 条记录</small></div></div>");
    b.append("<div class=\"workflow-callout warning\"><strong>确认有效期至 ").append(e(dateTime(preview.expiresAt()))).append("</strong><br>预览不会创建待办；正式值仍未改变。草稿或正式记录发生变化后必须重新预览。</div>");
    b.append(diffTable(preview.rows()));
    b.append("<form class=\"workflow-confirm\" method=\"post\" action=\"/workflow/confirm\">").append(hidden("csrf",session.csrf)).append(hidden("previewId",preview.id())).append(hidden("requestId",requestId("confirm",preview.id())))
      .append("<div><strong>").append(preview.mode()==Mode.REVIEW?"提交复核":session.actor.role()==Role.DIVISION_ADMIN?"分行终审并发布":"提交分行终审").append("</strong><p>").append(preview.mode()==Mode.REVIEW?"确认后生成不可变提交单，等待本支行复核员处理。":session.actor.role()==Role.DIVISION_ADMIN?"分行管理员确认后正式值、审计和通知将在同一事务中发布。":"支行修改将作为不可变快照送分行终审，确认时不改正式值。").append("</p></div><button class=\"btn btn-primary\" type=\"submit\">确认").append(preview.mode()==Mode.REVIEW?"提交复核":session.actor.role()==Role.DIVISION_ADMIN?"并发布":"并送分行终审").append("</button></form>");
    String back=!returnUrl.isEmpty()?returnUrl:preview.mode()==Mode.REVIEW?"/workflow/edit?dataset="+u(preview.dataset())+"&draft="+u(preview.draftId()):"/details?dataset="+u(preview.dataset());
    b.append("<p class=\"workflow-back\"><a ").append(preview.mode()!=Mode.REVIEW?"data-back=\"yes\" ":"").append("href=\"").append(e(back)).append("\">返回修改</a></p>");
    return shell("确认修改","preview",b.toString());
  }

  String submissions(List<Submission> rows,String dataset,String organization,State state,LocalDate from,LocalDate through,boolean mine,int page,boolean pending,String notice) {
    String pendingTitle=session.actor.role()==Role.DIVISION_ADMIN?"分行终审待办":"本支行复核待办";
    StringBuilder b=new StringBuilder(message(notice,false));b.append("<div class=\"workflow-title\"><span class=\"workflow-eyebrow\">").append(pending?"REVIEW QUEUE":"SUBMISSION LEDGER").append("</span><h1>").append(pending?pendingTitle:mine?"我的提交":"提交记录").append("</h1><p>").append(pending?"通知已读不会移除待办；每张单据必须明确处理。":"列表只展示当前账号获准查看的机构范围。").append("</p></div>");
    b.append(submissionFilter(dataset,organization,state,from,through,mine,pending));
    b.append(submissionPanel(pending?"待处理单据":"查询结果",rows,pending));
    if(page>1||rows.size()==30)b.append(pager(submissionLink(dataset,organization,state,from,through,mine,pending),page,page+(rows.size()==30?1:0),-1));
    return shell(pending?"复核待办":"提交记录",pending?"reviews":"submissions",b.toString());
  }

  String submission(Submission submission,String notice) {
    StringBuilder b=new StringBuilder(message(notice,false));
    b.append("<div class=\"workflow-title clearfix\"><div><span class=\"workflow-eyebrow\">IMMUTABLE SUBMISSION</span><h1>").append(e(DatasetSchema.get(submission.dataset()).label)).append(" · ").append(e(stateLabel(submission.state()))).append("</h1><p>单号 ").append(e(submission.id())).append(" · ").append(e(Organizations.label(submission.organizationId()))).append(" · ").append(e(dateTime(submission.createdAt()))).append("</p></div><span class=\"workflow-status ").append(stateClass(submission.state())).append("\">").append(e(stateLabel(submission.state()))).append("</span></div>");
    b.append("<div class=\"workflow-summary clearfix\"><div><span>提交人</span><strong>").append(e(submission.ownerName())).append("</strong></div><div><span>处理方式</span><strong>").append(submission.mode()==Mode.REVIEW?"操作员两级审核":submission.mode()==Mode.IMPORT?"导入候选：支行确认后分行终审":submission.state()==State.PENDING_DIVISION?"支行修改送分行终审":session.actor.role()==Role.DIVISION_ADMIN?"分行终审发布":"分行管理员修改").append("</strong></div><div><span>记录／字段</span><strong>").append(submission.rowStages().size()).append(" / ").append(countChanges(submission.rows())).append("</strong></div><div><span>最近处理人</span><strong>").append(e(submission.reviewerName().isBlank()?"尚未处理":submission.reviewerName())).append("</strong></div></div>");
    if(submission.rowStages().containsValue(RowStage.RETURNED)&&!submission.reason().isBlank())b.append("<div class=\"workflow-callout error\"><strong>最近退回原因（仅对应本次处理行）</strong><br>").append(e(submission.reason())).append("</div>");
    if(!submission.priorSubmissionId().isEmpty())b.append("<div class=\"workflow-callout\">本单由已退回单 <a href=\"/workflow/submission?id=").append(u(submission.priorSubmissionId())).append("\">").append(e(shortId(submission.priorSubmissionId()))).append("</a> 修订后重新提交。</div>");
    b.append(submissionItems(submission));
    if(submission.ownerId().equals(session.actor.userId())&&!submission.draftId().isEmpty()&&submission.rows().stream().anyMatch(r->submission.rowStages().get(r.before().id())==RowStage.RETURNED))b.append("<div class=\"workflow-next\"><a class=\"btn btn-primary\" href=\"/workflow/edit?dataset=").append(u(submission.dataset())).append("&amp;draft=").append(u(submission.draftId())).append("&amp;prior=").append(u(submission.id())).append("\">恢复草稿并修订</a></div>");
    if(submission.state()!=State.SUBMITTED)b.append("<p class=\"workflow-audit-link\"><a href=\"/audit?submissionId=").append(u(submission.id())).append("\">查看公共审计记录</a> <span>（按当前账号权限查询）</span></p>");
    return shell("提交详情","submission",b.toString());
  }

  String rejectReason(Submission submission,List<String> recordIds,String requestId) {
    StringBuilder b=new StringBuilder("<div class=\"workflow-title\"><h1>填写退回原因</h1><p>将退回 ").append(recordIds.size()).append(" 行 · ").append(e(DatasetSchema.get(submission.dataset()).label)).append("。原因会通知下一处理人并留在历史记录中。</p></div>");
    b.append("<form method=\"post\" action=\"/workflow/review/reject\" class=\"workflow-reason-form\">").append(hidden("csrf",session.csrf)).append(hidden("submissionId",submission.id())).append(hidden("recordIds",String.join(",",recordIds))).append(hidden("requestId",requestId))
      .append("<label for=\"review-reason\">退回原因（必填）</label><textarea id=\"review-reason\" name=\"reason\" rows=\"5\" maxlength=\"2000\" required=\"required\"></textarea><div class=\"workflow-reason-actions\"><button class=\"btn btn-primary\" type=\"submit\">确认退回</button><a class=\"btn btn-light\" href=\"/workflow/submission?id=").append(u(submission.id())).append("\">取消</a></div></form>");
    return shell("填写退回原因","submission",b.toString());
  }

  String reopen(BusinessRecord row,String notice) {
    DatasetSchema schema=DatasetSchema.get(row.dataset());String customer=schema.value(row.values(),schema.customerColumn);StringBuilder b=new StringBuilder(message(notice,false));
    b.append("<div class=\"workflow-title clearfix\"><h1>终审后退回修改 · ").append(e(schema.label)).append("</h1><p>").append(e(customer)).append(" · ").append(e(row.period().key())).append(" · 正式版本 ").append(row.version()).append("</p></div>");
    if(row.workflowStage()==RowStage.RETURNED)b.append("<div class=\"workflow-callout warning\"><strong>该行已重新开放为支行待处理。</strong><p>退回原因：").append(e(row.workflowReason())).append("</p><p>原正式值与版本保持不变；支行完成修订并按新流程送审后才会重新计算完成。</p></div><p><a class=\"btn btn-primary\" href=\"/details?dataset=").append(u(row.dataset())).append("&amp;month=").append(u(java.time.YearMonth.from(row.period().start()).toString())).append("&amp;q=").append(u(customer)).append("\">返回业务待处理清单</a></p>");
    else {
      b.append("<div class=\"workflow-callout warning\"><strong>正式值尚未改变。</strong><p>确认后只重新打开这一行；当前正式值及版本保留供核对，并从正式完成数中扣除。退回理由会记录到审计和行级历史。</p></div>")
        .append("<form method=\"post\" action=\"/workflow/reopen\" class=\"workflow-review-card reject\">").append(hidden("csrf",session.csrf)).append(hidden("recordId",row.id())).append(hidden("expectedVersion",""+row.version())).append(hidden("requestId",UUID.randomUUID().toString()))
        .append("<label>终审退回原因（必填）<textarea name=\"reason\" rows=\"4\" maxlength=\"2000\" required=\"required\"></textarea></label><label class=\"workflow-check\"><input type=\"checkbox\" name=\"confirm\" value=\"yes\" required=\"required\">确认只退回此行；原正式值不清空</label><button class=\"btn btn-light\" type=\"submit\">确认退回支行</button></form>");
    }
    return shell("终审后退回修改","submission",b.toString());
  }

  String reconfirm(BusinessRecord row) {
    DatasetSchema schema=DatasetSchema.get(row.dataset());StringBuilder b=new StringBuilder("<h1>核对后原值重新提交</h1><p>").append(e(schema.value(row.values(),schema.customerColumn))).append(" · ").append(e(row.period().key())).append("</p><p>退回原因：").append(e(row.workflowReason())).append("</p>");
    b.append("<p>仅在核实原内容正确、无需改字时使用。下一步仍需确认，之后生成新一轮审核；当前正式值和版本保持不变。</p><table class=\"workflow-table\"><thead><tr><th>填报字段</th><th>保留的正式值</th></tr></thead><tbody>");
    for(var field:schema.fields)if(field.editable())b.append("<tr><td>").append(e(field.title())).append("</td><td>").append(e(blankLabel(schema.value(row.values(),schema.index(field.key()))))).append("</td></tr>");
    b.append("</tbody></table><form method=\"post\" action=\"/workflow/reconfirm\">").append(hidden("csrf",session.csrf)).append(hidden("recordId",row.id())).append(hidden("expectedVersion",""+row.version())).append("<label><input type=\"checkbox\" name=\"confirm\" value=\"yes\" required=\"required\">我已核对原内容，确认无需修改</label><button class=\"btn btn-primary\" type=\"submit\">预览原值重提</button></form>");
    return shell("核对后原值重提","submission",b.toString());
  }

  String recordHistory(BusinessRecord row,List<ItemEvent> events,List<Submission> submissions) {
    DatasetSchema schema=DatasetSchema.get(row.dataset());
    StringBuilder b=new StringBuilder("<div class=\"workflow-title clearfix\"><div><span class=\"workflow-eyebrow\">ROW HISTORY</span><h1>历史修改记录 · ").append(e(schema.label)).append("</h1><p>").append(e(schema.value(row.values(),schema.customerColumn))).append(" · ").append(e(Organizations.label(row.organizationId()))).append(" · ").append(e(row.period().key())).append(" · 当前阶段 ").append(e(stageLabel(row.workflowStage()))).append("</p></div></div>");
    b.append("<p><a class=\"btn btn-light\" href=\"/details?dataset=").append(u(row.dataset())).append("\">返回业务清单</a></p>");
    b.append("<section class=\"workflow-panel\"><div class=\"workflow-panel-head clearfix\"><div><span>OPERATION TIMELINE</span><h2>填报／复核／退回／发布记录</h2></div></div>");
    if(events.isEmpty())b.append(empty("该行暂无流程操作记录。"));
    else {
      b.append("<div class=\"workflow-table-scroll\"><table class=\"workflow-table\"><thead><tr><th>时间</th><th>操作</th><th>阶段</th><th>操作人</th><th>角色</th><th>说明／原因</th><th>单据</th></tr></thead><tbody>");
      for(ItemEvent event:events){
        b.append("<tr><td>").append(e(dateTime(event.at()))).append("</td><td>").append(e(actionLabel(event.action()))).append("</td><td>").append(e(stageLabelSafe(event.stage()))).append("</td><td>").append(e(event.actorName())).append("</td><td>").append(e(roleLabel(event.actorRole()))).append("</td><td>").append(e(event.reason())).append("</td><td>");
        if(event.submissionId().isEmpty())b.append("—");else b.append("<a href=\"/workflow/submission?id=").append(u(event.submissionId())).append("\">查看单据</a>");
        b.append("</td></tr>");
      }
      b.append("</tbody></table></div>");
    }
    b.append("</section><section class=\"workflow-panel\"><div class=\"workflow-panel-head clearfix\"><div><span>SUBMISSIONS</span><h2>相关提交单</h2></div></div>");
    if(submissions.isEmpty())b.append(empty("该行暂无提交单。"));
    else {
      b.append("<div class=\"workflow-table-scroll\"><table class=\"workflow-table\"><thead><tr><th>提交时间</th><th>提交人</th><th>状态</th><th>最近处理人</th><th>处理时间</th><th>说明</th><th>单据</th></tr></thead><tbody>");
      for(Submission s:submissions)b.append("<tr><td>").append(e(dateTime(s.createdAt()))).append("</td><td>").append(e(s.ownerName())).append("</td><td>").append(e(stateLabel(s.state()))).append("</td><td>").append(e(s.reviewerName().isBlank()?"—":s.reviewerName())).append("</td><td>").append(e(s.decidedAt()==null?"—":dateTime(s.decidedAt()))).append("</td><td>").append(e(s.reason())).append("</td><td><a href=\"/workflow/submission?id=").append(u(s.id())).append("\">查看单据</a></td></tr>");
      b.append("</tbody></table></div>");
    }
    return shell("历史修改记录","",b.append("</section>").toString());
  }
  private static String stageLabelSafe(String stage){try{return stageLabel(RowStage.valueOf(stage));}catch(RuntimeException ex){return stage;}}
  private static String roleLabel(String role){try{return PageLayout.roleName(Role.valueOf(role));}catch(RuntimeException ex){return role;}}
  private static String actionLabel(String action){return switch(action){case "SUBMITTED"->"提交复核";case "BRANCH_APPROVED"->"支行复核通过";case "BRANCH_RETURNED"->"支行退回修改";case "DIVISION_APPROVED"->"分行终审通过并发布";case "DIVISION_RETURNED"->"分行退回";case "DIVISION_REOPENED"->"终审后退回修改";case "DIRECT_SUBMIT"->"直接提交";case "DIRECT_SENT_TO_DIVISION"->"直接修改送分行终审";case "DIRECT_PUBLISHED"->"直接修改并发布";case "IMPORT_SUBMITTED"->"导入提交复核";case "IMPORT_READY"->"导入待处理";case "LEGACY_MIGRATION"->"历史迁入";case "DRAFT_SAVE"->"保存草稿";case "DRAFT_RESTORE_RETURNED"->"恢复退回草稿";default->action;};}

  String problem(int status,String message,List<Conflict> conflicts) {
    StringBuilder b=new StringBuilder("<div class=\"workflow-problem\"><span>").append(status).append("</span><h1>操作未完成</h1><p>").append(e(message==null?"请刷新后重试":message)).append("</p>");
    if(conflicts!=null&&!conflicts.isEmpty()) {
      b.append("<div class=\"workflow-table-scroll\"><table class=\"workflow-table\"><thead><tr><th>企业／客户</th><th>字段</th><th>原基线</th><th>当前正式值</th><th>拟提交值</th></tr></thead><tbody>");
      for(Conflict conflict:conflicts){BusinessRecord current=conflict.current();DatasetSchema schema=DatasetSchema.get(current.dataset());BusinessRecord base=conflict.base();for(var entry:conflict.proposed().values().entrySet()){int index=schema.index(entry.getKey());b.append("<tr><td>").append(e(schema.value(current.values(),schema.customerColumn))).append("</td><td>").append(e(index>=0?schema.fields.get(index).title():entry.getKey())).append("</td><td>").append(e(base==null?"未保存基线":schema.value(base.values(),index))).append("</td><td>").append(e(schema.value(current.values(),index))).append("</td><td>").append(e(entry.getValue())).append("</td></tr>");}}
      b.append("</tbody></table></div>");
    }
    b.append("<p><a class=\"btn btn-light\" href=\"/workflow\">返回工作台</a></p></div>");return shell("操作未完成","",b.toString());
  }

  private String shell(String title,String active,String body) {
    StringBuilder content=new StringBuilder(header()).append("<div class=\"workflow-shell\"><div class=\"workflow-nav clearfix\"><div><strong>工作流</strong><span>").append(e(PageLayout.roleName(session.actor.role()))).append(" · ").append(e(Organizations.label(session.actor.organizationId()))).append("</span></div><div class=\"workflow-tabs\">")
      .append(tab("/workflow","工作台","home",active));
    if(session.actor.role()==Role.OPERATOR||canDirect(session.actor))content.append(tab("/details?dataset=multi",session.actor.role()==Role.OPERATOR?"草稿填报":"直接修改","edit",active));
    if(session.actor.role()==Role.OPERATOR)content.append(tab("/workflow/drafts","全部草稿","drafts",active));
    content.append(tab("/workflow/submissions",session.actor.role()==Role.OPERATOR?"我的提交":"提交记录","submissions",active));
    if(session.actor.role()==Role.REVIEWER||session.actor.role()==Role.DIVISION_ADMIN)content.append(tab("/workflow/reviews",session.actor.role()==Role.REVIEWER?"复核待办":"分行终审","reviews",active));
    content.append("</div></div>");if(!active.equals("home"))content.append(backButton("/workflow"));content.append(body).append("</div>");
    String html=page(title,content.toString());return html.replace("<link rel=\"stylesheet\" href=\"/assets/foundation.css\">","<link rel=\"stylesheet\" href=\"/assets/foundation.css\"><link rel=\"stylesheet\" href=\"/assets/workflow.css\"><script src=\"/assets/workflow.js\"></script>");
  }

  private String draftEditor(Draft draft,List<BusinessRecord> rows,int page,int pages,int total,String prior,String notice){
    DatasetSchema schema=DatasetSchema.get(draft.dataset());Map<String,SnapshotRow> saved=new HashMap<>();for(var row:draft.rows())saved.put(row.before().id(),row);
    StringBuilder b=new StringBuilder(message(notice,false)).append("<div class=\"workflow-title clearfix\"><h1>我的修改 · ").append(e(schema.label)).append("</h1><p>仅显示本人修改过的行和字段；正式统计不含草稿。版本 ").append(draft.version()).append(" · 全部 ").append(total).append(" 条差异</p></div>");
    b.append("<p><a class=\"btn btn-light\" href=\"/details?dataset=").append(u(draft.dataset())).append("&amp;draft=").append(u(draft.id())).append("\">返回完整清单继续填写</a></p>");
    if(!prior.isEmpty())b.append("<p class=\"workflow-callout warning\">由退回单修订，重新提交会保留原单历史。</p>");
    b.append("<form class=\"workflow-edit-form\" method=\"post\" action=\"/workflow/draft/save\">").append(hidden("csrf",session.csrf)).append(hidden("requestId",UUID.randomUUID().toString()))
      .append(hidden("dataset",draft.dataset())).append(hidden("organization",draft.organizationId())).append(hidden("page",""+page)).append(hidden("rows",""+rows.size()))
      .append(hidden("draftId",draft.id())).append(hidden("draftVersion",""+draft.version())).append(hidden("priorSubmissionId",prior)).append(hidden("fieldsMode","differences"));
    if(!rows.isEmpty())b.append(actionBar(true));
    b.append("<div class=\"table-scroll workflow-table-scroll\"><table class=\"workflow-table workflow-draft-diff\"><thead><tr><th>企业／来源时间</th><th>修改字段</th><th>原正式值</th><th>我的草稿</th></tr></thead><tbody>");
    for(int i=0;i<rows.size();i++){
      BusinessRecord current=rows.get(i);SnapshotRow row=saved.get(current.id());boolean first=true;
      for(FieldDiff diff:confirmationFields(row)){
        var field=schema.fields.get(schema.index(diff.key()));b.append("<tr><td class=\"workflow-company\">");
        if(first){b.append(hidden("id"+i,current.id())).append(hidden("version"+i,""+row.change().expectedVersion()));first=false;}
        b.append(e(schema.value(current.values(),schema.customerColumn))).append("<br><small>").append(e(row.before().period().key())).append("</small>");
        if(current.version()!=row.change().expectedVersion())b.append("<span class=\"workflow-row-warning\">正式记录已变化，请核对版本</span>");
        if(row.fields().isEmpty())b.append("<br><a href=\"/workflow/reconfirm?record=").append(u(current.id())).append("\">内容无需修改，重新核对原值</a>");
        b.append("</td><td>").append(e(diff.title())).append("</td><td class=\"workflow-before\">").append(e(blankLabel(diff.before()))).append("</td><td class=\"workflow-editable-cell\">");
        String name="value_"+i+"_"+diff.key();
        if(field.options().isEmpty())b.append("<textarea rows=\"3\" name=\"").append(e(name)).append("\">").append(e(diff.after())).append("</textarea>");
        else{b.append("<select name=\"").append(e(name)).append("\">").append(option("","请选择",diff.after()));if(!diff.after().isBlank()&&!field.options().contains(diff.after()))b.append(option(diff.after(),diff.after()+"（历史值）",diff.after()));for(String choice:field.options())b.append(option(choice,choice,diff.after()));b.append("</select>");}
        b.append("</td></tr>");
      }
    }
    if(rows.isEmpty())b.append("<tr><td colspan=\"4\" class=\"workflow-empty-cell\">没有未提交差异，已从活动草稿列表移除。</td></tr>");
    b.append("</tbody></table></div></form>").append(pager(editLink(draft.dataset(),draft.organizationId(),null,null,draft.id(),prior),page,pages,total));
    return shell("我的草稿差异","edit",b.toString());
  }

  private String editorFilter(String dataset,String organization,LocalDate from,LocalDate through,Draft draft,String prior) {
    StringBuilder b=new StringBuilder("<form class=\"workflow-filter clearfix\" method=\"get\" action=\"/workflow/edit\"><label>表种<select name=\"dataset\">");for(DatasetSchema s:DatasetSchema.all())b.append(option(s.id,s.label,dataset));b.append("</select></label>");
    if(session.actor.role()==Role.DIVISION_ADMIN){b.append("<label>支行<select name=\"organization\">").append(option("","请选择支行",organization));for(var entry:Organizations.BRANCHES.entrySet())b.append(option(entry.getKey(),entry.getValue(),organization));b.append("</select></label>");}
    else b.append(hidden("organization",organization)).append("<label>机构<span class=\"workflow-readonly\">").append(e(Organizations.label(organization))).append("</span></label>");
    b.append("<label>开始日期<input name=\"from\" value=\"").append(e(from==null?"":from.toString())).append("\" placeholder=\"YYYY-MM-DD\"></label><label>结束日期<input name=\"through\" value=\"").append(e(through==null?"":through.toString())).append("\" placeholder=\"YYYY-MM-DD\"></label>");
    if(draft!=null)b.append(hidden("draft",draft.id()));if(!prior.isEmpty())b.append(hidden("prior",prior));b.append("<button class=\"btn btn-dark\" type=\"submit\">查看正式记录</button></form>");return b.toString();
  }

  private String submissionFilter(String dataset,String organization,State state,LocalDate from,LocalDate through,boolean mine,boolean pending) {
    String action=pending?"/workflow/reviews":"/workflow/submissions";StringBuilder b=new StringBuilder("<form class=\"workflow-filter clearfix\" method=\"get\" action=\"").append(action).append("\"><label>表种<select name=\"dataset\">").append(option("","全部表种",dataset));for(DatasetSchema s:DatasetSchema.all())b.append(option(s.id,s.label,dataset));b.append("</select></label>");
    if(AccessPolicy.all(session.actor)){b.append("<label>支行<select name=\"organization\">").append(option("","全部支行",organization));for(var entry:Organizations.BRANCHES.entrySet())b.append(option(entry.getKey(),entry.getValue(),organization));b.append("</select></label>");}
    if(!pending){b.append("<label>状态<select name=\"state\">").append(option("","全部状态",state==null?"":state.name()));for(State candidate:State.values())b.append(option(candidate.name(),stateLabel(candidate),state==null?"":state.name()));b.append("</select></label>");}
    b.append("<label>开始日期<input name=\"from\" value=\"").append(e(from==null?"":from.toString())).append("\" placeholder=\"YYYY-MM-DD\"></label><label>结束日期<input name=\"through\" value=\"").append(e(through==null?"":through.toString())).append("\" placeholder=\"YYYY-MM-DD\"></label>");
    if(session.actor.role()!=Role.OPERATOR&&!pending)b.append("<label class=\"workflow-check\"><input type=\"checkbox\" name=\"mine\" value=\"1\"").append(mine?" checked=\"checked\"":"").append("> 只看本人提交</label>");
    b.append("<button class=\"btn btn-dark\" type=\"submit\">筛选</button></form>");return b.toString();
  }

  private String draftPanel(List<Draft> drafts) {
    StringBuilder b=new StringBuilder("<section class=\"workflow-panel\"><div class=\"workflow-panel-head clearfix\"><div><span>PRIVATE</span><h2>我的草稿</h2></div><a href=\"/workflow/drafts\">查看全部草稿</a> <a href=\"/details?dataset=multi&amp;draft=new\">新建草稿</a></div><div class=\"workflow-list\">");
    for(Draft draft:drafts)b.append("<a class=\"workflow-list-row clearfix\" href=\"/workflow/edit?dataset=").append(u(draft.dataset())).append("&amp;draft=").append(u(draft.id())).append("\"><span class=\"workflow-list-main\"><strong>").append(e(DatasetSchema.get(draft.dataset()).label)).append("</strong><small>").append(draft.rows().size()).append(" 条差异 · 版本 ").append(draft.version()).append(" · ").append(e(dateTime(draft.updatedAt()))).append("</small></span><em>继续编辑</em></a>");
    if(drafts.isEmpty())b.append(empty("尚无私人草稿。"));return b.append("</div></section>").toString();
  }

  private String submissionPanel(String title,List<Submission> rows,boolean pending) {
    StringBuilder b=new StringBuilder("<section class=\"workflow-panel\"><div class=\"workflow-panel-head clearfix\"><div><span>").append(pending?"ACTION REQUIRED":"HISTORY").append("</span><h2>").append(e(title)).append("</h2></div>");
    b.append("<a href=\"").append(pending?"/workflow/reviews":"/workflow/submissions").append("\">查看全部</a></div><div class=\"workflow-list\">");
    for(Submission row:rows)b.append("<a class=\"workflow-list-row clearfix\" href=\"/workflow/submission?id=").append(u(row.id())).append("\"><span class=\"workflow-status ").append(stateClass(row.state())).append("\">").append(e(stateLabel(row.state()))).append("</span><span class=\"workflow-list-main\"><strong>").append(e(DatasetSchema.get(row.dataset()).label)).append(" · ").append(e(row.ownerName())).append("</strong><small>").append(e(Organizations.label(row.organizationId()))).append(" · ").append(row.rowStages().size()).append(" 条记录 · ").append(e(dateTime(row.createdAt()))).append("</small></span><em>查看详情</em></a>");
    if(rows.isEmpty())b.append(empty(pending?"当前没有需要处理的复核单。":"暂无提交记录。"));return b.append("</div></section>").toString();
  }

  private String diffTable(List<SnapshotRow> rows) {
    StringBuilder b=new StringBuilder();
    for(BranchSnapshot branch:WorkflowContracts.branchSnapshots(rows)){
      b.append("<section class=\"workflow-branch-snapshot\"><h2>").append(e(Organizations.label(branch.organizationId()))).append(" · 修改明细</h2><div class=\"workflow-table-scroll\"><table class=\"workflow-table workflow-diff-table\"><thead><tr><th>企业／客户</th><th>来源时间</th><th>字段</th><th>正式原值</th><th>拟修改值</th></tr></thead><tbody>");
      for(SnapshotRow row:branch.rows()){DatasetSchema schema=DatasetSchema.get(row.before().dataset());for(FieldDiff field:confirmationFields(row))b.append("<tr><td class=\"workflow-company\">").append(e(schema.value(row.before().values(),schema.customerColumn))).append("</td><td>").append(e(row.before().period().key())).append("</td><td>").append(e(field.title())).append("</td><td class=\"workflow-before\">").append(e(blankLabel(field.before()))).append("</td><td class=\"workflow-after\">").append(e(blankLabel(field.after()))).append("</td></tr>");}
      b.append("</tbody></table></div></section>");
    }
    return b.toString();
  }

  private String submissionItems(Submission submission) {
    boolean branch=session.actor.role()==Role.REVIEWER,division=session.actor.role()==Role.DIVISION_ADMIN;
    RowStage actionable=branch?RowStage.BRANCH_REVIEW:division?RowStage.DIVISION_REVIEW:null;
    List<String> selected=new ArrayList<>();StringBuilder b=new StringBuilder();
    b.append("<div class=\"workflow-items\"><h2>复核明细</h2><p>按行显示本次拟发布内容，改动的单元格会标出原值；每行点“复核通过”或“退回”即可。</p>");
    Set<String> shown=new HashSet<>();for(var row:submission.rows())shown.add(row.before().id());
    if(submission.rowStages().size()>shown.size()){
      b.append("<section class=\"workflow-callout\"><h2>精简流程回执</h2><p>以下记录当前不在本账号的业务明细范围，仅保留阶段回执。</p><ul>");
      submission.rowStages().forEach((id,stage)->{if(!shown.contains(id))b.append("<li>记录 ").append(e(shortId(id))).append(" · ").append(e(stageLabel(stage))).append("</li>");});b.append("</ul></section>");
    }
    if(submission.mode()==Mode.IMPORT)b.append("<p class=\"workflow-callout warning\">这是上传的预填候选。上传人仅记录来源；支行复核员须明确核对后提交分行，终审通过前不写正式值。上传人可在独立支行确认后进行分行终审。</p>");
    for(BranchSnapshot group:WorkflowContracts.branchSnapshots(submission.rows())) {
      DatasetSchema schema=DatasetSchema.get(group.rows().get(0).before().dataset());
      b.append("<section class=\"workflow-branch-snapshot\"><h3>").append(e(Organizations.label(group.organizationId()))).append(" · ").append(e(schema.label)).append("</h3><div class=\"workflow-table-scroll\"><table class=\"workflow-table workflow-review-table\"><thead><tr><th class=\"workflow-op-col\">操作</th>");
      for(DatasetSchema.Field field:schema.fields)b.append("<th>").append(e(field.title())).append("</th>");
      b.append("</tr></thead><tbody>");
      for(SnapshotRow row:group.rows()) {
        String recordId=row.before().id();RowStage stage=submission.rowStages().getOrDefault(recordId,RowStage.LEGACY_PUBLISHED);
        b.append("<tr><td class=\"workflow-op-col\">");
        if(actionable!=null&&stage==actionable) {
          selected.add(recordId);String action=branch?"branch":"division";
          b.append("<form method=\"post\" action=\"/workflow/review/approve\" class=\"workflow-review-op\">").append(hidden("csrf",session.csrf)).append(hidden("submissionId",submission.id())).append(hidden("recordIds",recordId)).append(hidden("requestId",requestId(action+"-approve",submission.id()+"-"+recordId))).append("<button class=\"btn btn-primary\" type=\"submit\">复核通过</button></form>")
            .append("<form method=\"post\" action=\"/workflow/review/reject\" class=\"workflow-review-op\" onsubmit=\"return workflowRejectReason(this);\">").append(hidden("csrf",session.csrf)).append(hidden("submissionId",submission.id())).append(hidden("recordIds",recordId)).append(hidden("reason","")).append(hidden("requestId",requestId(action+"-reject",submission.id()+"-"+recordId))).append("<button class=\"btn btn-light\" type=\"submit\">退回</button></form>");
        } else b.append("<span class=\"workflow-status ").append(stageClass(stage)).append("\">").append(e(stageLabel(stage))).append("</span>");
        b.append("</td>");
        for(DatasetSchema.Field field:schema.fields){int index=schema.index(field.key());String before=schema.value(row.before().values(),index);String after=row.change().values().containsKey(field.key())?row.change().values().get(field.key()):before;boolean changed=!after.equals(before);
          b.append("<td").append(changed?" class=\"workflow-review-changed\"":"").append(changed?" title=\"原值："+e(blankLabel(before))+"\"":"").append(">").append(e(blankLabel(after))).append("</td>");}
        b.append("</tr>");
        if(stage==RowStage.RETURNED&&session.actor.role()==Role.OPERATOR)b.append("<tr><td colspan=\"").append(schema.fields.size()+1).append("\"><form method=\"post\" action=\"/workflow/returned/restore\">").append(hidden("csrf",session.csrf)).append(hidden("submissionId",submission.id())).append(hidden("recordIds",recordId)).append(hidden("requestId",UUID.randomUUID().toString())).append("<p>从此行已提交的退回快照新建本人草稿；不会读取原作者未提交草稿，也不改变同单其他行。</p><button class=\"btn btn-light\" type=\"submit\">恢复此退回行并修订</button></form></td></tr>");
      }
      b.append("</tbody></table></div></section>");
    }
    if(actionable!=null&&selected.size()>1) {
      String ids=String.join(",",selected);String scope=UUID.nameUUIDFromBytes(ids.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();String action=branch?"branch":"division";
      b.append("<div class=\"workflow-batch-actions\"><span>批量处理当前待复核的 ").append(selected.size()).append(" 行：</span><form method=\"post\" action=\"/workflow/review/approve\">").append(hidden("csrf",session.csrf)).append(hidden("submissionId",submission.id())).append(hidden("recordIds",ids)).append(hidden("requestId",requestId(action+"-approve-batch",submission.id()+"-"+scope))).append("<button class=\"btn btn-primary\" type=\"submit\">全部通过</button></form>")
        .append("<form method=\"post\" action=\"/workflow/review/reject\" onsubmit=\"return workflowRejectReason(this);\">").append(hidden("csrf",session.csrf)).append(hidden("submissionId",submission.id())).append(hidden("recordIds",ids)).append(hidden("reason","")).append(hidden("requestId",requestId(action+"-reject-batch",submission.id()+"-"+scope))).append("<button class=\"btn btn-light\" type=\"submit\">全部退回</button></form></div>");
    }
    if(actionable!=null)b.append("<noscript><p class=\"workflow-callout warning\">点击退回后请在下一页填写原因并确认。</p></noscript>");
    return b.append("</div>").toString();
  }
  private static String stageLabel(RowStage stage){return switch(stage){case READY->"待支行处理";case BRANCH_REVIEW->"待支行复核";case DIVISION_REVIEW->"待分行终审";case RETURNED->"退回待修改";case PUBLISHED->"已终审发布";case LEGACY_PUBLISHED->"旧版正式保留";};}
  private static List<FieldDiff> confirmationFields(SnapshotRow row){
    if(!row.change().values().isEmpty())return row.fields();
    DatasetSchema schema=DatasetSchema.get(row.before().dataset());return schema.fields.stream().filter(DatasetSchema.Field::editable).map(f->{String value=schema.value(row.before().values(),schema.index(f.key()));return new FieldDiff(f.key(),f.title(),value,value);}).toList();
  }
  private static String stageClass(RowStage stage){return switch(stage){case READY,RETURNED->"returned";case BRANCH_REVIEW,DIVISION_REVIEW->"pending";case PUBLISHED,LEGACY_PUBLISHED->"approved";};}

  private String actionBar(boolean draftMode) {
    if(draftMode)return "<div class=\"workflow-action-bar clearfix\"><div><strong>当前页填写</strong><span>保存会与其他分页的草稿差异合并；提交前仍会先展示服务端差异。</span></div><button class=\"btn btn-primary\" type=\"submit\" name=\"intent\" value=\"preview\">提交</button><button class=\"btn btn-light\" type=\"submit\" name=\"intent\" value=\"save\">保存草稿</button></div>";
    String outcome=session.actor.role()==Role.DIVISION_ADMIN?"分行管理员确认后直接终审发布。":"确认后先作为不可变快照送分行终审，正式值暂不改变。";
    return "<div class=\"workflow-action-bar clearfix\"><div><strong>本人修改</strong><span>下一步先预览差异；"+outcome+"</span></div><button class=\"btn btn-primary\" type=\"submit\">预览修改</button></div>";
  }

  private static String fieldValue(DatasetSchema schema,BusinessRecord row,SnapshotRow saved,DatasetSchema.Field field) {if(saved!=null&&saved.change().values().containsKey(field.key()))return saved.change().values().get(field.key());return schema.value(row.values(),schema.index(field.key()));}
  private static int editableCount(DatasetSchema schema){int count=0;for(var field:schema.fields)if(field.editable())count++;return count;}
  private static int countChanges(List<SnapshotRow> rows){int count=0;for(var row:rows)count+=row.fields().size();return count;}
  private static boolean canDirect(ActorContext actor){return actor.role()==Role.DIVISION_ADMIN||actor.role()==Role.BRANCH_ADMIN||actor.role()==Role.REVIEWER;}
  private static String stateLabel(State state){return switch(state){case SUBMITTED->"待支行复核";case PENDING_DIVISION->"待分行终审";case PARTIAL->"部分处理";case APPROVED->"已发布";case RETURNED->"已退回";};}
  private static String stateClass(State state){return switch(state){case SUBMITTED,PENDING_DIVISION,PARTIAL->"pending";case APPROVED->"approved";case RETURNED->"returned";};}
  private static String blankLabel(String value){return value==null||value.isBlank()?"（空白）":value;}
  private static String shortId(String id){return id==null?"":id.substring(0,Math.min(8,id.length()));}
  private static String dateTime(Instant value){return value==null?"—":DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZONE).format(value);}
  private static String roleGuide(Role role){return switch(role){case OPERATOR->"查看并填写本支行待处理行；保存本人私人草稿，核对差异后提交支行复核。";case REVIEWER,BRANCH_ADMIN->"查看本支行待处理行并处理支行复核；本人修改确认后送分行终审，不会提前改变正式值。";case DIVISION_ADMIN->"查看全表并处理分行终审；本人修改经差异确认后直接终审发布。";case SUPER_ADMIN->"全域只读查看；超级管理员不填写、不审批。";};}
  private static String requestId(String action,String objectId){return action+"-"+objectId;}
  private static String tab(String href,String label,String name,String active){return "<a"+(name.equals(active)?" class=\"active\"":"")+" href=\""+href+"\">"+e(label)+"</a>";}
  private static String metric(String label,int value,String note){return "<div class=\"workflow-metric\"><span>"+e(label)+"</span><strong>"+value+"</strong><small>"+e(note)+"</small></div>";}
  private static String empty(String text){return "<div class=\"workflow-empty\"><strong>暂无内容</strong><p>"+e(text)+"</p></div>";}
  private static String message(String text,boolean error){return text==null||text.isBlank()?"":"<div class=\"workflow-message "+(error?"error":"success")+"\">"+e(text)+"</div>";}
  private static String pager(String base,int page,int pages,int total){StringBuilder b=new StringBuilder("<div class=\"workflow-pager\">");if(page>1)b.append("<a href=\"").append(base).append("&amp;page=").append(page-1).append("\">上一页</a>");b.append("<span>第 ").append(page).append(pages>0?" / "+pages:"").append(" 页");if(total>=0)b.append(" · 共 ").append(total).append(" 条");b.append("</span>");if(pages>page)b.append("<a href=\"").append(base).append("&amp;page=").append(page+1).append("\">下一页</a>");return b.append("</div>").toString();}
  private static String editLink(String dataset,String org,LocalDate from,LocalDate through,String draft,String prior){StringBuilder b=new StringBuilder("/workflow/edit?dataset=").append(u(dataset)).append("&amp;organization=").append(u(org));if(from!=null)b.append("&amp;from=").append(u(from.toString()));if(through!=null)b.append("&amp;through=").append(u(through.toString()));if(!draft.isEmpty())b.append("&amp;draft=").append(u(draft));if(!prior.isEmpty())b.append("&amp;prior=").append(u(prior));return b.toString();}
  private static String submissionLink(String dataset,String org,State state,LocalDate from,LocalDate through,boolean mine,boolean pending){StringBuilder b=new StringBuilder(pending?"/workflow/reviews?x=1":"/workflow/submissions?x=1");if(!dataset.isEmpty())b.append("&amp;dataset=").append(u(dataset));if(!org.isEmpty())b.append("&amp;organization=").append(u(org));if(state!=null)b.append("&amp;state=").append(u(state.name()));if(from!=null)b.append("&amp;from=").append(u(from.toString()));if(through!=null)b.append("&amp;through=").append(u(through.toString()));if(mine)b.append("&amp;mine=1");return b.toString();}
}

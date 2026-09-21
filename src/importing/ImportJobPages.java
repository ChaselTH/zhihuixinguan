import java.util.*;
import xinguan.platform.*;
import static xinguan.platform.ImportPlatform.*;

final class ImportJobPages extends PageLayout {
  ImportJobPages(String version,AuthService.Session session){super(version,session);}
  String history(List<Job> jobs,int offset){
    StringBuilder b=new StringBuilder("<h1>我的导入任务</h1><p>只显示本人任务。待确认内容保存 30 分钟，重新登录或重启服务后可继续；过期需重新上传。正式导入结果长期保留。</p><p><a class=\"btn btn-primary\" href=\"/imports\">上传新批次</a></p><div class=\"table-scroll\"><table class=\"data-table\"><thead><tr><th>创建时间</th><th>所属月份</th><th>识别清单</th><th>记录数</th><th>状态</th><th>操作</th></tr></thead><tbody>");
    for(Job j:jobs)b.append("<tr><td>").append(e(time(j.createdAt()))).append("</td><td>").append(e(j.selectedMonth().isBlank()?"需重新上传选月":j.selectedMonth())).append("</td><td>").append(e(label(j.dataset()))).append("</td><td>").append(j.count()).append("</td><td>").append(state(j.state())).append("</td><td><a href=\"/imports/preview?token=").append(u(j.id())).append("\">查看任务</a></td></tr>");
    if(jobs.isEmpty())b.append("<tr><td colspan=\"6\">暂无导入任务</td></tr>");
    return shell("导入任务",b.append("</tbody></table></div>").append(AccessPages.pager("/imports/jobs?",offset,jobs.size())).toString());
  }
  String preview(Preview p,int offset){return preview(p,offset,false);}
  String preview(Preview p,int offset,boolean details){
    Job j=p.job();StringBuilder b=new StringBuilder("<h1>导入预览</h1>").append(summary(p));
    b.append("<p><a href=\"/imports\">返回上传</a> · <a href=\"/imports/jobs\">导入任务</a> · <a href=\"/imports/preview?token=").append(u(j.id())).append(details?"\">收起明细":"&amp;details=yes\">查看明细").append("</a></p>");
    if(j.state().equals("PREVIEW")){
      b.append("<section class=\"identity-card\"><h2>批量决定</h2><form method=\"post\" action=\"/imports/confirm-bulk\" class=\"import-bulk-confirm\" data-new=\"").append(p.summary().newCount()).append("\" data-duplicates=\"").append(p.summary().formalDuplicates()).append("\" data-preserve=\"").append(p.summary().preserveUpdated()).append("\" data-overwrite=\"").append(p.summary().overwriteUpdated()).append("\">").append(fields(j,offset,details)).append(hidden("confirmed","")).append("<label>重复记录 <select name=\"mode\">").append(option("preserve","保留已有填写，仅补空白","preserve")).append(option("overwrite","使用上传值覆盖（含清空）","preserve")).append("</select></label><p class=\"import-selected-count\">按当前选择：预计更新 ").append(p.summary().preserveUpdated()).append(" 条，保持不变 ").append(p.summary().formalDuplicates()-p.summary().preserveUpdated()).append(" 条。</p><p><button class=\"btn btn-primary\" type=\"submit\">确认导入</button></p></form><form method=\"post\" action=\"/imports/cancel\">").append(fields(j,offset,details)).append("<button class=\"btn btn-light\" type=\"submit\">取消任务</button></form></section>");
    }else b.append("<p>任务状态：").append(state(j.state())).append("</p>");
    if(details){
      b.append("<div class=\"table-scroll\"><table class=\"data-table import-diff\"><thead><tr><th>清单／企业</th><th>来源</th><th>类型</th><th>填写差异（原值 → 上传值）</th></tr></thead><tbody>");
      for(Item item:p.items()){
        BusinessRecord r=item.source().record(),old=item.previous();DatasetSchema schema=DatasetSchema.get(r.dataset());
        b.append("<tr><td>").append(e(schema.label)).append("<br>").append(e(schema.value(r.values(),schema.customerColumn))).append("</td><td>").append(e(r.filename())).append(" / ").append(e(item.source().sheet())).append(" / 第 ").append(item.source().row()).append(" 行</td><td>").append(old==null?"新增":"重复").append("</td><td>");
        int changes=0;for(int c=0;c<schema.width();c++)if(schema.editable(c)){
          String before=old==null?"":old.values().get(c),after=r.values().get(c);if(before.equals(after))continue;
          changes++;b.append(e(schema.fields.get(c).title())).append("：").append(e(before.isBlank()?"（空白）":before)).append(" → ").append(e(after.isBlank()?"（空白）":after)).append("<br>");
        }
        if(changes==0)b.append("无填写差异");b.append("</td></tr>");
      }
      b.append("</tbody></table></div>").append(AccessPages.pager("/imports/preview?token="+u(j.id())+"&details=yes",offset,p.items().size()));
    }
    return shell("导入预览",b.toString());
  }
  private String summary(Preview p){
    Summary s=p.summary();StringBuilder b=new StringBuilder("<div class=\"import-summary\"><strong>本批数据对比</strong><p>冻结所属月份：").append(e(p.job().selectedMonth().isBlank()?"未选择（旧任务不能确认，请重新上传）":p.job().selectedMonth())).append("</p><p>上传有效记录 ").append(p.job().count()).append(" 条：新增 ").append(s.newCount()).append(" 条，重复 ").append(s.formalDuplicates()).append(" 条（其中填写不同 ").append(s.fillConflicts()).append(" 条）。</p><p>");
    for(var entry:s.datasetCounts().entrySet())b.append(e(label(entry.getKey()))).append(" ").append(entry.getValue()).append(" 条　");
    b.append("</p><p>文件 ").append(s.fileCount()).append(" 个；工作表：");for(var entry:s.sheetCounts().entrySet())b.append(e(entry.getKey())).append("（").append(entry.getValue()).append(" 条） ");
    b.append("</p><p>所属月份记录键：").append(e(String.join("、",s.periods()))).append("</p><p>跳过空白／说明／示例 ").append(p.job().examples()).append(" 处；本批重复源行合并 ").append(p.job().repeated()).append(" 条。</p><p>保留模式预计更新 ").append(s.preserveUpdated()).append(" 条；覆盖模式预计更新 ").append(s.overwriteUpdated()).append(" 条；无变化 ").append(Math.max(0,s.formalDuplicates()-s.preserveUpdated())).append(" 条。更新数量不含新增。</p>");
    if(s.similarCandidates()>0)b.append("<p>有 ").append(s.similarCandidates()).append(" 条同客户同期但来源不同的候选，按独立记录保留。</p>");
    return b.append("</div>").toString();
  }
  String confirmation(Preview p,String mode){
    StringBuilder b=new StringBuilder("<h1>确认导入</h1>").append(summary(p)).append("<p>").append(mode.equals("overwrite")?"使用上传值覆盖，上传空白也会清空原填写内容。":"保留已有填写，只补充空白字段。").append("</p><form method=\"post\" action=\"/imports/confirm-bulk\">").append(fields(p.job(),0,false)).append(hidden("mode",mode)).append(hidden("confirmed","yes")).append("<button class=\"btn btn-primary\" type=\"submit\">确定导入</button> <a class=\"btn btn-light\" href=\"/imports/preview?token=").append(u(p.job().id())).append("\">取消</a></form>");
    return shell("确认导入",b.toString());
  }
  String errors(List<WorkbookImporter.Issue> errors){StringBuilder b=new StringBuilder("<h1>表格校验未通过</h1><div class=\"alert alert-error\">本批全部未导入。请按位置修正后重新上传；每个文件最多展示前 100 个错误。</div><p><a href=\"/imports\">返回上传并下载对应模板</a></p><div class=\"table-scroll\"><table class=\"data-table import-diff\"><thead><tr><th>文件</th><th>工作表</th><th>行</th><th>列</th><th>问题</th></tr></thead><tbody>");for(var i:errors)b.append("<tr><td>").append(e(i.filename())).append("</td><td>").append(e(i.sheet())).append("</td><td>").append(i.row()>0?i.row():"—").append("</td><td>").append(e(i.column())).append("</td><td>").append(e(i.message())).append("</td></tr>");return shell("导入错误",b.append("</tbody></table></div>").toString());}
  private String fields(Job j,int offset){return fields(j,offset,false);}
  private String fields(Job j,int offset,boolean details){return hidden("csrf",currentSession.csrf)+hidden("token",j.id())+hidden("revision",Long.toString(j.revision()))+hidden("offset",Integer.toString(offset))+(details?hidden("details","yes"):"");}
  private String shell(String title,String body){return page(title,header()+"<div class=\"page-shell import-shell\">"+body+"</div>").replace("</head>","<link rel=\"stylesheet\" href=\"/assets/import.css\"></head>");}
  private static String choice(Choice c){return switch(c){case PRESERVE->"保留已有填写，仅补空白";case OVERWRITE->"覆盖为上传值（含清空）";case SKIP->"跳过，不修改此条";};}
  private static String state(String s){return switch(s){case "PREVIEW"->"待确认";case "COMMITTED"->"已导入";case "CANCELLED"->"已取消";case "EXPIRED"->"已过期";default->s;};}
  private static String label(String dataset){return "bundle".equals(dataset)?"三表统一工作簿":DatasetSchema.get(dataset).label;}
}

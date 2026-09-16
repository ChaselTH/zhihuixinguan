import com.sun.net.httpserver.HttpExchange;
import java.util.*;
import xinguan.platform.*;

/** Main owns authentication, password/safety gates and normal form CSRF validation. */
final class ImportRoutes extends HttpSupport {
  private final ImportPlatform importing;private final AuthService auth;private final String version;
  private final WorkbookImporter reader=new WorkbookImporter();
  ImportRoutes(PlatformStore store,AuthService auth,String version){this.importing=store.importing();this.auth=auth;this.version=version;}
  boolean get(HttpExchange x,AuthService.Session s,Map<String,String> q)throws Exception{
    String path=x.getRequestURI().getPath();if(!Set.of("/imports","/imports/jobs","/imports/preview","/template").contains(path))return false;
    AccessPolicy.require(s.actor,AccessPolicy.Action.UPLOAD,Organizations.DIVISION);ImportJobPages pages=new ImportJobPages(version,s);
    switch(path){
      case "/imports" -> sendHtml(x,200,new ImportPages(version,s).imports(s,limit(q.get("notice"),800),"1".equals(q.get("error"))));
      case "/imports/jobs" -> {int offset=offset(q);sendHtml(x,200,pages.history(importing.jobs(s.actor,offset,25),offset));}
      case "/imports/preview" -> {int offset=offset(q);sendHtml(x,200,pages.preview(importing.preview(s.actor,q.get("token"),offset,25),offset));}
      case "/template" -> {String dataset=q.get("dataset");sendDownload(x,new ExcelExporter().template(dataset),DatasetSchema.get(dataset).label+"_模板_v1.xlsx");}
      default -> throw new IllegalStateException();
    }return true;
  }
  void upload(HttpExchange x,AuthService.Session s,String dataset)throws Exception{
    DatasetSchema.get(dataset);AccessPolicy.require(s.actor,AccessPolicy.Action.UPLOAD,Organizations.DIVISION);
    String type=x.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data"))throw new IllegalArgumentException("上传格式错误");String boundary=boundary(type);if(boundary.isBlank()||boundary.length()>200)throw new IllegalArgumentException("上传边界错误");
    Multipart form=parseMultipart(readLimited(x.getRequestBody(),50*1024*1024),boundary);if(!auth.csrf(s,form.fields.get("csrf")))throw new SecurityException("页面校验已失效");
    if(form.files.isEmpty()||form.files.size()>10)throw new IllegalArgumentException("请选择 1～10 个文件");
    List<ImportPlatform.SourceRow> rows=new ArrayList<>();List<WorkbookImporter.Issue> errors=new ArrayList<>();int skipped=0;long characters=0;
    for(Part file:form.files){
      if(file.data.length==0||file.data.length>20*1024*1024){errors.add(new WorkbookImporter.Issue(file.filename,"",0,"","文件不能为空，且单文件不能超过 20 MB"));continue;}
      var parsed=reader.inspect(file.data,file.filename,form.fields.get("month"),form.fields.get("period"),dataset);errors.addAll(parsed.errors());
      for(var source:parsed.sources())for(String value:source.record().values())characters+=value.length();
      if(characters>8_000_000)throw new IllegalArgumentException("本批单元格文字合计超过 800 万字，请分批上传；本批全部未导入");
      rows.addAll(parsed.sources());skipped+=parsed.skippedExamples();
      if(rows.size()>20000)throw new IllegalArgumentException("一批最多 20000 条，请分批上传");
    }
    if(!errors.isEmpty()){sendHtml(x,400,new ImportJobPages(version,s).errors(errors));return;}
    if(rows.isEmpty()){adminRedirect(x,"格式校验通过，未发现业务数据；跳过示例／说明行 "+skipped+" 条。空模板未写入正式数据。",false);return;}
    var job=importing.stage(s.actor,dataset,rows,skipped);sendHtml(x,200,new ImportJobPages(version,s).preview(importing.preview(s.actor,job.id(),0,25),0));
  }
  boolean post(HttpExchange x,AuthService.Session s,Map<String,String> f)throws Exception{
    String path=x.getRequestURI().getPath();if(!Set.of("/imports/confirm","/imports/choices","/imports/cancel").contains(path))return false;
    AccessPolicy.require(s.actor,AccessPolicy.Action.UPLOAD,Organizations.DIVISION);String id=f.get("token");long revision=Long.parseLong(f.getOrDefault("revision","1"));
    if(path.equals("/imports/confirm")){var outcome=importing.confirm(s.actor,id,revision,f.getOrDefault("mode","saved"),"yes".equals(f.get("confirmOverwrite")),"yes".equals(f.get("confirmSimilar")));adminRedirect(x,"导入完成：新增 "+outcome.added()+" 条，重复 "+outcome.duplicates()+" 条，保留已有填写 "+outcome.preserved()+" 条。任务 "+id,false);return true;}
    if(path.equals("/imports/cancel")){importing.cancel(s.actor,id,revision);redirect(x,"/imports/jobs");return true;}
    Map<Integer,ImportPlatform.Choice> choices=new LinkedHashMap<>();for(var entry:f.entrySet())if(entry.getKey().startsWith("choice_"))choices.put(Integer.parseInt(entry.getKey().substring(7)),ImportPlatform.Choice.valueOf(entry.getValue()));
    ImportPlatform.Choice all=f.containsKey("allChoice")?ImportPlatform.Choice.valueOf(f.get("allChoice")):null;
    importing.choices(s.actor,id,revision,choices,all);redirect(x,"/imports/preview?token="+url(id)+"&offset="+offset(f));return true;
  }
  private static int offset(Map<String,String> q){int n=integer(q.get("offset"),0);if(n<0||n>20000)throw new IllegalArgumentException("分页范围无效");return n;}
}

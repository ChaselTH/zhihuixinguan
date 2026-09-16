import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import xinguan.platform.*;
import xinguan.platform.WorkflowContracts.*;

/** Isolated GET-only synthetic preview, excluded from application JAR. No credentials or database. */
public final class BusinessVisualFixture {
  public static void main(String[] args)throws Exception{
    var user=new UserAccount("synthetic-division","000000001","虚构分行管理员",Role.DIVISION_ADMIN,"CZ",true,false,1);
    var session=new AuthService.Session("synthetic","synthetic",Instant.now().getEpochSecond(),user);session.safetyVersion=AccessPlatform.SAFETY_VERSION;
    List<ImportRecord> records=new ArrayList<>();Map<String,BusinessWorkflowState.RowState> state=new HashMap<>();
    for(var schema:DatasetSchema.all())for(int i=0;i<9;i++){
      var record=new ImportRecord();record.id="visual-"+schema.id+"-"+i;record.dataset=schema.id;record.period="20260901-20260915";record.month="2026-09";record.organizationId=new ArrayList<>(Organizations.BRANCHES.keySet()).get(i);record.importedAt="2026-09-16T00:00:00Z";record.versions.add(1L);
      List<String> values=new ArrayList<>(Collections.nCopies(schema.width(),""));values.set(0,""+(i+1));values.set(schema.customerColumn,"虚构企业集团 "+(i+1)+" 号（页面测试）");values.set(schema.branchColumn,Organizations.label(record.organizationId));values.set(schema.codeColumn,"00000000"+i);
      if(i%3==0)values.set(schema.index(schema.id.equals("cross")?"cross_feedback":"feedback"),"虚构反馈：已核查经营情况及还款安排。".repeat(10));if(schema.periodColumn>=0)values.set(schema.periodColumn,record.period);record.rows.add(values);records.add(record);
      if(i==1){var pending=new Submission("synthetic-submission-"+schema.id,Mode.REVIEW,State.SUBMITTED,"synthetic-operator","虚构操作员",record.organizationId,schema.id,"synthetic-draft",1,"",Instant.now(),"","",null,"",List.of());state.put(record.id,new BusinessWorkflowState.RowState(null,false,pending,pending));}
    }
    var states=new BusinessWorkflowState(state);var months=List.of("2026-09");
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",args.length==0?0:Integer.parseInt(args[0])),0);
    server.createContext("/",x->{try{
      if(!x.getRequestMethod().equals("GET")){HttpSupport.text(x,405,"Synthetic read-only preview","text/plain");return;}
      String path=x.getRequestURI().getPath();
      if(path.startsWith("/assets/")){String asset=path.substring(8);if(!Set.of("style.css","foundation.css","access.css","business.css","html5shiv.js").contains(asset)){HttpSupport.text(x,404,"Not found","text/plain");return;}HttpSupport.text(x,200,Files.readString(Path.of("web/assets").resolve(asset)),asset.endsWith("css")?"text/css":"application/javascript");return;}
      var query=new HashMap<>(HttpSupport.query(x.getRequestURI()));if(path.equals("/internal"))query.put("dataset","cross");
      var data=new DashboardData(RangeSelection.from(query,months),months,List.of(),records);var pages=new RiskPages("0.3.0-b2.1 · 虚构只读预览",session);var filter=BusinessFilter.from(session.actor,query);
      String html=switch(path){case "/"->pages.dashboard(data,states);case "/details"->pages.details(data,filter,1,states);case "/internal"->new InternalPages("0.3.0-b2.1 · 虚构只读预览",session).overview(data,filter,states);case "/branch"->pages.branch(data,filter.branch,states);default->pages.error(404,"虚构只读预览不执行真实填写、复核或导出");};
      HttpSupport.sendHtml(x,200,html);
    }catch(Exception e){HttpSupport.text(x,500,"Fixture error","text/plain");}finally{x.close();}});
    Runtime.getRuntime().addShutdownHook(new Thread(()->server.stop(0)));server.start();System.out.println("B2_SYNTHETIC_PREVIEW=http://127.0.0.1:"+server.getAddress().getPort());
  }
}

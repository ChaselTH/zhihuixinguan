import java.util.*;
import xinguan.platform.*;

final class AccessPages extends PageLayout {
  AccessPages(String version,AuthService.Session session){super(version,session);}
  String apply(String csrf,String scope) {
    boolean branch="branch".equals(scope);
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\"><section class=\"identity-card identity-narrow\"><h1>申请使用权限</h1><p>请选择机构类型和目标角色。管理员核验身份后分配并另行交付初始密码。</p><div class=\"access-tabs\"><a class=\"btn btn-light ").append(branch?"":"is-selected").append("\" aria-current=\"").append(branch?"false":"page").append("\" href=\"/access/apply?scope=division\">分行</a> <a class=\"btn btn-light ").append(branch?"is-selected":"").append("\" aria-current=\"").append(branch?"page":"false").append("\" href=\"/access/apply?scope=branch\">支行</a></div><h2>").append(branch?"支行人员申请":"分行人员申请").append("</h2><form method=\"post\" action=\"/access/apply\">").append(hidden("csrf",csrf));
    if(branch){b.append("<label>所属支行<select name=\"organization\" required=\"required\"><option value=\"\">请选择支行</option>");for(var o:Organizations.BRANCHES.entrySet())b.append(option(o.getKey(),o.getValue(),""));b.append("</select></label><label>拟申请角色<select name=\"requestedRole\" required=\"required\"><option value=\"\">请选择角色</option><option value=\"BRANCH_ADMIN\">支行管理员</option><option value=\"OPERATOR\">客户经理操作员</option><option value=\"REVIEWER\">客户经理复核员</option></select></label>");}else b.append(hidden("organization",Organizations.DIVISION)).append(hidden("requestedRole",Role.DIVISION_ADMIN.name())).append("<p class=\"selection-summary\"><strong>申请机构：分行</strong><br>拟申请角色：分行管理员（固定）</p>");
    return page("申请权限",b.append("<label>统一认证号<input name=\"number\" maxlength=\"20\" autocomplete=\"off\" required=\"required\"></label><label>姓名<input name=\"name\" maxlength=\"100\" autocomplete=\"off\" required=\"required\"></label><button class=\"btn btn-primary\" type=\"submit\">提交申请</button> <a class=\"btn btn-light\" href=\"/login\">返回登录</a></form></section></div>").toString());
  }
  String receipt(){return page("申请已接收",header()+"<div class=\"page-shell\"><section class=\"identity-card identity-narrow\"><h1>申请已接收</h1><p>如符合开户条件，管理员将在核验后处理。已有账号或相同待办不会重复创建。请联系本机构管理员了解结果和领取初始密码。</p><a class=\"btn btn-primary\" href=\"/login\">返回登录</a></section></div>");}
  String safety(){
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\"><section class=\"identity-card identity-narrow safety-card\"><span class=\"eyebrow\">登录确认</span><h1>数据安全提示</h1>")
      .append("<p>本系统仅限行内获准的隔离内网使用，涉及企业预警、信贷及客户信息。请确认以下要求后再进入：</p>")
      .append("<ul class=\"safety-list\">")
      .append("<li>仅使用本人账号登录，账号和密码不得转借、共用或写在便签、公共设备上；首次登录后请立即修改初始密码。</li>")
      .append("<li>只查询、填写、复核和导出与本人岗位职责、授权机构范围相符的数据，不越权查看其他支行或他人信息。</li>")
      .append("<li>业务数据、客户信息和导出文件严禁通过微信、外网邮箱、网盘、U 盘等方式传出；导出文件使用后及时删除。</li>")
      .append("<li>登录、填报、复核、退回和导出等操作均会留痕并可审计，请如实操作，不得代他人操作或伪造审批。</li>")
      .append("<li>离开工位请退出登录或锁定屏幕；发现账号异常、误发数据或泄露风险，立即报告本机构管理员。</li>")
      .append("</ul><form method=\"post\" action=\"/security/ack\">").append(hidden("csrf",currentSession.csrf)).append(hidden("noticeVersion",AccessPlatform.SAFETY_VERSION)).append("<button class=\"btn btn-primary\" type=\"submit\">我已阅读并确认</button></form></section></div>");
    return page("数据安全提示",b.toString());
  }
  String applications(List<AccessPlatform.Application> rows,boolean pending,int offset) {
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\"><h1>权限申请审批</h1><p>支行管理员可审批本支行操作员、复核员；需开通支行管理员时转交分行。分行人员仅由超级管理员审批。高层管理员可处理其管理范围内的申请。</p><p><a href=\"/access/requests\">待处理</a> · <a href=\"/access/requests?all=yes\">全部申请</a></p><div class=\"table-scroll\"><table class=\"data-table\"><thead><tr><th>申请时间</th><th>姓名</th><th>统一认证号</th><th>机构</th><th>目标角色</th><th>状态</th><th>操作</th></tr></thead><tbody>");
    for(var r:rows)b.append("<tr><td>").append(e(time(r.createdAt()))).append("</td><td>").append(e(r.name())).append("</td><td>").append(e(r.number())).append("</td><td>").append(e(Organizations.label(r.organization()))).append("</td><td>").append(r.requestedRole()==null?"历史申请":roleName(r.requestedRole())).append("</td><td>").append(state(r.state())).append("</td><td><a href=\"/access/request?id=").append(u(r.id())).append("\">查看 / 审批</a></td></tr>");
    if(rows.isEmpty())b.append("<tr><td colspan=\"7\">暂无申请</td></tr>");
    b.append("</tbody></table></div>").append(pager("/access/requests?all="+(pending?"no":"yes"),offset,rows.size()));return page("权限申请",b.append("</div>").toString());
  }
  String application(AccessPlatform.Application r,boolean canDecide) {
    StringBuilder b=new StringBuilder(header()).append("<div class=\"page-shell\"><section class=\"identity-card identity-narrow\"><h1>权限申请详情</h1><p>姓名：").append(e(r.name())).append("</p><p>统一认证号：").append(e(r.number())).append("</p><p>机构：").append(e(Organizations.label(r.organization()))).append("</p><p>拟申请角色：").append(r.requestedRole()==null?"历史申请（需审批人明确分配）":roleName(r.requestedRole())).append("</p><p>状态：").append(state(r.state())).append("</p><p>处理说明：").append(e(r.reason())).append("</p>");
    if(canDecide){b.append("<form method=\"post\" action=\"/access/decision\">").append(hidden("csrf",currentSession.csrf)).append(hidden("id",r.id())).append(hidden("revision",""+r.revision())).append(hidden("requestId",UUID.randomUUID().toString())).append("<label>批准时分配角色<select name=\"role\" required=\"required\"><option value=\"\">请选择角色</option>");for(Role role:Role.values())if(AccessPolicy.canManage(currentSession.actor,role,r.organization()))b.append(option(role.name(),roleName(role),r.requestedRole()==null?"":r.requestedRole().name()));b.append("</select></label><label>处理说明（退回 / 转交必填）<textarea name=\"reason\" rows=\"4\" maxlength=\"1000\"></textarea></label><p>批准前请通过行内渠道核实人员身份，避免冒名开户。</p><button class=\"btn btn-primary\" name=\"action\" value=\"APPROVE\" type=\"submit\">同意并生成初始密码</button> <button class=\"btn btn-light\" name=\"action\" value=\"REJECT\" type=\"submit\">退回</button>");if(currentSession.actor.role()==Role.BRANCH_ADMIN)b.append(" <button class=\"btn btn-light\" name=\"action\" value=\"ESCALATE\" type=\"submit\">转交分行</button>");b.append("</form>");}
    return page("申请详情",b.append("<p><a href=\"/access/requests\">返回申请列表</a></p></section></div>").toString());
  }
  static String state(String s){return switch(s){case "PENDING"->"待审批";case "ESCALATED"->"已转交分行";case "APPROVED"->"已批准";case "REJECTED"->"已退回";default->e(s);};}
  static String pager(String url,int offset,int count){return "<p class=\"access-pager\">"+(offset>0?"<a class=\"btn btn-light\" href=\""+e(url)+"&amp;offset="+Math.max(0,offset-25)+"\">上一页</a> ":"")+"第 "+(offset/25+1)+" 页 "+(count==25?"<a class=\"btn btn-light\" href=\""+e(url)+"&amp;offset="+(offset+25)+"\">下一页</a>":"")+"</p>";}
}

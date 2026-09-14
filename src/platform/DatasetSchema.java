package xinguan.platform;

import java.util.*;

/** Versioned schema verified against the user's three-sheet .et template (headers only). */
public final class DatasetSchema {
  public record Field(String key,String title,boolean editable,List<String> options) {}
  public final String id,label,module;
  public final int headerRows, branchColumn, customerColumn, codeColumn, periodColumn;
  public final List<Field> fields;
  private static final Map<String,DatasetSchema> ALL=new LinkedHashMap<>();
  private DatasetSchema(String id,String label,String module,int headerRows,int branch,int customer,int code,int period,String[] keys,String[] titles,int[] editable) {
    this.id=id;this.label=label;this.module=module;this.headerRows=headerRows;this.branchColumn=branch;this.customerColumn=customer;this.codeColumn=code;this.periodColumn=period;
    Set<Integer> input=new HashSet<>();for(int n:editable)input.add(n);
    List<Field> f=new ArrayList<>();
    for(int i=0;i<keys.length;i++) {
      List<String> options=switch(keys[i]) {
        case "default_risk","repayment_impact" -> List.of("是","否");
        case "control_category" -> List.of("无需管控","日常一般管控","重点关注管控");
        case "finance_strategy" -> List.of("增加","维持","压降","退出");
        default -> List.of();
      };
      f.add(new Field(keys[i],titles[i],input.contains(i),options));
    }
    fields=List.copyOf(f); ALL.put(id,this);
  }
  static {
    new DatasetSchema("negative","负面闭环清单","risk",1,6,2,3,12,
      new String[]{"sequence","warning_summary","customer_name","customer_code","warning_level","warning_detail","branch","loan_balance","credit_rating","feedback","repayment_impact","control_measures","period"},
      new String[]{"序号","预警简述","企业名称","客户编码","变动级别","变动详情","支行","贷款余额（万元）","信用等级","情况反馈","预测对企业近6个月的还款能力是否产生实质性影响","如有风险，请说明管控措施","时间顺序"},new int[]{9,10,11});
    new DatasetSchema("multi","多重预警清单","risk",2,1,2,3,22,
      new String[]{"sequence","branch","customer_name","customer_code","loan_balance","credit_rating","company_size","industry","sales_2024","sales_2025","profit_2024","profit_2025","finance_2024","finance_2025","icbc_finance_2024","icbc_finance_2025","warning_detail","feedback","default_risk","control_category","control_measures","finance_strategy","period"},
      new String[]{"序号","支行","客户全称","客户编码","贷款余额（万元）","信用等级","企业规模","所属行业（大类）","2024年销售收入","2025销售收入","2024年净利润","2025年净利润","2024年融资总额","2025融资总额","2024我行融资","2025我行融资","多重预警信息","情况反馈","未来6个月内是否存在违约风险","后续管控分类（无需管控，日常一般管控，重点关注管控）","具体管控目标及措施（日常一般管控、重点管控必填）","本年度融资策略","时间顺序"},new int[]{8,9,10,11,12,13,14,15,17,18,19,20,21});
    new DatasetSchema("cross","交叉违约清单","internal",2,3,1,2,-1,
      new String[]{"sequence","customer_name","customer_code","branch","authorization_filed","authorization_expiry","icbc_watch","icbc_bad","other_watch","other_bad","default_bank","default_first_date","cross_feedback","default_risk","control_measures"},
      new String[]{"序号","客户全称","客户编码","支行","两书是否入库","已入库两书授权到期日","工行违约情况 / 关注","工行违约情况 / 不良","他行违约情况 / 关注","他行违约情况 / 不良","违约银行名称","违约首次出现时间","交叉违约是否满90天且金额超贷款总额20%（请列明借款人最新的总融资余额、他行违约金额、违约期数），仅我行关注的简要说明关注原因。","未来6个月是否存在违约风险","风险化解方案及管控措施"},new int[]{12,13,14});
  }
  public static DatasetSchema get(String id) { DatasetSchema s=ALL.get(id); if(s==null)throw new IllegalArgumentException("数据类型无效");return s; }
  public static Collection<DatasetSchema> all() {return Collections.unmodifiableCollection(ALL.values());}
  public int width(){return fields.size();}
  public int index(String key){for(int i=0;i<fields.size();i++)if(fields.get(i).key().equals(key))return i;return -1;}
  public boolean editable(int i){return i>=0&&i<fields.size()&&fields.get(i).editable();}
  public String value(List<String> row,int i){return i>=0&&i<row.size()&&row.get(i)!=null?row.get(i):"";}
  public boolean complete(List<String> row){for(int i=0;i<fields.size();i++)if(editable(i)&&!value(row,i).strip().isEmpty())return true;return false;}
  public void validateEdit(int index,String value) {
    if(!editable(index))throw new IllegalArgumentException("该字段不是黄色填报列");
    if(value==null||value.length()>10000)throw new IllegalArgumentException("填报内容不能超过 10000 字");
    var options=fields.get(index).options();
    if(!value.isBlank()&&!options.isEmpty()&&!options.contains(value.strip()))throw new IllegalArgumentException(fields.get(index).title()+"：请从下拉列表选择");
  }
}

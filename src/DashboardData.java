import java.math.*;
import java.util.*;
import xinguan.platform.*;

final class DashboardData {
  final RangeSelection range;final List<String> months;final List<ImportRecord> records;
  final List<RowRef> multiRows=new ArrayList<>(),negativeRows=new ArrayList<>(),crossRows=new ArrayList<>();
  final Map<String,BranchStats> branches=new LinkedHashMap<>();
  final Map<String,Set<String>> requiredFields=new LinkedHashMap<>();
  String latestUpdate="";BigDecimal loanBalance=BigDecimal.ZERO;
  DashboardData(RangeSelection range,List<String> months,List<ImportRecord> all,List<ImportRecord> records){
    this(range,months,all,records,Map.of());
  }
  DashboardData(RangeSelection range,List<String> months,List<ImportRecord> all,List<ImportRecord> records,Map<String,CompletionRules.Setting> rules){
    this.range=range;this.months=months;this.records=records;
    for(var schema:DatasetSchema.all())requiredFields.put(schema.id,rules.containsKey(schema.id)?rules.get(schema.id).requiredFields():Set.of());
    for(String name:Organizations.BRANCHES.values())branches.put(name,new BranchStats(name));
    for(ImportRecord r:records){
      requiredFields.put(r.dataset,r.requiredFields);
      String update=r.updatedAt==null||r.updatedAt.isBlank()?r.importedAt:r.updatedAt;if(update.compareTo(latestUpdate)>0)latestUpdate=update;
      DatasetSchema s=DatasetSchema.get(r.dataset);
      for(int i=0;i<r.rows.size();i++){
        List<String> row=r.rows.get(i);RowRef ref=new RowRef(r,i,row);rows(r.dataset).add(ref);
        BranchStats branch=branches.get(s.value(row,s.branchColumn));
        if(branch!=null){branch.total++;branch.totals.merge(r.dataset,1,Integer::sum);if(ref.complete()){branch.completed++;branch.done.merge(r.dataset,1,Integer::sum);}}
        if(r.dataset.equals("multi"))try{loanBalance=loanBalance.add(new BigDecimal(cell(row,4).replace(",","").strip()));}catch(NumberFormatException ignored){}
      }
    }
  }
  List<RowRef> rows(String dataset){return switch(dataset){case "multi"->multiRows;case "negative"->negativeRows;case "cross"->crossRows;default->throw new IllegalArgumentException("数据类型无效");};}
  List<RowRef> filtered(String dataset,String q,String branch){
    return filtered(dataset,q,branch,"all");
  }
  List<RowRef> filtered(String dataset,String q,String branch,String completion){
    String status=BusinessFilter.completion(completion);
    List<RowRef> result=new ArrayList<>();DatasetSchema s=DatasetSchema.get(dataset);String needle=q==null?"":q.strip().toLowerCase(Locale.ROOT);
    for(RowRef ref:rows(dataset)){if(branch!=null&&!branch.isBlank()&&!branch.equals(s.value(ref.values,s.branchColumn)))continue;
      if(status.equals("complete")&&!ref.complete()||status.equals("incomplete")&&ref.complete())continue;
      if(status.equals("overdue")&&!ref.overdue())continue;
      if(needle.isEmpty()||ref.values.stream().anyMatch(v->v.toLowerCase(Locale.ROOT).contains(needle)))result.add(ref);
    }return result;
  }
  List<FeedbackPeriod> feedbackPeriods(){
    Map<String,FeedbackPeriod> grouped=new LinkedHashMap<>();
    for(ImportRecord r:records){
      FeedbackPeriod p=grouped.computeIfAbsent(r.dataset+"\n"+r.period,k->new FeedbackPeriod(r));
      for(List<String> values:r.rows){p.total++;if(DatasetSchema.get(r.dataset).complete(values,r.requiredFields))p.completed++;}
    }
    return grouped.values().stream().sorted(Comparator.comparing((FeedbackPeriod p)->p.period).reversed().thenComparingInt(p->List.of("multi","negative","cross").indexOf(p.dataset))).toList();
  }
  static final class FeedbackPeriod{
    final String dataset,period;final java.time.LocalDate due;final long revision;final java.time.Instant asOf;int total,completed;
    FeedbackPeriod(ImportRecord r){dataset=r.dataset;period=r.period;due=r.feedbackDeadline;revision=r.deadlineRevision;asOf=r.feedbackAsOf;}
    boolean overdue(){return FeedbackTiming.overdue(due,total==completed,asOf);}
    String reminder(){return total==completed?"本期已全部完成":FeedbackTiming.remaining(due,asOf);}
  }
  int totalCount(){return multiRows.size()+negativeRows.size()+crossRows.size();}
  int completedCount(){int total=0;for(ImportRecord r:records)for(List<String> row:r.rows)if(DatasetSchema.get(r.dataset).complete(row,r.requiredFields))total++;return total;}
  int completionPercent(){return totalCount()==0?0:completedCount()*100/totalCount();}
  String loanText(){return loanBalance.setScale(2,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+" 万元";}
  static String cell(List<String> row,int i){return i>=0&&i<row.size()&&row.get(i)!=null?row.get(i):"";}
  static final class BranchStats{final String name;final Map<String,Integer> totals=new HashMap<>(),done=new HashMap<>();int total,completed;BranchStats(String n){name=n;}int total(String type){return totals.getOrDefault(type,0);}int completed(String type){return done.getOrDefault(type,0);}int percent(String type){return total(type)==0?0:completed(type)*100/total(type);}int percent(){return total==0?0:completed*100/total;}}
}

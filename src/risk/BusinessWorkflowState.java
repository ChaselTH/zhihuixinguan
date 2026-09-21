import java.util.*;
import xinguan.platform.*;
import xinguan.platform.WorkflowContracts.*;

/** Request-local presentation metadata only. Never substitute draft/snapshot values for official cells. */
final class BusinessWorkflowState {
  record RowState(Draft draft,boolean stale,Submission pending,Submission latest) {}
  private final Map<String,RowState> states;
  final Draft editingDraft;final boolean chooseDraft;
  BusinessWorkflowState(Map<String,RowState> states){this(states,null,false);}
  private BusinessWorkflowState(Map<String,RowState> states,Draft draft,boolean choose){this.states=Map.copyOf(states);editingDraft=draft;chooseDraft=choose;}
  BusinessWorkflowState editing(Draft draft,boolean choose){return new BusinessWorkflowState(states,draft,choose);}
  static BusinessWorkflowState empty(){return new BusinessWorkflowState(Map.of());}
  RowState get(String id){return states.getOrDefault(id,new RowState(null,false,null,null));}
  static BusinessWorkflowState load(PlatformStore store,ActorContext actor,Collection<RowRef> shown){
    if(shown.isEmpty())return empty();
    synchronized(store){
      Map<String,RowRef> selected=new LinkedHashMap<>();Set<String> datasets=new LinkedHashSet<>();
      for(RowRef row:shown){store.find(actor,row.record.id);selected.put(row.record.id,row);datasets.add(row.record.dataset);}
      var service=store.workflow();Map<String,Submission> pending=new HashMap<>();Map<String,Draft> drafts=new HashMap<>();Map<String,Boolean> stale=new HashMap<>();
      for(String dataset:datasets){
        for(int offset=0;;offset+=100){
          var page=service.submissions(actor,new Query(dataset,null,null,null,null,false,offset,100));
          for(var submission:page)for(var item:submission.rows())if(selected.containsKey(item.before().id())&&Set.of(RowStage.BRANCH_REVIEW,RowStage.DIVISION_REVIEW).contains(submission.rowStages().get(item.before().id()))){
            var existing=pending.get(item.before().id());
            if(existing==null||(!existing.ownerId().equals(actor.userId())&&submission.ownerId().equals(actor.userId())))pending.put(item.before().id(),submission);
          }
          if(page.size()<100)break;
        }
        if(actor.role()==Role.OPERATOR)for(int offset=0;;offset+=100){
          var page=service.drafts(actor,dataset,offset,100);
          for(var draft:page)for(var item:draft.rows()){
            var row=selected.get(item.before().id());if(row==null)continue;
            var schema=DatasetSchema.get(dataset);boolean different=item.change().values().entrySet().stream().anyMatch(e->!row.values.get(schema.index(e.getKey())).equals(e.getValue()));
            if((different||row.record.workflowStage==RowStage.RETURNED)&&drafts.putIfAbsent(row.record.id,draft)==null)stale.put(row.record.id,item.change().expectedVersion()!=row.record.versions.get(row.rowIndex));
          }
          if(page.size()<100)break;
        }
      }
      Map<String,RowState> result=new HashMap<>();for(var row:selected.values()){
        var latest=service.recordHistory(actor,row.record.id,0,1);result.put(row.record.id,new RowState(drafts.get(row.record.id),stale.getOrDefault(row.record.id,false),pending.get(row.record.id),latest.isEmpty()?null:latest.get(0)));
      }
      return new BusinessWorkflowState(result);
    }
  }
}

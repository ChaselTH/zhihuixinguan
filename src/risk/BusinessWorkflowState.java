import java.util.*;
import xinguan.platform.*;
import xinguan.platform.WorkflowContracts.*;

/** Request-local presentation metadata only. Never substitute draft/snapshot values for official cells. */
final class BusinessWorkflowState {
  record RowState(Draft draft,boolean stale,Submission pending,Submission latest) {}
  private final Map<String,RowState> states;
  private final Draft activeDraft;
  BusinessWorkflowState(Map<String,RowState> states){this(states,null);}
  BusinessWorkflowState(Map<String,RowState> states,Draft activeDraft){this.states=Map.copyOf(states);this.activeDraft=activeDraft;}
  static BusinessWorkflowState empty(){return new BusinessWorkflowState(Map.of());}
  RowState get(String id){return states.getOrDefault(id,new RowState(null,false,null,null));}
  Draft activeDraft(){return activeDraft;}
  Draft firstDraft(Collection<RowRef> rows){
    if(activeDraft!=null)return activeDraft;
    Draft candidate=null;
    for(RowRef row:rows){Draft draft=get(row.record.id).draft();if(draft==null)continue;if(candidate==null)candidate=draft;else if(!candidate.id().equals(draft.id())||candidate.version()!=draft.version())return null;}
    return candidate;
  }
  static BusinessWorkflowState load(PlatformStore store,ActorContext actor,Collection<RowRef> shown){return load(store,actor,shown,"");}
  static BusinessWorkflowState load(PlatformStore store,ActorContext actor,Collection<RowRef> shown,String requestedDraftId){
    if(shown.isEmpty())return empty();
    synchronized(store){
      Map<String,RowRef> selected=new LinkedHashMap<>();Set<String> datasets=new LinkedHashSet<>();
      for(RowRef row:shown){store.find(actor,row.record.id);selected.put(row.record.id,row);datasets.add(row.record.dataset);}
      var service=store.workflow();Map<String,Submission> pending=new HashMap<>();Map<String,Draft> drafts=new HashMap<>();Map<String,Boolean> stale=new HashMap<>();Set<String> consumedDrafts=new HashSet<>();
      Draft context=null;
      if(actor.role()==Role.OPERATOR&&!requestedDraftId.isBlank()){
        context=service.draft(actor,requestedDraftId);
        if(!datasets.contains(context.dataset())||!service.draftVersionActive(actor,context.id(),context.version()))context=null;
        else for(var item:context.rows())if(selected.containsKey(item.before().id()))drafts.put(item.before().id(),context);
      }
      for(String dataset:datasets){
        for(int offset=0;;offset+=100){
          var page=service.submissions(actor,new Query(dataset,null,State.SUBMITTED,null,null,false,offset,100));
          for(var submission:page)for(var item:submission.rows())if(selected.containsKey(item.before().id())){
            var existing=pending.get(item.before().id());
            if(existing==null||(!existing.ownerId().equals(actor.userId())&&submission.ownerId().equals(actor.userId())))pending.put(item.before().id(),submission);
          }
          if(page.size()<100)break;
        }
        // An approved draft version stays consumed even if formal data is subsequently changed.
        if(actor.role()==Role.OPERATOR)for(int offset=0;;offset+=100){
          var page=service.submissions(actor,new Query(dataset,null,null,null,null,true,offset,100));
          for(var submission:page)if(submission.state()!=State.RETURNED)consumedDrafts.add(submission.draftId()+":"+submission.draftVersion());
          if(page.size()<100)break;
        }
        if(actor.role()==Role.OPERATOR)for(int offset=0;;offset+=100){
          var page=service.drafts(actor,dataset,offset,100);
          for(var draft:page)if(!consumedDrafts.contains(draft.id()+":"+draft.version()))for(var item:draft.rows()){
            var row=selected.get(item.before().id());if(row==null)continue;
            var schema=DatasetSchema.get(dataset);boolean different=item.change().values().entrySet().stream().anyMatch(e->!row.values.get(schema.index(e.getKey())).equals(e.getValue()));
            if(different&&drafts.putIfAbsent(row.record.id,draft)==null)stale.put(row.record.id,item.change().expectedVersion()!=row.record.versions.get(row.rowIndex));
          }
          if(page.size()<100)break;
        }
      }
      Map<String,RowState> result=new HashMap<>();for(var row:selected.values()){
        var latest=service.recordHistory(actor,row.record.id,0,1);result.put(row.record.id,new RowState(drafts.get(row.record.id),stale.getOrDefault(row.record.id,false),pending.get(row.record.id),latest.isEmpty()?null:latest.get(0)));
      }
      return new BusinessWorkflowState(result,context);
    }
  }
}

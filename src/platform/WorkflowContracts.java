package xinguan.platform;
import java.util.*;

/** Phase A/B integration contract. No permissive production stub is installed. */
public final class WorkflowContracts {
  public enum State { DRAFT, SUBMITTED, APPROVED, RETURNED, CONFLICT }
  public record ChangeSet(String id,String ownerId,String organizationId,String dataset,State state,List<RecordChange> changes,String reason) {
    public ChangeSet {changes=List.copyOf(changes);}
  }
  public interface WorkflowService {
    String saveDraft(ActorContext actor,ChangeSet draft);
    ChangeSet preview(ActorContext actor,String draftId);
    String submit(ActorContext actor,String draftId,String requestId);
    void approve(ActorContext actor,String submissionId,String requestId);
    void reject(ActorContext actor,String submissionId,String reason,String requestId);
  }
  private WorkflowContracts(){}
}

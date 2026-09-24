package xinguan.platform;

import java.time.*;
import java.util.*;

/** Public A/B contract. All identity, scope and state checks are repeated in the store. */
public final class WorkflowContracts {
  private WorkflowContracts() {}
  public enum State { SUBMITTED, PENDING_DIVISION, PARTIAL, APPROVED, RETURNED }
  public enum RowStage { READY, BRANCH_REVIEW, DIVISION_REVIEW, RETURNED, PUBLISHED, LEGACY_PUBLISHED }
  public enum Mode { REVIEW, DIRECT, BATCH_DIRECT, IMPORT }
  public enum Code {
    INVALID_INPUT, NOT_FOUND, VERSION_CONFLICT, CONFIRMATION_EXPIRED,
    ALREADY_SUBMITTED, ALREADY_DECIDED, NO_REVIEWER, OWNER_CHANGED,
    REQUEST_REUSED, TRANSACTION_FAILED
  }
  public record Conflict(BusinessRecord base, BusinessRecord current, RecordChange proposed) {}
  public static final class WorkflowException extends RuntimeException {
    private final Code code;
    private final List<Conflict> conflicts;
    public WorkflowException(Code code, String message) { this(code,message,List.of()); }
    public WorkflowException(Code code, String message, List<Conflict> conflicts) {
      super(message); this.code=code; this.conflicts=List.copyOf(conflicts);
    }
    public Code code() { return code; }
    public List<Conflict> conflicts() { return conflicts; }
  }
  public record FieldDiff(String key, String title, String before, String after) {}
  public record AuditEntry(String id, Instant at, String actorId, String actorName, String actorRole,
                           String recordId, String action, String requestId, String before, String after, String details) {}
  /** One immutable row-level workflow event: who did what to this business row, when and why. */
  public record ItemEvent(String id, Instant at, String submissionId, String action, String stage,
                          String actorName, String actorRole, String reason) {}
  public record SnapshotRow(BusinessRecord before, RecordChange change) {
    public List<FieldDiff> fields() {
      DatasetSchema schema=DatasetSchema.get(before.dataset());
      List<FieldDiff> result=new ArrayList<>();
      for(var field:schema.fields) if(change.values().containsKey(field.key()))
        result.add(new FieldDiff(field.key(),field.title(),before.values().get(schema.index(field.key())),change.values().get(field.key())));
      return List.copyOf(result);
    }
  }
  /** A batch envelope is not an ordinary CZ business submission: rows retain their real branch. */
  public record BranchSnapshot(String organizationId,List<SnapshotRow> rows) {
    public BranchSnapshot { rows=List.copyOf(rows);if(rows.stream().anyMatch(row->!row.before().organizationId().equals(organizationId)))throw new IllegalArgumentException("子快照机构不一致"); }
  }
  public static List<BranchSnapshot> branchSnapshots(List<SnapshotRow> rows){
    Map<String,List<SnapshotRow>> groups=new LinkedHashMap<>();for(var row:rows)groups.computeIfAbsent(row.before().organizationId(),key->new ArrayList<>()).add(row);
    return groups.entrySet().stream().map(e->new BranchSnapshot(e.getKey(),e.getValue())).toList();
  }
  public record Draft(String id, String ownerId, String organizationId, String dataset,
                      long version, Instant updatedAt, String priorSubmissionId, List<SnapshotRow> rows) {
    public Draft { rows=List.copyOf(rows); }
  }
  public record Preview(String id, Mode mode, String ownerId, String organizationId, String dataset,
                        String draftId, long draftVersion, String priorSubmissionId,
                        Instant createdAt, Instant expiresAt, List<SnapshotRow> rows) {
    public Preview { rows=List.copyOf(rows); }
  }
  public record Submission(String id, Mode mode, State state, String ownerId, String ownerName,
                           String organizationId, String dataset, String draftId, long draftVersion,
                           String priorSubmissionId, Instant createdAt, String reviewerId, String reviewerName,
                           Instant decidedAt, String reason, List<SnapshotRow> rows, Map<String,RowStage> rowStages) {
    public Submission { rows=List.copyOf(rows);rowStages=Map.copyOf(rowStages); }
    public Submission(String id,Mode mode,State state,String ownerId,String ownerName,String organizationId,String dataset,
                      String draftId,long draftVersion,String priorSubmissionId,Instant createdAt,String reviewerId,String reviewerName,
                      Instant decidedAt,String reason,List<SnapshotRow> rows) {
      this(id,mode,state,ownerId,ownerName,organizationId,dataset,draftId,draftVersion,priorSubmissionId,createdAt,
        reviewerId,reviewerName,decidedAt,reason,rows,Map.of());
    }
  }
  /** Live row routing, without pending proposal values. */
  public record Progress(String recordId,RowStage stage,String previousActor,String currentHandler,String submissionId) {}
  /** from/through filter source periods, not submission creation dates. Null means no constraint. */
  public record Query(String dataset, String organization, State state, LocalDate from, LocalDate through,
                      boolean mineOnly, int offset, int limit) {
    public static Query firstPage() { return new Query(null,null,null,null,null,false,0,50); }
  }
  public interface WorkflowService {
    /** New draft: blank id and expectedVersion=0. Existing draft: exact latest version. */
    Draft saveDraft(ActorContext actor, String id, long expectedVersion, String dataset,
                    List<RecordChange> changes, String priorSubmissionId, String requestId);
    Draft draft(ActorContext actor, String id);
    /** Owner-only point lookup; rejects consumed revisions, independent of list pagination. */
    Draft editableDraft(ActorContext actor, String id);
    List<Draft> drafts(ActorContext actor, String dataset, int offset, int limit);
    Preview previewDraft(ActorContext actor, String draftId, long expectedVersion);
    Preview previewDirect(ActorContext actor, String dataset, List<RecordChange> changes);
    /** Explicitly reconfirm the retained official values of one currently returned row. */
    Preview previewReturned(ActorContext actor, String recordId, long expectedVersion);
    /** Copies only active returned shared snapshot rows into a new private operator draft. */
    Draft restoreReturned(ActorContext actor, String submissionId, List<String> recordIds, String requestId);
    Preview preview(ActorContext actor, String previewId);
    Mode previewMode(ActorContext actor,String previewId);
    /** Only a stored preview ID is accepted: the client cannot replace its confirmed values. */
    Submission confirm(ActorContext actor, String previewId, String requestId);
    Submission submission(ActorContext actor, String id);
    List<Submission> submissions(ActorContext actor, Query query);
    List<Submission> pendingReviews(ActorContext actor, Query query);
    List<Submission> pendingDivisionReviews(ActorContext actor, Query query);
    /** Only the caller's branch-review rows that were returned by division are revisable. */
    Set<String> branchRevisionRows(ActorContext actor, String submissionId);
    /** Private reviewer edit state for a division-returned row, never part of official data. */
    Draft reviewerRevisionDraft(ActorContext actor, String submissionId, String recordId);
    Draft saveReviewerRevisionDraft(ActorContext actor, String submissionId, String recordId, long expectedVersion,
                                   Map<String,String> proposed, String requestId);
    List<Submission> recordHistory(ActorContext actor, String recordId, int offset, int limit);
    /** Active workflow owner metadata for authorized records; never includes proposed cell values. */
    Map<String,String> currentOwners(ActorContext actor, Collection<String> recordIds);
    Map<String,Progress> progress(ActorContext actor, Collection<String> recordIds);
    /** False for a reopened legacy row without an original operator to receive a reviewer rejection. */
    boolean mayReject(ActorContext actor,String submissionId);
    /** Row-level operation timeline (submitted/reviewed/returned/published) with the operator name. */
    List<ItemEvent> recordEvents(ActorContext actor, String recordId, int offset, int limit);
    List<AuditEntry> auditTrail(ActorContext actor, String submissionId);
    Submission approve(ActorContext actor, String submissionId, String requestId);
    Submission reject(ActorContext actor, String submissionId, String reason, String requestId);
    Submission approveRows(ActorContext actor, String submissionId, List<String> recordIds, String requestId);
    Submission rejectRows(ActorContext actor, String submissionId, List<String> recordIds, String reason, String requestId);
    /** Reviewer replaces one division-returned proposal with a new immutable submission for final review. */
    Submission reviseForDivision(ActorContext actor, String submissionId, String recordId, long expectedVersion, Map<String,String> proposed, String requestId);
    /** Reopens one completed official row without changing its published values/version. */
    String reopenCompleted(ActorContext actor, String recordId, long expectedVersion, String reason, String requestId);
  }
}

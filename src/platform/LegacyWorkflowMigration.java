package xinguan.platform;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Schema 8 history is immutable. Only the new current-state projection is initialized here. */
final class LegacyWorkflowMigration {
  private record Decision(String id,String owner,String state,String reason,Instant at,String payload) {}
  static void apply(Connection db)throws SQLException {
    // Schema 8 allowed distinct operators to submit the same record. Preserve every snapshot
    // and pending entry; select one deterministic active projection until duplicates are returned.
    Set<String> pending=new HashSet<>();
    try(var st=db.createStatement();var rs=st.executeQuery("SELECT p.record_id,p.submission_id,p.owner_id,s.created_at FROM pending_submission_records p JOIN submissions s ON s.id=p.submission_id ORDER BY s.created_at,s.id")){
      while(rs.next())if(pending.add(rs.getString("record_id")))try(var update=db.prepareStatement("UPDATE workflow_record_state SET submission_id=?,owner_id=?,stage='BRANCH_REVIEW',reason='',updated_at=? WHERE record_id=?")){
        update.setString(1,rs.getString("submission_id"));update.setString(2,rs.getString("owner_id"));update.setString(3,rs.getString("created_at"));update.setString(4,rs.getString("record_id"));update.executeUpdate();
      }
    }
    List<Decision> history=new ArrayList<>();
    try(var st=db.createStatement();var rs=st.executeQuery("SELECT * FROM submissions WHERE state IN ('APPROVED','RETURNED')")){
      while(rs.next())history.add(new Decision(rs.getString("id"),rs.getString("owner_id"),rs.getString("state"),Objects.toString(rs.getString("decision_reason"),""),Instant.parse(Objects.toString(rs.getString("decided_at"),rs.getString("created_at"))),rs.getString("payload")));
    }
    history.sort(Comparator.comparing(Decision::at).reversed().thenComparing(d->d.state().equals("APPROVED")?0:1).thenComparing(Decision::id));
    Set<String> seen=new HashSet<>();
    for(Decision d:history)for(var row:WorkflowCodec.rows(d.payload())){
      String recordId=row.before().id();if(seen.contains(recordId))continue;
      try(var st=db.prepareStatement("SELECT o.revision,w.stage FROM official_records o JOIN workflow_record_state w ON w.record_id=o.id WHERE o.id=?")){
        st.setString(1,recordId);try(var rs=st.executeQuery()){
          if(!rs.next()||rs.getString("stage").equals("BRANCH_REVIEW"))continue;
          // An obsolete submission rejected after another author published is not a new business task.
          boolean returned=d.state().equals("RETURNED")&&rs.getLong("revision")==row.before().version();
          if(d.state().equals("RETURNED")&&!returned)continue;
          seen.add(recordId);
          try(var update=db.prepareStatement("UPDATE workflow_record_state SET submission_id=?,owner_id=?,stage=?,reason=?,updated_at=? WHERE record_id=?")){
            update.setString(1,d.id());update.setString(2,d.owner());update.setString(3,returned?"RETURNED":"LEGACY_PUBLISHED");update.setString(4,returned?d.reason():"");update.setString(5,d.at().toString());update.setString(6,recordId);update.executeUpdate();
          }
        }
      }
    }
  }
  private LegacyWorkflowMigration(){}
}

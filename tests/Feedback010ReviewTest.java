package xinguan.platform;

import java.util.*;
import static xinguan.platform.WorkflowContracts.*;
import static xinguan.platform.WorkflowPlatformTest.*;

/** Maintenance regressions for the per-person notification and division-return routing change. */
public final class Feedback010ReviewTest {
  static int checks, failures;
  public static void main(String[] args)throws Exception {
    run("division return goes back to the approving branch reviewer",Feedback010ReviewTest::divisionReturnToReviewer);
    run("notifications stay personal inside a branch",Feedback010ReviewTest::personalNotices);
    if(failures>0)throw new AssertionError("FEEDBACK010_REVIEW_FAILED cases="+failures);
    System.out.println("FEEDBACK010_REVIEW_OK checks="+checks);
  }
  static void run(String name,Work work)throws Exception {try{work.run();System.out.println("PASS "+name);}catch(Throwable e){failures++;System.out.println("FAIL "+name+": "+e);}}
  static void verify(boolean result,String message){checks++;if(!result)throw new AssertionError(message);}

  static void divisionReturnToReviewer()throws Exception {try(var f=new Fixture()){
    var row=f.record("WUJIN","multi");
    var submission=f.pending(f.op,row,"DIVISION-RETURN-ROUTE");
    f.w().approve(f.review,submission.id(),id());
    verify(f.store.find(f.div,row.id()).workflowStage()==RowStage.DIVISION_REVIEW,"branch approval stages the row for division");
    long reviewerNotices=f.n().unreadCount(f.review),otherReviewerNotices=f.n().unreadCount(f.review2),operatorNotices=f.n().unreadCount(f.op);
    f.w().reject(f.div,submission.id(),"division needs the branch to recheck",id());
    verify(f.store.find(f.div,row.id()).workflowStage()==RowStage.BRANCH_REVIEW,"division return reopens branch review instead of the operator queue");
    verify(f.w().submission(f.div,submission.id()).state()==State.SUBMITTED,"submission state returns to branch review");
    verify(f.w().pendingReviews(f.review,Query.firstPage()).stream().anyMatch(s->s.id().equals(submission.id())),"approving reviewer sees the returned row in the branch queue");
    verify(f.n().unreadCount(f.review)==reviewerNotices+1,"approving reviewer is notified of the division return");
    verify(f.n().unreadCount(f.review2)==otherReviewerNotices,"uninvolved branch reviewer is not notified");
    verify(f.n().unreadCount(f.op)==operatorNotices,"operator is not notified before the reviewer decides");
    var events=f.w().recordEvents(f.review,row.id(),0,50);
    verify(events.stream().anyMatch(e->e.action().equals("BRANCH_APPROVED")&&!e.actorName().isBlank())&&events.stream().anyMatch(e->e.action().equals("DIVISION_RETURNED")),"row history exposes review and return events with operator names");
    verify(events.stream().anyMatch(e->!e.submissionId().isEmpty()),"row history events link back to their submission");
    f.w().reject(f.review,submission.id(),"return to operator for rework",id());
    verify(f.store.find(f.op,row.id()).workflowStage()==RowStage.RETURNED,"reviewer can still return the row to the operator");
    verify(f.n().unreadCount(f.op)==operatorNotices+1,"operator is notified when the reviewer returns the row");
  }}

  static void personalNotices()throws Exception {try(var f=new Fixture()){
    var a=f.record("WUJIN","multi");var b=f.record("WUJIN","multi");
    long otherOperatorNotices=f.n().unreadCount(f.op2),otherBranchNotices=f.n().unreadCount(f.otherOp);
    var first=f.pending(f.op,a,"OWN-ROW-A");
    var second=f.pending(f.op2,b,"OWN-ROW-B");
    f.w().approve(f.review,first.id(),id());f.w().approve(f.div,first.id(),id());
    verify(f.n().unreadCount(f.op2)==otherOperatorNotices,"second operator does not receive the first operator's decision");
    verify(f.n().unreadCount(f.otherOp)==otherBranchNotices,"other branch operator is not notified");
    verify(f.n().inbox(f.op,false,0,100).stream().allMatch(n->n.submissionId().isEmpty()||n.submissionId().equals(first.id())),"first operator inbox stays inside its own submission");
    verify(f.n().inbox(f.review2,false,0,100).stream().filter(n->!n.submissionId().isEmpty()).allMatch(n->n.submissionId().equals(first.id())||n.submissionId().equals(second.id())),"branch reviewer only receives own-branch submissions");
  }}
}

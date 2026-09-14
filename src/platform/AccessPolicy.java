package xinguan.platform;

public final class AccessPolicy {
  public enum Action { VIEW, DIRECT_EDIT, SAVE_DRAFT, SUBMIT, REVIEW, UPLOAD }
  private AccessPolicy() {}
  public static boolean all(ActorContext a) { return a!=null&&(a.role()==Role.SUPER_ADMIN||a.role()==Role.DIVISION_ADMIN); }
  public static boolean can(ActorContext a, Action action, String org) {
    if(a==null||(!all(a)&&!a.organizationId().equals(org))) return false;
    return switch(action) {
      case VIEW -> true;
      case UPLOAD -> a.role()==Role.DIVISION_ADMIN;
      case DIRECT_EDIT -> a.role()==Role.DIVISION_ADMIN||a.role()==Role.BRANCH_ADMIN||a.role()==Role.REVIEWER;
      case SAVE_DRAFT,SUBMIT -> a.role()==Role.OPERATOR;
      case REVIEW -> a.role()==Role.REVIEWER;
    };
  }
  public static void require(ActorContext a, Action action, String org) {
    if(!can(a,action,org)) throw new SecurityException("当前账号无权执行此操作");
  }
  public static boolean canManage(ActorContext a,Role target,String org) {
    if(a==null||target==null||target==Role.SUPER_ADMIN) return false;
    if(target==Role.DIVISION_ADMIN?!Organizations.DIVISION.equals(org):!Organizations.BRANCHES.containsKey(org))return false;
    return switch(a.role()) {
      case SUPER_ADMIN -> true;
      case DIVISION_ADMIN -> target==Role.BRANCH_ADMIN||target==Role.OPERATOR||target==Role.REVIEWER;
      case BRANCH_ADMIN -> a.organizationId().equals(org)&&(target==Role.OPERATOR||target==Role.REVIEWER);
      default -> false;
    };
  }
}

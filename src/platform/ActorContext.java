package xinguan.platform;

/** Trusted server-side identity. Never construct from request role/organization fields. */
public record ActorContext(String userId, String name, Role role, String organizationId, long identityRevision) {
  /** Non-persistent identities are for isolated service tests, not HTTP request construction. */
  public ActorContext(String userId,String name,Role role,String organizationId){this(userId,name,role,organizationId,-1);}
  public ActorContext {
    if(userId==null||userId.isBlank()||name==null||role==null) throw new IllegalArgumentException("身份不完整");
    boolean division=role==Role.SUPER_ADMIN||role==Role.DIVISION_ADMIN;
    if(division?!Organizations.DIVISION.equals(organizationId):!Organizations.BRANCHES.containsKey(organizationId))
      throw new IllegalArgumentException("角色与所属机构不匹配");
  }
}

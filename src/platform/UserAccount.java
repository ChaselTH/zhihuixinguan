package xinguan.platform;

/** Safe user projection: never contains password hashes or initial passwords. */
public record UserAccount(String id,String authNumber,String name,Role role,String organizationId,boolean active,boolean mustChangePassword,long revision) {
  public ActorContext actor(){return new ActorContext(id,name,role,organizationId,revision);}
}

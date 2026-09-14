package xinguan.platform;
import java.util.*;
public interface OfficialDataWriter {
  String publishDirect(ActorContext actor,List<RecordChange> changes,String requestId);
}

package xinguan.platform;
import java.util.*;
public interface OfficialDataWriter {
  /** Retained only for binary/source compatibility. PlatformStore always rejects this legacy bypass. */
  @Deprecated
  String publishDirect(ActorContext actor,List<RecordChange> changes,String requestId);
}

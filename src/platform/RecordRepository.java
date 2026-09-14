package xinguan.platform;
import java.time.*;
import java.util.*;
public interface RecordRepository {
  List<BusinessRecord> list(ActorContext actor,String dataset,LocalDate from,LocalDate through);
  BusinessRecord find(ActorContext actor,String recordId);
}

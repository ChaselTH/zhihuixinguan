package xinguan.platform;

import java.time.Instant;
import java.util.List;

public interface NotificationService {
  record Notice(String id, String type, String organizationId, String title, String summary,
                String submissionId, Instant createdAt, Instant readAt) {}
  List<Notice> inbox(ActorContext actor, boolean unreadOnly, int offset, int limit);
  long unreadCount(ActorContext actor);
  void markRead(ActorContext actor, String noticeId);
  /** Manager-only administrative notice; cannot forge workflow events or arbitrary navigation URLs. */
  String publishNotice(ActorContext actor, String organization, String title, String summary,
                       List<String> recipientIds, String requestId);
}

import java.util.Set;

/** Explicit same-site navigation origin. Never trust the browser Referer for a back link. */
final class ReturnNavigation {
  private static final Set<String> PAGES=Set.of(
    "/","/details","/branch","/progress","/internal","/audit","/records/history",
    "/notifications","/notifications/detail","/access/requests","/access/request",
    "/workflow","/workflow/drafts","/workflow/submissions","/workflow/reviews",
    "/workflow/submission","/workflow/record-history","/workflow/review/edit",
    "/imports","/imports/jobs","/completion-rules","/deadlines","/people");
  private ReturnNavigation() {}
  static String safe(String candidate,String fallback) {
    if(candidate==null||candidate.isBlank())return fallback;
    String target=candidate.strip();
    if(target.length()>2048||target.indexOf('\\')>=0||target.indexOf('#')>=0||target.chars().anyMatch(c->c<32||c==127))return fallback;
    String path=target.split("\\?",2)[0];return PAGES.contains(path)?target:fallback;
  }
  static String link(String target,String source) {
    return target+(target.indexOf('?')>=0?"&":"?")+"return="+PageLayout.u(source);
  }
}

package xinguan.platform;

import java.time.*;
import java.time.temporal.ChronoUnit;

/** Beijing time is fixed for every client and server host; the deadline day is inclusive. */
public final class FeedbackTiming {
  public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
  private FeedbackTiming(){}
  public static boolean overdue(LocalDate due,boolean complete,Instant now){
    return due!=null&&!complete&&!now.isBefore(due.plusDays(1).atStartOfDay(ZONE).toInstant());
  }
  public static String remaining(LocalDate due,Instant now){
    if(due==null)return "未设置截止日期";
    if(overdue(due,false,now))return "已超期 "+ChronoUnit.DAYS.between(due,now.atZone(ZONE).toLocalDate())+" 天";
    long minutes=(Duration.between(now,due.plusDays(1).atStartOfDay(ZONE).toInstant()).getSeconds()+59)/60;
    if(minutes>=1440)return "剩余 "+minutes/1440+" 天 "+minutes%1440/60+" 小时";
    return "今日截止，剩余 "+minutes/60+" 小时 "+minutes%60+" 分钟";
  }
}

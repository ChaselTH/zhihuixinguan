package xinguan.platform;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.regex.*;

public record Period(String key,LocalDate start,LocalDate end) {
  private static final Pattern RANGE=Pattern.compile("(20\\d{2})[-./]?(\\d{2})[-./]?(\\d{2})\\s*[-~～至到_]\\s*(20\\d{2})[-./]?(\\d{2})[-./]?(\\d{2})");
  public Period { if(key==null||key.isBlank()||start==null||end==null||start.isAfter(end)||end.isAfter(start.plusYears(10)))throw new IllegalArgumentException("时间段无效"); }
  public static Period parse(String value,String month) {
    String text=value==null?"":value.strip();
    Matcher r=RANGE.matcher(text);
    if(r.matches()) {
      LocalDate a=LocalDate.parse(r.group(1)+r.group(2)+r.group(3),DateTimeFormatter.BASIC_ISO_DATE);
      LocalDate b=LocalDate.parse(r.group(4)+r.group(5)+r.group(6),DateTimeFormatter.BASIC_ISO_DATE);
      return new Period(a+"~"+b,a,b);
    }
    if(text.matches("20\\d{2}-\\d{2}"))month=text;
    else if(!text.isEmpty()&&!text.matches("20\\d{2}-\\d{2}-P[12]"))throw new IllegalArgumentException("时间顺序格式无效："+text+"；请使用 YYYYMMDD-YYYYMMDD");
    if(month==null||!month.matches("20\\d{2}-\\d{2}"))throw new IllegalArgumentException("缺少数据月份，请指定 YYYY-MM");
    YearMonth ym=YearMonth.parse(month);
    return new Period(text.isEmpty()?ym.toString():text,ym.atDay(1),ym.atEndOfMonth());
  }
}

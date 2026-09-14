package xinguan.platform;

import java.util.*;

public final class Organizations {
  public static final String DIVISION = "CZ";
  public static final String UNASSIGNED = "UNASSIGNED";
  public static final Map<String,String> BRANCHES;
  static {
    LinkedHashMap<String,String> m=new LinkedHashMap<>();
    m.put("WUJIN","武进"); m.put("JINTAN","金坛"); m.put("LIYANG","溧阳");
    m.put("XINQU","新区"); m.put("JINGKAI","经开"); m.put("TIANNING","天宁");
    m.put("ZHONGLOU","钟楼"); m.put("YINGYEBU","营业部"); m.put("ZHONGWU","中吴");
    BRANCHES=Collections.unmodifiableMap(m);
  }
  private Organizations() {}
  public static String resolve(String value) {
    String name=value==null?"":value.strip().replace(" ","").replace("　","");
    if(BRANCHES.containsKey(name)) return name;
    for(var e:BRANCHES.entrySet()) {
      for(String prefix:List.of("","常州","常州分行","工行常州","中国工商银行股份有限公司常州","中国工商银行常州")) {
        if(name.equals(prefix+e.getValue())||name.equals(prefix+e.getValue()+"支行")) return e.getKey();
      }
    }
    throw new IllegalArgumentException("无法识别支行："+name+"；请使用九家支行的正式名称");
  }
  public static String label(String id) { return DIVISION.equals(id)?"分行":UNASSIGNED.equals(id)?"待确认机构":BRANCHES.getOrDefault(id,id); }
}

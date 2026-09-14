package xinguan.platform;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
public final class Codec {
  private Codec(){}
  public static String encode(List<String> values){List<String> a=new ArrayList<>();for(String v:values)a.add(Base64.getEncoder().encodeToString((v==null?"":v).getBytes(StandardCharsets.UTF_8)));return String.join("\t",a);}
  public static List<String> decode(String text){List<String> a=new ArrayList<>();for(String v:text.split("\t",-1))a.add(new String(Base64.getDecoder().decode(v),StandardCharsets.UTF_8));return a;}
  public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}

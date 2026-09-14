package xinguan.platform;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.PBEKeySpec;

final class Passwords {
  private static final SecureRandom RANDOM=new SecureRandom();
  record Encoded(String salt,String hash){}
  static Encoded encode(String password){byte[] salt=new byte[24];RANDOM.nextBytes(salt);return new Encoded(Base64.getEncoder().encodeToString(salt),Base64.getEncoder().encodeToString(derive(password,salt)));}
  static boolean matches(String password,String salt,String hash){if(password==null||password.length()>128)return false;try{return MessageDigest.isEqual(derive(password,Base64.getDecoder().decode(salt)),Base64.getDecoder().decode(hash));}catch(IllegalArgumentException e){return false;}}
  static String temporary(){byte[] bytes=new byte[15];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
  static void validate(String value){if(value==null||value.length()<10||value.length()>128||value.isBlank())throw new IllegalArgumentException("新密码需要 10～128 位，不能全为空格");}
  private static byte[] derive(String password,byte[] salt){PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,600000,256);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}catch(GeneralSecurityException e){throw new IllegalStateException(e);}finally{spec.clearPassword();}}
}

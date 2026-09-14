import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import xinguan.platform.PlatformStore;

/** Deployment-local, one-time initialization. No shared or fallback credentials. */
final class BootstrapConfig {
  static final String FILE_NAME="bootstrap.local.properties";
  static boolean initialize(PlatformStore users,Path file)throws IOException {
    // A stale, removed or invalid configuration must never reset an existing account.
    if(users.hasUsers())return false;
    if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("账号库为空，请在应用目录配置 bootstrap.local.properties；参考 bootstrap.example.properties，勿上传已填写的配置");
    byte[] bytes;
    try(InputStream in=Files.newInputStream(file)){bytes=in.readNBytes(8193);}
    if(bytes.length>8192)throw new IOException("本地初始化配置超过 8 KB");
    Properties settings=new Properties();
    try {settings.load(new StringReader(new String(bytes,StandardCharsets.UTF_8)));}
    catch(IllegalArgumentException e){throw new IOException("本地初始化配置格式错误，请参考空白示例");}
    if(!Set.of("auth_number","password").containsAll(settings.stringPropertyNames()))throw new IOException("初始化配置仅支持 auth_number 和 password 两项");
    String number=settings.getProperty("auth_number",""),password=settings.getProperty("password");
    if(!number.matches("[0-9]{6,20}")||password==null||password.length()<10||password.length()>128||password.isBlank())throw new IOException("请在本地配置填写 6～20 位数字认证号及 10～128 位密码，原值不会在错误信息中显示");
    users.bootstrapSuperAdmin(number,password);
    return true;
  }
}

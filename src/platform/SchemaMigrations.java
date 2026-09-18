package xinguan.platform;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** H2 DDL can commit implicitly. Interrupted migrations fail closed and require a verified backup. */
final class SchemaMigrations {
  static final int CURRENT_VERSION=8;
  private static final List<String> FILES=List.of("V001__foundation.sql","V002__workflow_platform.sql","V003__access_platform.sql","V004__import_jobs.sql","V005__feedback_roles_credentials.sql","V006__feedback_deadlines.sql","V007__record_deletions.sql","V008__completion_rules.sql");
  static void apply(Connection db, Consumer<String> checkpoint) throws Exception {
    List<String> scripts=new ArrayList<>();
    for(String file:FILES) try(InputStream in=SchemaMigrations.class.getResourceAsStream("/db/"+file)) {
      if(in==null) throw new IOException("缺少数据库迁移资源："+file);
      scripts.add(new String(in.readAllBytes(),StandardCharsets.UTF_8));
    }
    execute(db,"CREATE TABLE IF NOT EXISTS schema_migrations(version INT PRIMARY KEY, checksum VARCHAR(64) NOT NULL, applied_at VARCHAR(40) NOT NULL)");
    int applied=0;
    try(Statement st=db.createStatement();ResultSet rs=st.executeQuery("SELECT version,checksum FROM schema_migrations ORDER BY version")) {
      while(rs.next()) {
        int version=rs.getInt(1);
        if(version!=applied+1||version>scripts.size()||!Codec.hash(scripts.get(version-1)).equals(rs.getString(2)))
          throw new IOException("数据库版本或迁移校验不匹配；禁止使用旧程序覆盖新数据库");
        applied=version;
      }
    }
    execute(db,"CREATE TABLE IF NOT EXISTS schema_migration_attempts(version INT PRIMARY KEY, checksum VARCHAR(64) NOT NULL, started_at VARCHAR(40) NOT NULL, state VARCHAR(20) NOT NULL)");
    try(Statement st=db.createStatement();ResultSet rs=st.executeQuery("SELECT version,checksum,state FROM schema_migration_attempts ORDER BY version")) {
      while(rs.next()) {
        int version=rs.getInt(1);
        if(version<1||version>applied||!"DONE".equals(rs.getString(3))||!Codec.hash(scripts.get(version-1)).equals(rs.getString(2)))
          throw new IOException("检测到未完成的数据库迁移；请保全当前目录并按升级备份恢复，禁止自动继续");
      }
    }
    for(int index=applied;index<scripts.size();index++) {
      int version=index+1;String checksum=Codec.hash(scripts.get(index));
      try(PreparedStatement st=db.prepareStatement("INSERT INTO schema_migration_attempts VALUES(?,?,?,'STARTED')")) {
        st.setInt(1,version);st.setString(2,checksum);st.setString(3,Instant.now().toString());st.executeUpdate();
      }
      try {
        int step=0;
        for(String sql:scripts.get(index).split(";")) if(!sql.isBlank()) {
          execute(db,sql);checkpoint.accept("migration-"+version+"-step-"+(++step));
        }
        db.setAutoCommit(false);
        try(PreparedStatement st=db.prepareStatement("INSERT INTO schema_migrations VALUES(?,?,?)")) {
          st.setInt(1,version);st.setString(2,checksum);st.setString(3,Instant.now().toString());st.executeUpdate();
        }
        execute(db,"UPDATE schema_migration_attempts SET state='DONE' WHERE version="+version);
        db.commit();
      } catch(Exception e) {
        if(!db.getAutoCommit()) db.rollback();
        throw new IOException("数据库迁移未完成；请保全当前目录并使用升级备份恢复",e);
      } finally { db.setAutoCommit(true); }
    }
  }
  private static void execute(Connection db,String sql)throws SQLException {
    try(Statement st=db.createStatement()) { st.execute(sql); }
  }
  private SchemaMigrations() {}
}

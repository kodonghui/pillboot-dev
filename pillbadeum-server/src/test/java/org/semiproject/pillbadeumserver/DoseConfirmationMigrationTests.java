package org.semiproject.pillbadeumserver;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

/** 07 최초 적용과 재실행의 backfill을 전용 DB 안의 독립 fixture 표에서 확인함. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class DoseConfirmationMigrationTests {
  @Autowired DataSource dataSource;

  @Test
  void firstApplyMarksOnlyKnownSchedulesAndReapplyPreservesUserConfirmation() throws Exception {
    String table = "bug_dose_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      assertThat(connection.getCatalog()).isEqualTo("pill_test");
      statement.execute("CREATE TABLE " + table + "(id INT PRIMARY KEY,dose_per_time INT,start_date DATE,end_date DATE,dose_times VARCHAR(50))");
      try {
        statement.executeUpdate("INSERT INTO " + table + " VALUES(1,2,'2026-10-21','2026-10-22','08:00'),(2,1,NULL,NULL,NULL),(3,4,NULL,NULL,NULL)");
        var script = new ByteArrayResource(Files.readString(Path.of("sql/07_DOSE_CONFIRMATION.sql")).replace("my_drug", table).getBytes(StandardCharsets.UTF_8));
        ScriptUtils.executeSqlScript(connection, script);
        try (var rows = statement.executeQuery("SELECT id,dose_per_time,dose_confirmed FROM " + table + " ORDER BY id")) {
          assertThat(rows.next()).isTrue(); assertThat(rows.getInt(2)).isEqualTo(2); assertThat(rows.getBoolean(3)).isTrue();
          assertThat(rows.next()).isTrue(); assertThat(rows.getInt(2)).isEqualTo(1); assertThat(rows.getBoolean(3)).isFalse();
          assertThat(rows.next()).isTrue(); assertThat(rows.getInt(2)).isEqualTo(4); assertThat(rows.getBoolean(3)).isFalse();
        }
        statement.executeUpdate("UPDATE " + table + " SET dose_confirmed=TRUE WHERE id=2");
        ScriptUtils.executeSqlScript(connection, script);
        try (var row = statement.executeQuery("SELECT dose_confirmed FROM " + table + " WHERE id=2")) {
          assertThat(row.next()).isTrue(); assertThat(row.getBoolean(1)).isTrue();
        }
      } finally { statement.execute("DROP TABLE " + table); }
    }
  }
}

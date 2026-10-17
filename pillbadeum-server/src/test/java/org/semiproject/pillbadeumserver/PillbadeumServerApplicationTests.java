package org.semiproject.pillbadeumserver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;

/** 실제 MySQL 검증은 pill_test 전용 DB를 명시했을 때만 실행합니다. CI에서 항상 실행합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class PillbadeumServerApplicationTests {
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void mysqlSchemaSeedsAndExistingDataArePreserved() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT DATABASE()")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).as("Only the dedicated pill_test database may be modified")
                        .isEqualTo("pill_test");
            }
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/01_PILLBADEUM_DB_SCRIPT.sql"));
            Set<String> tables = new HashSet<>();
            try (var rows = statement.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()")) {
                while (rows.next()) tables.add(rows.getString(1));
            }
            Set<String> expectedTables = Set.of("MEMBER", "CONSENT", "PROFILE", "CONSENT_HISTORY",
                    "notification_setting", "DRUG", "my_drug", "chat_session", "chat_message", "chat_summary",
                    "PHOTO", "OCR_CANDIDATE", "DUR_WARNING", "DUR_INTERACTION");
            // Windows MySQL은 이름을 소문자로 보관할 수 있습니다. Linux 기본값에서는 원문 대소문자까지 검증합니다.
            try (var rows = statement.executeQuery("SELECT @@lower_case_table_names")) {
                rows.next();
                if (rows.getInt(1) != 0) {
                    tables = tables.stream().map(name -> name.toLowerCase(java.util.Locale.ROOT))
                            .collect(java.util.stream.Collectors.toSet());
                    expectedTables = expectedTables.stream().map(name -> name.toLowerCase(java.util.Locale.ROOT))
                            .collect(java.util.stream.Collectors.toSet());
                }
            }
            assertThat(tables).containsExactlyInAnyOrderElementsOf(expectedTables);
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM MEMBER")) {
                rows.next();
                if (rows.getInt(1) == 0) {
                    // 롤백해도 AUTO_INCREMENT는 되돌아가지 않습니다. 샘플이 ID 1을 가정하지 않는지 검증합니다.
                    connection.setAutoCommit(false);
                    try {
                        statement.executeUpdate("INSERT INTO MEMBER(email,password_hash,nickname,user_role,created_at,updated_at) "
                                + "VALUES('seed-rollback@example.test','test-only','fixture','ROLE_USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                        statement.executeUpdate("INSERT INTO CONSENT(terms_type,terms_version,terms_title,terms_content,"
                                + "is_required,is_active,effective_at,created_at) "
                                + "VALUES('ROLLBACK_TEST','test','fixture','fixture',FALSE,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                    } finally {
                        connection.rollback();
                        connection.setAutoCommit(true);
                    }
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/02_PILLBADEUM_TEST_DATA.sql"));
                }
            }
            // 생성 SQL을 다시 실행해도 샘플 데이터가 지워지지 않아야 합니다.
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/01_PILLBADEUM_DB_SCRIPT.sql"));
            assertRows(connection, "MEMBER", 5);
            assertRows(connection, "PROFILE", 15);
            assertRows(connection, "DRUG", 2);
            assertRows(connection, "my_drug", 30);
            assertRows(connection, "chat_session", 45);
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM chat_session cs JOIN PROFILE p ON p.profile_id=cs.profile_id "
                    + "WHERE cs.snap_pregnant IS NULL OR cs.snap_pregnant<>p.profile_pregnant")) {
                rows.next();
                assertThat(rows.getLong(1)).as("Seed snapshots must preserve the profile pregnancy boolean").isZero();
            }
            assertRows(connection, "chat_message", 180);
            assertRows(connection, "chat_summary", 45);
            for (String table : Set.of("PHOTO", "OCR_CANDIDATE", "DUR_WARNING", "DUR_INTERACTION")) {
                assertRows(connection, table, 0);
            }
            connection.setAutoCommit(false);
            try {
                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO notification_setting(member_id) VALUES(-9223372036854775807)"))
                        .isInstanceOf(SQLException.class);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void fullApplicationStartsWithHealthSwaggerAndTokenSecurity() throws Exception {
        var health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(json.readTree(health.body()).path("status").asString()).isEqualTo("UP");
        assertThat(health.headers().firstValue("X-Request-Id")).isPresent();
        var docs = get("/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(json.readTree(docs.body()).path("openapi").asString()).startsWith("3.");
        assertThat(json.readTree(docs.body()).path("components").path("securitySchemes")
                .path("bearerAuth").path("scheme").asString()).isEqualTo("bearer");
        assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(200);
        var denied = get("/api/members");
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(json.readTree(denied.body()).path("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(denied.headers().allValues("Set-Cookie")).noneMatch(value -> value.contains("JSESSIONID"));
    }

    private static void assertRows(Connection connection, String table, long expected) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).as(table + " sample rows").isEqualTo(expected);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

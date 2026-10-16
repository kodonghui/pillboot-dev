package org.semiproject.pillbadeumserver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
            assertThat(tables).containsExactlyInAnyOrder("member", "terms", "profile", "consent_history",
                    "notification_setting", "drug", "my_drug", "chat_session", "chat_message", "chat_summary");
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM member")) {
                rows.next();
                if (rows.getInt(1) == 0) {
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/02_PILLBADEUM_TEST_DATA.sql"));
                }
            }
            // 생성 SQL을 다시 실행해도 샘플 데이터가 지워지지 않아야 합니다.
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/01_PILLBADEUM_DB_SCRIPT.sql"));
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM chat_message")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(180);
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
        assertThat(json.readTree(docs.body()).path("paths").size()).isZero();
        assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(200);
        var denied = get("/api/members");
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(json.readTree(denied.body()).path("code").asString()).isEqualTo("COMMON_003");
        assertThat(denied.headers().allValues("Set-Cookie")).noneMatch(value -> value.contains("JSESSIONID"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

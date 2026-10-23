package org.semiproject.pillbadeumserver.member;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.TestDatabaseSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AUTH 회원가입: 같은 이메일 가입 두 건이 사전 중복 확인을 함께 통과해도 409로 끝나는지 실제 MySQL에서 확인함.
 * 다른 연결이 같은 이메일을 넣고 커밋하기 전에 가입 요청이 확인·INSERT까지 진행하도록 행 잠금으로 순서를 고정함.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class SignupDuplicateMySqlTests {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();
    private final String email = "race-" + UUID.randomUUID().toString().substring(0, 8) + "@pillbadeum.test";
    private long termsId;

    @BeforeEach
    void prepareRequiredConsentInTheDedicatedTestDatabase() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        try (var connection = dataSource.getConnection()) {
            TestDatabaseSchema.prepare(connection);
        }
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO consent(terms_type, terms_version, terms_title, terms_content, is_required, is_active, effective_at, created_at)
                    VALUES('TEST_RACE', '1.0', '동시 가입 테스트', '테스트 전용 약관', TRUE, TRUE, '2026-01-01 00:00:00', '2026-01-01 00:00:00')
                    """, new String[]{"terms_id"});
            return statement;
        }, key);
        termsId = key.getKey().longValue();
    }

    @AfterEach
    void removeOnlyThisTestsRows() {
        jdbc.update("DELETE h FROM consent_history h JOIN member m ON m.member_id=h.member_id WHERE m.email=?", email);
        jdbc.update("DELETE FROM consent_history WHERE terms_id=?", termsId);
        jdbc.update("DELETE FROM member WHERE email=?", email);
        jdbc.update("DELETE FROM consent WHERE terms_id=?", termsId);
    }

    @Test
    void sameEmailSignupThatPassedTheCheckTogetherReturnsConflictNotServerError() throws Exception {
        List<Long> activeTerms = jdbc.queryForList(
                "SELECT terms_id FROM consent WHERE is_active=TRUE AND effective_at <= NOW()", Long.class);
        String body = json.writeValueAsString(java.util.Map.of(
                "email", email, "password", "password1234", "nickname", "동시가입", "agreedTermsIds", activeTerms));

        try (var other = dataSource.getConnection(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            // 먼저 온 가입 요청이 같은 이메일을 넣었지만 아직 커밋하지 않은 상태를 만든다.
            other.setAutoCommit(false);
            try (var insert = other.prepareStatement("""
                    INSERT INTO member(email, password_hash, nickname, user_role, created_at, updated_at)
                    VALUES(?, 'not-a-login-hash', '먼저가입', 'ROLE_USER', ?, ?)
                    """)) {
                var now = Timestamp.valueOf(LocalDateTime.now());
                insert.setString(1, email);
                insert.setTimestamp(2, now);
                insert.setTimestamp(3, now);
                insert.executeUpdate();
            }
            var signup = callers.submit(() -> mvc.perform(post("/api/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn());
            // 두 번째 요청의 existsByEmail은 커밋 전 행을 보지 못하고 INSERT에서 UNIQUE 잠금을 기다린다.
            waitUntilMemberInsertIsBlocked();
            other.commit();

            MvcResult result = signup.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            var error = json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(error.path("code").asString()).isEqualTo("CONFLICT");
            assertThat(error.path("message").asString()).isEqualTo("이미 가입된 이메일입니다.");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE email=?", Integer.class, email)).isEqualTo(1);
    }

    /** 같은 DB 사용자의 연결만 보이는 SHOW PROCESSLIST로 INSERT가 잠금을 기다리는지 확인함. */
    private void waitUntilMemberInsertIsBlocked() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            boolean blocked = jdbc.queryForList("SHOW FULL PROCESSLIST").stream()
                    .map(row -> String.valueOf(row.get("Info")).toLowerCase())
                    .anyMatch(info -> info.startsWith("insert into") && info.contains("member"));
            if (blocked) return;
            Thread.sleep(50);
        }
        throw new AssertionError("가입 요청의 INSERT가 잠금 대기에 들어가지 않았습니다.");
    }
}

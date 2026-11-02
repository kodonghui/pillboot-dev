package org.semiproject.pillbadeumserver.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.TestDatabaseSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

/** V3-CHAT-002~005·007(TASK-021): 16 패치와 제안 표의 제약, 한 번만 확정되는 상태 변경을 전용 pill_test에서 확인함. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class ChatProposalMySqlTests {
    /** 서버가 확정·취소·만료에 쓰는 조건부 UPDATE와 같은 모양임. 바뀐 행이 0이면 409로 답함. */
    private static final String CONFIRM = "UPDATE chat_proposal SET status='CONFIRMED', decided_at=CURRENT_TIMESTAMP "
            + "WHERE proposal_id=? AND status='PENDING' AND expires_at > CURRENT_TIMESTAMP";

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    private long member, profile, session;
    private String code;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = dataSource.getConnection()) {
            TestDatabaseSchema.prepare(connection);
        }
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        code = "proposal-" + UUID.randomUUID();
        member = insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) "
                + "VALUES(?,'synthetic','fixture','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", UUID.randomUUID() + "@example.test");
        profile = insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,"
                + "profile_pregnant,created_at,updated_at) VALUES(?,'fixture','SELF',2000,'FEMALE',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", member);
        session = insert("INSERT INTO chat_session(member_id,profile_id,created_at,snap_birth_year,snap_gender) "
                + "VALUES(?,?,CURRENT_TIMESTAMP,2000,'FEMALE')", member, profile);
        for (String item : new String[]{code, code + "-2"}) {
            jdbc.update("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) "
                    + "VALUES(?,'synthetic','synthetic','OTC','synthetic','ROUND','WHITE','TABLET','synthetic',CURRENT_TIMESTAMP)", item);
        }
    }

    @AfterEach
    void clean() {
        // 제안·항목은 회원·프로필·메시지를 지우면 함께 지워짐(ON DELETE CASCADE).
        jdbc.update("DELETE FROM chat_message WHERE session_id=?", session);
        jdbc.update("DELETE FROM chat_session WHERE session_id=?", session);
        jdbc.update("DELETE FROM my_drug WHERE profile_id=?", profile);
        jdbc.update("DELETE FROM profile WHERE profile_id=?", profile);
        jdbc.update("DELETE FROM member WHERE member_id=?", member);
        jdbc.update("DELETE FROM drug WHERE item_seq IN (?,?)", code, code + "-2");
    }

    @Test
    void patch16AddsTheSameProposalTablesToAnExistingDatabaseAndCanRunAgain() throws Exception {
        // 실제 표를 지우지 않도록 이름을 바꾼 사본에 적용함. 제약 이름도 표 이름을 품고 있어 함께 바뀜.
        String prefix = "t" + UUID.randomUUID().toString().substring(0, 8) + "_";
        var script = new ByteArrayResource(Files.readString(Path.of("sql/16_CHAT_PROPOSAL.sql"))
                .replace("chat_proposal", prefix + "chat_proposal").getBytes(StandardCharsets.UTF_8));
        String[] tables = {prefix + "chat_proposal_schedule", prefix + "chat_proposal_item", prefix + "chat_proposal"};
        try (var connection = dataSource.getConnection()) {
            try {
                ScriptUtils.executeSqlScript(connection, script);
                ScriptUtils.executeSqlScript(connection, script);   // 다시 적용해도 오류 없이 그대로임

                assertThat(columns(prefix + "chat_proposal")).isEqualTo(columns("chat_proposal")).isEqualTo(9);
                assertThat(columns(prefix + "chat_proposal_item")).isEqualTo(columns("chat_proposal_item")).isEqualTo(8);
                assertThat(columns(prefix + "chat_proposal_schedule")).isEqualTo(columns("chat_proposal_schedule")).isEqualTo(4);
                assertThat(constraints(prefix + "chat_proposal", "CHECK")).isEqualTo(constraints("chat_proposal", "CHECK")).isEqualTo(4);
                assertThat(constraints(prefix + "chat_proposal_item", "CHECK")).isEqualTo(3);
                assertThat(constraints(prefix + "chat_proposal_item", "FOREIGN KEY")).isEqualTo(3);
                assertThat(jdbc.queryForObject("SELECT delete_rule FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema=DATABASE() AND constraint_name=?", String.class,
                        "fk_" + prefix + "chat_proposal_item_my_drug")).isEqualTo("SET NULL");
            } finally {
                try (var statement = connection.createStatement()) {
                    for (String table : tables) statement.execute("DROP TABLE IF EXISTS " + table);
                }
            }
        }
    }

    @Test
    void onlyOneOfTwoConcurrentConfirmationsChangesThePendingProposal() throws Exception {
        long proposal = proposal(message(), "REGISTER", 30);
        try (var first = dataSource.getConnection(); var second = dataSource.getConnection();
             var worker = Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            assertThat(update(first, CONFIRM, proposal)).isEqualTo(1);
            // 뒤 확정은 앞 확정이 끝날 때까지 행 잠금을 기다렸다가, 이미 대기가 아니므로 아무 행도 바꾸지 않음.
            var late = worker.submit(() -> update(second, CONFIRM, proposal));
            assertThatThrownBy(() -> late.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            first.commit();
            assertThat(late.get(10, TimeUnit.SECONDS)).isZero();
            second.commit();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM chat_proposal WHERE proposal_id=?", String.class, proposal))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.update(CONFIRM, proposal)).as("확정된 제안은 다시 확정되지 않음").isZero();

        long expired = proposal(message(), "SCHEDULE", -1);
        assertThat(jdbc.update(CONFIRM, expired)).as("만료 시각이 지난 대기 제안은 확정되지 않음").isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM chat_proposal WHERE proposal_id=?", String.class, expired))
                .isEqualTo("PENDING");
    }

    @Test
    void constraintsKeepStatesOneWayAndOneSavedDrugPerItem() throws Exception {
        long message = message();
        long proposal = proposal(message, "REGISTER", 30);
        String proposalInsert = "INSERT INTO chat_proposal(member_id,profile_id,message_id,kind,status,created_at,expires_at,decided_at) "
                + "VALUES(?,?,?,?,?,'2026-11-02 10:00:00',?,?)";
        String itemInsert = "INSERT INTO chat_proposal_item(proposal_id,item_no,item_seq,strength_match,already_registered,chosen,my_drug_id) "
                + "VALUES(?,?,?,?,?,?,?)";
        try (var connection = dataSource.getConnection()) {
            long other = message();
            assertSqlError(connection, 3819, proposalInsert, member, profile, other, "OTHER", "PENDING", "2026-11-02 10:30:00", null);
            assertSqlError(connection, 3819, proposalInsert, member, profile, other, "REGISTER", "DONE", "2026-11-02 10:30:00", null);
            assertSqlError(connection, 3819, proposalInsert, member, profile, other, "REGISTER", "PENDING", "2026-11-02 10:00:00", null);
            assertSqlError(connection, 3819, proposalInsert, member, profile, other, "REGISTER", "PENDING", "2026-11-02 10:30:00", "2026-11-02 10:10:00");
            assertSqlError(connection, 3819, proposalInsert, member, profile, other, "REGISTER", "CONFIRMED", "2026-11-02 10:30:00", null);
            assertSqlError(connection, 1062, proposalInsert, member, profile, message, "REGISTER", "PENDING", "2026-11-02 10:30:00", null);

            assertSqlError(connection, 3819, itemInsert, proposal, 11, code, "MATCH", 0, 0, null);
            assertSqlError(connection, 3819, itemInsert, proposal, 1, code, "SAME", 0, 0, null);
            assertSqlError(connection, 3819, itemInsert, proposal, 1, code, "MATCH", 1, 1, null);
            update(connection, itemInsert, proposal, 1, code, "MATCH", 0, 0, null);
            assertSqlError(connection, 1062, itemInsert, proposal, 2, code, "DIFFERENT", 0, 0, null);
            update(connection, itemInsert, proposal, 2, code + "-2", "UNKNOWN", 0, 0, null);
        }

        // 확정: 고른 항목마다 약목록 약 하나. 같은 약목록 약을 두 항목이 가리킬 수 없음.
        long saved = insert("INSERT INTO my_drug(profile_id,item_seq,dose_per_time,source) VALUES(?,?,1,'CHAT')", profile, code);
        assertThat(jdbc.update(CONFIRM, proposal)).isEqualTo(1);
        jdbc.update("UPDATE chat_proposal_item SET chosen=1, my_drug_id=? WHERE proposal_id=? AND item_no=1", saved, proposal);
        assertThatThrownBy(() -> jdbc.update("UPDATE chat_proposal_item SET chosen=1, my_drug_id=? WHERE proposal_id=? AND item_no=2",
                saved, proposal)).hasRootCauseInstanceOf(SQLException.class).hasMessageContaining("uq_chat_proposal_item_my_drug");

        // 약목록에서 지워도 확정 기록은 남고 약 ID만 비워짐(여러 개 삭제가 막히지 않음).
        assertThat(jdbc.update("DELETE FROM my_drug WHERE my_drug_id=?", saved)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT chosen, my_drug_id FROM chat_proposal_item WHERE proposal_id=? AND item_no=1", proposal))
                .containsEntry("chosen", true).containsEntry("my_drug_id", null);

        // 일정 제안도 대상 약을 지우면 약 ID만 비워짐.
        long scheduled = insert("INSERT INTO my_drug(profile_id,item_seq,dose_per_time,source) VALUES(?,?,1,'SEARCH')", profile, code + "-2");
        long schedule = proposal(message(), "SCHEDULE", 30);
        jdbc.update("INSERT INTO chat_proposal_schedule(proposal_id,my_drug_id,current_schedule,proposed_schedule) VALUES(?,?,?,?)",
                schedule, scheduled, "{\"doseTimes\":[\"07:00\"]}", "{\"doseTimes\":[\"08:00\"]}");
        jdbc.update("DELETE FROM my_drug WHERE my_drug_id=?", scheduled);
        assertThat(jdbc.queryForObject("SELECT my_drug_id FROM chat_proposal_schedule WHERE proposal_id=?", Long.class, schedule)).isNull();

        // 카드를 보여 주는 메시지를 지우면 제안과 항목이 함께 지워짐.
        jdbc.update("DELETE FROM chat_message WHERE message_id=?", message);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_proposal WHERE proposal_id=?", Integer.class, proposal)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_proposal_item WHERE proposal_id=?", Integer.class, proposal)).isZero();
    }

    private long message() {
        return insert("INSERT INTO chat_message(session_id,trun_no,role,content) VALUES(?,1,'assistant','제안 카드')", session);
    }

    /** minutes 뒤에 만료되는 대기 제안. 음수면 이미 지난 제안임. */
    private long proposal(long message, String kind, int minutes) {
        return insert("INSERT INTO chat_proposal(member_id,profile_id,message_id,kind,created_at,expires_at) "
                + "VALUES(?,?,?,?,CURRENT_TIMESTAMP - INTERVAL 31 MINUTE,CURRENT_TIMESTAMP + INTERVAL ? MINUTE)",
                member, profile, message, kind, minutes);
    }

    private int columns(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?",
                Integer.class, table);
    }

    private int constraints(String table, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() "
                + "AND table_name=? AND constraint_type=?", Integer.class, table, type);
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private static int update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement.executeUpdate();
        }
    }

    private static void assertSqlError(Connection connection, int code, String sql, Object... values) {
        assertThatThrownBy(() -> update(connection, sql, values))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).isEqualTo(code));
    }
}

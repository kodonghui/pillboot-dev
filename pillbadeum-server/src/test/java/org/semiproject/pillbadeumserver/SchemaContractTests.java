package org.semiproject.pillbadeumserver;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.*;

/** 실제 저장 흐름을 별도 MySQL pill_test에서만 검증하며 fixture는 롤백합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class SchemaContractTests {
    @Autowired DataSource dataSource;

    @Test
    void supportsSchedulelessRegistrationChatPairsTemporaryCandidatesAndPushSubscriptions() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT DATABASE() = 'pill_test'"))
                    .as("Only the dedicated pill_test database may be modified").isEqualTo(1);
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/01_PILLBADEUM_DB_SCRIPT.sql"));
            connection.setAutoCommit(false);
            try {
                String suffix = UUID.randomUUID().toString();
                String itemSeq = "schema-test-" + suffix;
                long member = insert(connection,
                        "INSERT INTO member(email,password_hash,nickname) VALUES(?, 'test-only', 'schema fixture')",
                        "review-" + suffix + "a".repeat(20) + "@example.test");
                long profile = insert(connection,
                        "INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender) "
                                + "VALUES(?, 'schema fixture', 'SELF', 2000, 'FEMALE')", member);
                assertThat(count(connection, "SELECT COUNT(*) FROM profile WHERE profile_id = ? AND profile_pregnant IS NULL", profile))
                        .as("Not answering pregnancy must remain distinct from NO").isEqualTo(1);
                execute(connection, "INSERT INTO drug(item_seq,name) VALUES(?, 'permit API may lack pill attributes')", itemSeq);

                long myDrug = insert(connection,
                        "INSERT INTO my_drug(profile_id,item_seq,source) VALUES(?, ?, 'SEARCH')", profile, itemSeq);
                assertDuplicate(connection, "INSERT INTO my_drug(profile_id,item_seq,source) VALUES(?, ?, 'SEARCH')", profile, itemSeq);
                execute(connection, "UPDATE my_drug SET dose_per_time=1, start_date='2026-10-17', end_date='2026-10-18', "
                        + "dose_times='07:00,11:00' WHERE my_drug_id=?", myDrug);
                // Schedule deletion may retain dose/timing as defaults for later re-registration.
                execute(connection, "UPDATE my_drug SET start_date=NULL,end_date=NULL,dose_times=NULL WHERE my_drug_id=?", myDrug);
                assertThat(count(connection, "SELECT COUNT(*) FROM my_drug WHERE my_drug_id=? AND start_date IS NULL AND dose_per_time=1", myDrug))
                        .isEqualTo(1);

                long room = insert(connection,
                        "INSERT INTO chat_session(member_id,profile_id,created_at) VALUES(?, ?, CURRENT_TIMESTAMP)", member, profile);
                execute(connection, "INSERT INTO chat_message(session_id,turn_no,role,content) VALUES(?,1,'USER','question')", room);
                execute(connection, "INSERT INTO chat_message(session_id,turn_no,role,content) VALUES(?,1,'ASSISTANT','answer')", room);
                assertDuplicate(connection, "INSERT INTO chat_message(session_id,turn_no,role,content) VALUES(?,1,'USER','duplicate')", room);
                execute(connection, "INSERT INTO chat_summary(session_id,from_turn,to_turn,content) VALUES(?,1,1,'summary')", room);
                assertDuplicate(connection, "INSERT INTO chat_summary(session_id,from_turn,to_turn,content) VALUES(?,1,2,'same start')", room);

                long prescription = insert(connection,
                        "INSERT INTO photo(member_id,profile_id,kind,temp_path,mime_type,size_bytes,status) "
                                + "VALUES(?,?,'PRESCRIPTION','fixture.jpg','image/jpeg',1,'IDENTIFIED')", member, profile);
                execute(connection, "INSERT INTO ocr_candidate(photo_id,read_no,read_name,candidate_rank,lookup_status) "
                        + "VALUES(?,1,'no result card',1,'NOT_FOUND')", prescription);
                execute(connection, "INSERT INTO ocr_candidate(photo_id,read_no,item_seq,candidate_rank,lookup_status) "
                        + "VALUES(?,2,?,1,'OK')", prescription, itemSeq);
                execute(connection, "DELETE FROM photo WHERE photo_id=?", prescription);
                assertThat(count(connection, "SELECT COUNT(*) FROM ocr_candidate WHERE photo_id=?", prescription)).isZero();

                long front = insert(connection,
                        "INSERT INTO photo(member_id,profile_id,kind,side,temp_path,mime_type,size_bytes,status) "
                                + "VALUES(?,?,'PILL','FRONT','front.jpg','image/jpeg',1,'IDENTIFIED')", member, profile);
                long back = insert(connection,
                        "INSERT INTO photo(member_id,profile_id,kind,side,front_photo_id,temp_path,mime_type,size_bytes,status) "
                                + "VALUES(?,?,'PILL','BACK',?,'back.jpg','image/jpeg',1,'IDENTIFIED')", member, profile, front);
                execute(connection, "INSERT INTO ocr_candidate(photo_id,read_no,item_seq,candidate_rank,lookup_status) "
                        + "VALUES(?,1,?,1,'OK')", front, itemSeq);
                execute(connection, "DELETE FROM photo WHERE photo_id=?", front);
                assertThat(count(connection, "SELECT COUNT(*) FROM photo WHERE photo_id IN (?,?)", front, back)).isZero();
                assertThat(count(connection, "SELECT COUNT(*) FROM ocr_candidate WHERE photo_id=?", front)).isZero();

                execute(connection, "INSERT INTO dur_warning(item_seq,type) VALUES(?,'PREGNANCY')", itemSeq);
                // Related public-data drugs do not all need local drug rows.
                execute(connection, "INSERT INTO dur_interaction(item_seq,mixture_item_seq) VALUES(?,?)", itemSeq, "unpersisted-" + suffix);
                execute(connection, "UPDATE drug SET dur_synced_at=CURRENT_TIMESTAMP WHERE item_seq=?", itemSeq);
                assertThat(count(connection, "SELECT COUNT(*) FROM drug WHERE item_seq=? AND dur_synced_at IS NOT NULL", itemSeq)).isEqualTo(1);

                String endpoint = "https://push.example.test/" + "a".repeat(1800) + suffix;
                insert(connection, "INSERT INTO push_subscription(member_id,endpoint,p256dh,auth) VALUES(?,?,'test-public','test-auth')", member, endpoint);
                assertDuplicate(connection, "INSERT INTO push_subscription(member_id,endpoint,p256dh,auth) VALUES(?,?,'test-public','test-auth')", member, endpoint);
                insert(connection, "INSERT INTO push_subscription(member_id,endpoint,p256dh,auth) VALUES(?,?,'test-public','test-auth')", member,
                        endpoint.replace("/aaa", "/Aaa"));
                assertThat(count(connection, "SELECT COUNT(*) FROM push_subscription WHERE member_id=?", member)).isEqualTo(2);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void assertDuplicate(Connection connection, String sql, Object... values) {
        assertThatThrownBy(() -> execute(connection, sql, values)).isInstanceOfSatisfying(SQLException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(1062));
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    private static long insert(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
            try (var rows = statement.getGeneratedKeys()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static long count(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }
}

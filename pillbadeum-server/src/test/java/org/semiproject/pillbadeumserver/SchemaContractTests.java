package org.semiproject.pillbadeumserver;

import java.nio.file.Files;
import java.nio.file.Path;
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

/** ERD Cloud의 구조와 제약을 별도 MySQL pill_test에서 검증하고 fixture는 롤백합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class SchemaContractTests {
    @Autowired DataSource dataSource;

    @Test
    void preservesErdColumnDefinitionsAndOnlyDeclaredConstraints() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT DATABASE() = 'pill_test'"))
                    .as("Only the dedicated pill_test database may be modified").isEqualTo(1);
            org.semiproject.pillbadeumserver.TestDatabaseSchema.prepare(connection);
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"))
                    .isEqualTo(195);
            assertConstraintCount(connection, "PRIMARY KEY", 23);
            assertConstraintCount(connection, "FOREIGN KEY", 32);
            assertConstraintCount(connection, "UNIQUE", 10);
            assertConstraintCount(connection, "CHECK", 16);
            assertColumn(connection, "pill_identification_session", "identification_id", "char", 36L, "NO", null);
            assertColumn(connection, "pill_identification_session", "photo_id", "bigint", null, "YES", null);
            assertColumn(connection, "pill_identification_session", "snapshot", "json", null, "YES", null);
            assertColumn(connection, "pill_identification_session", "expires_at", "datetime", null, "NO", null);
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.table_constraints "
                    + "WHERE constraint_schema=DATABASE() AND table_name='drug' "
                    + "AND constraint_type='CHECK' AND constraint_name='chk_drug_type' AND enforced='YES'"))
                    .as("The drug type CHECK must be named and enforced").isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND LOWER(extra) LIKE '%on update%'"))
                    .as("ERD does not declare automatic ON UPDATE timestamps").isZero();
            assertColumn(connection, "profile", "profile_pregnant", "tinyint", null, "NO", "0");
            assertColumn(connection, "profile", "relationship_type", "varchar", 10L, "NO", null);
            assertColumn(connection, "chat_message", "trun_no", "int", null, "NO", null);
            assertColumn(connection, "chat_message", "dur_notice", "json", null, "YES", null);
            assertColumn(connection, "drug", "metadata_source", "varchar", 20L, "NO", "UNKNOWN");
            assertColumn(connection, "drug", "cancelled", "tinyint", null, "YES", null);
            // V3-DRUG-001: 함량 칸은 모두 NULL 허용이며, 14를 최신01 위에 다시 적용해도 READY이고 구조가 그대로다.
            assertColumn(connection, "drug", "base_name", "varchar", 50L, "YES", null);
            assertColumn(connection, "drug", "strength_text", "varchar", 50L, "YES", null);
            assertColumn(connection, "drug", "strength_ug", "decimal", null, "YES", null);
            assertColumn(connection, "drug", "strength_unit", "varchar", 10L, "YES", null);
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/14_DRUG_STRENGTH.sql"));
            // V3-CHAT-002~005·007: 제안 표 3개는 최신01에 있고, 16을 다시 적용해도 구조가 그대로다.
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/16_CHAT_PROPOSAL.sql"));
            // V3-KAKAO-012~016(DEC-029): 연결 표 2개·상담방 channel은 최신01에 있고, 17을 두 번 다시 적용해도 READY이고 구조가 그대로다.
            for (int run = 0; run < 2; run++) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/17_KAKAO_LINK.sql"));
            }
            assertThat(finalStatus(connection, "sql/17_KAKAO_LINK.sql")).isEqualTo("READY");
            // 2026-11-08: 처방 값 칸·일정 제안 줄 번호는 최신01에 있고, 18을 두 번 다시 적용해도 READY이고 구조가 그대로다.
            for (int run = 0; run < 2; run++) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/18_CHAT_PROPOSAL_PRESCRIPTION.sql"));
            }
            assertThat(finalStatus(connection, "sql/18_CHAT_PROPOSAL_PRESCRIPTION.sql")).isEqualTo("READY");
            assertColumn(connection, "chat_proposal_item", "rx_dose", "varchar", 10L, "YES", null);
            assertColumn(connection, "chat_proposal_item", "rx_applied", "tinyint", null, "NO", "0");
            assertColumn(connection, "chat_proposal_schedule", "item_no", "tinyint", null, "NO", "1");
            assertColumn(connection, "chat_proposal_schedule", "note", "varchar", 200L, "YES", null);
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"))
                    .isEqualTo(195);
            assertConstraintCount(connection, "CHECK", 16);
            assertColumn(connection, "chat_session", "channel", "varchar", 10L, "NO", "WEB");
            assertColumn(connection, "kakao_link", "kakao_user_hmac", "char", 64L, "NO", null);
            assertColumn(connection, "kakao_link", "current_profile_id", "bigint", null, "YES", null);
            assertColumn(connection, "kakao_link_code", "code_hash", "char", 64L, "NO", null);
            assertColumn(connection, "kakao_link_code", "used_at", "datetime", null, "YES", null);
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.referential_constraints "
                    + "WHERE constraint_schema=DATABASE() AND constraint_name='fk_kakao_link_profile' AND delete_rule='SET NULL'"))
                    .as("A deleted profile only clears the KakaoTalk link's current profile").isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.referential_constraints "
                    + "WHERE constraint_schema=DATABASE() AND constraint_name IN ('fk_kakao_link_member','fk_kakao_link_code_member') "
                    + "AND delete_rule='CASCADE'")).as("Withdrawing removes the member's links and codes").isEqualTo(2);
            assertColumn(connection, "chat_proposal", "status", "varchar", 12L, "NO", "PENDING");
            assertColumn(connection, "chat_proposal", "decided_at", "datetime", null, "YES", null);
            assertColumn(connection, "chat_proposal_item", "my_drug_id", "bigint", null, "YES", null);
            assertColumn(connection, "chat_proposal_schedule", "proposed_schedule", "json", null, "NO", null);
            assertColumn(connection, "my_drug", "dose_confirmed", "tinyint", null, "NO", "1");
            assertColumn(connection, "chat_session", "snap_birth_year", "bigint", null, "NO", null);
            assertColumn(connection, "chat_session", "snap_pregnant", "tinyint", null, "YES", null);
            assertColumn(connection, "my_drug", "start_date", "date", null, "YES", null);
            assertColumn(connection, "my_drug", "end_date", "date", null, "YES", null);
            assertColumn(connection, "my_drug", "dose_times", "varchar", 50L, "YES", null);
            assertColumn(connection, "my_drug", "item_seq", "varchar", 100L, "YES", null);
            assertColumn(connection, "my_drug", "custom_name", "varchar", 100L, "YES", null);
            assertColumn(connection, "consent", "terms_title", "varchar", 30L, "NO", null);
            assertColumn(connection, "drug", "etc_otc", "varchar", 10L, "NO", null);
            assertColumn(connection, "drug", "material", "text", 65535L, "NO", null);
            assertColumn(connection, "photo", "kind", "varchar", 30L, "NO", null);
            assertColumn(connection, "photo", "front_photo_id", "bigint", null, "YES", null);
            assertColumn(connection, "ocr_candidate", "item_seq", "varchar", 100L, "YES", null);
            assertColumn(connection, "dur_interaction", "item_seq2", "varchar", 100L, "NO", null);
            assertColumn(connection, "dur_interaction", "mixture_item_seq", "varchar", 50L, "YES", null);

            connection.setAutoCommit(false);
            try {
                String suffix = UUID.randomUUID().toString();
                String itemSeq = "schema-test-" + suffix;
                long member = insert(connection,
                        "INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) "
                                + "VALUES(?, 'test-only', 'fixture', 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                        "review-" + suffix + "@example.test");
                long profile = insert(connection,
                        "INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,created_at,updated_at) "
                                + "VALUES(?, 'fixture', 'SELF', 2000, 'FEMALE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", member);
                assertThat(count(connection, "SELECT COUNT(*) FROM profile WHERE profile_id=? AND profile_pregnant=FALSE", profile))
                        .isEqualTo(1);
                String drugInsert = "INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) "
                        + "VALUES(?, 'fixture', 'fixture', ?, 'fixture', 'ROUND', 'WHITE', 'TABLET', 'fixture', CURRENT_TIMESTAMP)";
                execute(connection, drugInsert, itemSeq, "OTC");
                execute(connection, drugInsert, itemSeq + "-2", "OTC");
                execute(connection, drugInsert, itemSeq + "-3", "ETC");
                assertThat(count(connection, "SELECT COUNT(*) FROM drug WHERE item_seq IN (?,?) AND etc_otc='OTC'",
                        itemSeq, itemSeq + "-2")).as("Different drugs may share the same OTC category").isEqualTo(2);
                assertSqlError(connection, 1062, drugInsert, itemSeq, "ETC");
                assertSqlError(connection, 3819, drugInsert, itemSeq + "-invalid", "UNKNOWN");
                assertSqlError(connection, 1048, drugInsert, itemSeq + "-null", null);

                long myDrug = insert(connection,
                        "INSERT INTO my_drug(profile_id,item_seq,dose_per_time,start_date,end_date,dose_times,source) "
                                + "VALUES(?,?,1,'2026-10-17','2026-10-18','07:00,11:00,17:00','SEARCH')", profile, itemSeq);
                assertThat(count(connection, "SELECT COUNT(*) FROM my_drug WHERE my_drug_id=? AND dose_times='07:00,11:00,17:00'", myDrug))
                        .isEqualTo(1);
                assertSqlError(connection, 3819, "UPDATE my_drug SET end_date=NULL WHERE my_drug_id=?", myDrug);
                execute(connection, "UPDATE my_drug SET start_date=NULL,end_date=NULL,dose_times=NULL WHERE my_drug_id=?", myDrug);
                assertThat(count(connection, "SELECT COUNT(*) FROM my_drug WHERE my_drug_id=? "
                        + "AND profile_id=? AND item_seq=? AND start_date IS NULL AND end_date IS NULL AND dose_times IS NULL",
                        myDrug, profile, itemSeq)).as("Deleting one schedule must preserve its medication and profile").isEqualTo(1);
                assertSqlError(connection, 3819, "UPDATE my_drug SET dose_times='07:00' WHERE my_drug_id=?", myDrug);
                // 사용자 확정: 같은 조합만 거절하고 다른 품목·다른 프로필의 같은 품목은 허용합니다.
                assertSqlError(connection, 1062, "INSERT INTO my_drug(profile_id,item_seq,dose_per_time,start_date,end_date,dose_times,source) "
                        + "VALUES(?,?,1,'2026-10-17','2026-10-18','07:00','SEARCH')", profile, itemSeq);
                long unscheduled = insert(connection,
                        "INSERT INTO my_drug(profile_id,item_seq,dose_per_time,source) VALUES(?,?,1,'SEARCH')", profile, itemSeq + "-2");
                assertThat(count(connection, "SELECT COUNT(*) FROM my_drug WHERE my_drug_id=? AND start_date IS NULL "
                        + "AND end_date IS NULL AND dose_times IS NULL", unscheduled)).isEqualTo(1);
                long familyProfile = insert(connection,
                        "INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,created_at,updated_at) "
                                + "VALUES(?,'family','FAMILY',1990,'MALE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", member);
                insert(connection,"INSERT INTO my_drug(profile_id,item_seq,dose_per_time,source) VALUES(?,?,1,'SEARCH')",familyProfile,itemSeq);
                for (int i=0;i<2;i++) insert(connection,
                        "INSERT INTO my_drug(profile_id,custom_name,dose_per_time,source) VALUES(?,'unidentified fixture',1,'MANUAL')",profile);

                // 알림 설정은 회원당 1행, 푸시 구독은 브라우저 주소(대소문자 구분)당 1행, 발송은 같은 분에 1번입니다.
                execute(connection, "INSERT INTO notification_setting(member_id,push_enabled) VALUES(?,TRUE)", member);
                assertSqlError(connection, 1062, "INSERT INTO notification_setting(member_id) VALUES(?)", member);
                String endpoint = "https://push.example.test/" + suffix;
                String subscriptionInsert = "INSERT INTO push_subscription(member_id,endpoint,p256dh,auth) VALUES(?,?,'key','auth')";
                execute(connection, subscriptionInsert, member, endpoint);
                assertSqlError(connection, 1062, subscriptionInsert, member, endpoint);
                execute(connection, subscriptionInsert, member, endpoint.toUpperCase());
                String dispatchInsert = "INSERT INTO notification_dispatch(dose_at) VALUES('2000-01-01 07:00:00')";
                execute(connection, dispatchInsert);
                assertSqlError(connection, 1062, dispatchInsert);

                long room = insert(connection,
                        "INSERT INTO chat_session(member_id,profile_id,created_at,snap_birth_year,snap_gender) "
                                + "VALUES(?,?,CURRENT_TIMESTAMP,2000,'FEMALE')", member, profile);
                assertThat(count(connection, "SELECT COUNT(*) FROM chat_session WHERE session_id=? AND snap_pregnant IS NULL", room))
                        .isEqualTo(1);
                for (boolean pregnant : new boolean[]{false, true}) {
                    execute(connection, "UPDATE profile SET profile_pregnant=? WHERE profile_id=?", pregnant, profile);
                    execute(connection, "UPDATE chat_session SET snap_pregnant="
                            + "(SELECT profile_pregnant FROM profile WHERE profile_id=?) WHERE session_id=?", profile, room);
                    assertThat(count(connection, "SELECT COUNT(*) FROM chat_session cs JOIN profile p ON p.profile_id=cs.profile_id "
                            + "WHERE cs.session_id=? AND cs.snap_pregnant=p.profile_pregnant AND cs.snap_pregnant=?", room, pregnant))
                            .isEqualTo(1);
                }
                execute(connection, "INSERT INTO chat_message(session_id,trun_no,role,content) VALUES(?,1,'user','question')", room);
                execute(connection, "INSERT INTO chat_message(session_id,trun_no,role,content) VALUES(?,1,'assistant','answer')", room);
                execute(connection, "INSERT INTO chat_summary(session_id,from_turn,to_turn,content,created_at) "
                        + "VALUES(?,1,1,'summary',CURRENT_TIMESTAMP)", room);

                long photo = insert(connection,
                        "INSERT INTO photo(member_id,profile_id,kind,temp_path,mime_type,size_bytes,status,uploaded_at) "
                                + "VALUES(?,?,'PRESCRIPTION','fixture.jpg','image/jpeg',1,'IDENTIFIED',CURRENT_TIMESTAMP)", member, profile);
                assertThat(count(connection, "SELECT COUNT(*) FROM photo WHERE photo_id=? AND kind='PRESCRIPTION'", photo))
                        .as("The photo kind must fit the prescription literal").isEqualTo(1);
                execute(connection, "INSERT INTO ocr_candidate(photo_id,read_no,candidate_rank) VALUES(?,1,1)", photo);
                assertThat(count(connection, "SELECT COUNT(*) FROM ocr_candidate WHERE photo_id=? "
                        + "AND read_name IS NULL AND lookup_status IS NULL AND print_matched IS NULL", photo)).isEqualTo(1);
                // 05를 다시 적용해도 이전 NULL 후보와 원본 경로를 보존하며 연결 FK는 유효해야 한다.
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/05_PHOTO_CANDIDATE_LINKS.sql"));
                assertThat(count(connection,"SELECT COUNT(*) FROM ocr_candidate WHERE photo_id=? AND item_seq IS NULL",photo)).isEqualTo(1);
                assertSqlError(connection,1452,"UPDATE photo SET front_photo_id=? WHERE photo_id=?",Long.MAX_VALUE,photo);
                assertSqlError(connection,1452,"UPDATE ocr_candidate SET item_seq='nonexistent-fixture' WHERE photo_id=?",photo);
                execute(connection,"UPDATE ocr_candidate SET item_seq=? WHERE photo_id=?",itemSeq,photo);
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/05_PHOTO_CANDIDATE_LINKS.sql"));
                assertThat(count(connection,"SELECT COUNT(*) FROM ocr_candidate WHERE photo_id=? AND item_seq=?",photo,itemSeq)).isEqualTo(1);
                // ERD의 FK에는 CASCADE가 없습니다. 자식을 먼저 지워야 부모를 삭제할 수 있습니다.
                assertSqlError(connection, 1451, "DELETE FROM photo WHERE photo_id=?", photo);
                execute(connection, "DELETE FROM ocr_candidate WHERE photo_id=?", photo);
                execute(connection, "DELETE FROM photo WHERE photo_id=?", photo);
                execute(connection, "INSERT INTO dur_warning(item_seq,content) VALUES(?,'warning fixture')", itemSeq);
                execute(connection, "INSERT INTO dur_interaction(item_seq2) VALUES(?)", itemSeq);
                assertThat(count(connection, "SELECT COUNT(*) FROM dur_interaction WHERE item_seq2=? AND mixture_item_seq IS NULL", itemSeq))
                        .isEqualTo(1);
                assertSqlError(connection, 1452, "INSERT INTO dur_interaction(item_seq2) VALUES(?)", "missing-" + suffix);

                // 상담방은 WEB·KAKAO만 받고, 카카오 사용자 하나는 연결 하나만 가짐. 코드는 만든 시각보다 뒤에 끝나야 함.
                assertThat(count(connection, "SELECT COUNT(*) FROM chat_session WHERE session_id=? AND channel='WEB'", room))
                        .as("Existing and default rooms are web rooms").isEqualTo(1);
                execute(connection, "UPDATE chat_session SET channel='KAKAO' WHERE session_id=?", room);
                assertSqlError(connection, 3819, "UPDATE chat_session SET channel='SMS' WHERE session_id=?", room);
                String hmac = "a".repeat(64);
                String linkInsert = "INSERT INTO kakao_link(member_id,kakao_user_hmac,current_profile_id,notice_version,agreed_at,linked_at) "
                        + "VALUES(?,?,?,'KAKAO-LINK-1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";
                long link = insert(connection, linkInsert, member, hmac, familyProfile);
                assertSqlError(connection, 1062, linkInsert, member, hmac, profile);
                assertSqlError(connection, 3819, "INSERT INTO kakao_link_code(member_id,code_hash,notice_version,agreed_at,created_at,expires_at) "
                        + "VALUES(?,?,'KAKAO-LINK-1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", member, "b".repeat(64));
                execute(connection, "DELETE FROM my_drug WHERE profile_id=?", familyProfile);
                execute(connection, "DELETE FROM profile WHERE profile_id=?", familyProfile);
                assertThat(count(connection, "SELECT COUNT(*) FROM kakao_link WHERE link_id=? AND current_profile_id IS NULL", link))
                        .as("Deleting the current profile keeps the link and clears its profile").isEqualTo(1);
            } finally {
                connection.rollback();
            }
        }
    }

    /** 패치 파일 맨 끝의 상태 SELECT만 다시 실행해 그 값을 돌려준다(ScriptUtils는 SELECT 결과를 주지 않음). */
    private static String finalStatus(Connection connection, String path) throws Exception {
        String script = Files.readString(Path.of(path));
        String select = script.substring(script.lastIndexOf("SELECT IF(")).strip();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery(select.endsWith(";") ? select.substring(0, select.length() - 1) : select)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static void assertConstraintCount(Connection connection, String type, long expected) throws SQLException {
        assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema=DATABASE() AND constraint_type=?", type)).as(type).isEqualTo(expected);
    }

    private static void assertColumn(Connection connection, String table, String column, String type,
            Long length, String nullable, String defaultValue) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT data_type,character_maximum_length,is_nullable,column_default "
                + "FROM information_schema.columns WHERE table_schema=DATABASE() AND LOWER(table_name)=LOWER(?) AND column_name=?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as(table + "." + column).isTrue();
                assertThat(rows.getString(1)).isEqualTo(type);
                assertThat(rows.getObject(2, Long.class)).isEqualTo(length);
                assertThat(rows.getString(3)).isEqualTo(nullable);
                assertThat(rows.getString(4)).isEqualTo(defaultValue);
            }
        }
    }

    private static void assertSqlError(Connection connection, int code, String sql, Object... values) {
        assertThatThrownBy(() -> execute(connection, sql, values)).isInstanceOfSatisfying(SQLException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
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

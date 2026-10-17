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
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/01_PILLBADEUM_DB_SCRIPT.sql"));
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"))
                    .isEqualTo(114);
            assertConstraintCount(connection, "PRIMARY KEY", 14);
            assertConstraintCount(connection, "FOREIGN KEY", 15);
            assertConstraintCount(connection, "UNIQUE", 2);
            assertConstraintCount(connection, "CHECK", 0);
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND LOWER(extra) LIKE '%on update%'"))
                    .as("ERD does not declare automatic ON UPDATE timestamps").isZero();
            assertColumn(connection, "PROFILE", "profile_pregnant", "tinyint", null, "NO", "0");
            assertColumn(connection, "PROFILE", "realationship_type", "varchar", 10L, "NO", null);
            assertColumn(connection, "chat_message", "trun_no", "int", null, "NO", null);
            assertColumn(connection, "chat_session", "snap_birth_year", "bigint", null, "NO", null);
            assertColumn(connection, "chat_session", "snap_pregnant", "varchar", 10L, "YES", null);
            assertColumn(connection, "my_drug", "end_date", "date", null, "NO", null);
            assertColumn(connection, "my_drug", "dose_times", "varchar", 50L, "NO", "07:00,11:00,17:00");
            assertColumn(connection, "CONSENT", "terms_title", "varchar", 30L, "NO", null);
            assertColumn(connection, "DRUG", "matrial", "varchar", 30L, "NO", null);
            assertColumn(connection, "DUR_INTERACTION", "item_seq2", "varchar", 100L, "NO", null);
            assertColumn(connection, "DUR_INTERACTION", "mixture_item_seq", "varchar", 50L, "YES", null);

            connection.setAutoCommit(false);
            try {
                String suffix = UUID.randomUUID().toString();
                String itemSeq = "schema-test-" + suffix;
                String category = suffix.substring(0, 8);
                long member = insert(connection,
                        "INSERT INTO MEMBER(email,password_hash,nickname,user_role,created_at,updated_at) "
                                + "VALUES(?, 'test-only', 'fixture', 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                        "review-" + suffix + "@example.test");
                long profile = insert(connection,
                        "INSERT INTO PROFILE(member_id,profile_name,realationship_type,profile_birth_year,profile_gender,created_at,updated_at) "
                                + "VALUES(?, 'fixture', 'SELF', 2000, 'FEMALE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", member);
                assertThat(count(connection, "SELECT COUNT(*) FROM PROFILE WHERE profile_id=? AND profile_pregnant=FALSE", profile))
                        .isEqualTo(1);
                execute(connection, "INSERT INTO DRUG(item_seq,name,maker,etc_otc,class_name,shape,color,form,matrial,synced_at) "
                        + "VALUES(?, 'fixture', 'fixture', ?, 'fixture', 'ROUND', 'WHITE', 'TABLET', 'fixture', CURRENT_TIMESTAMP)", itemSeq, category);
                assertSqlError(connection, 1062,
                        "INSERT INTO DRUG(item_seq,name,maker,etc_otc,class_name,shape,color,form,matrial,synced_at) "
                                + "VALUES(?, 'duplicate category', 'fixture', ?, 'fixture', 'ROUND', 'WHITE', 'TABLET', 'fixture', CURRENT_TIMESTAMP)",
                        itemSeq + "-2", category);

                long myDrug = insert(connection,
                        "INSERT INTO my_drug(profile_id,item_seq,dose_per_time,start_date,end_date,source) "
                                + "VALUES(?,?,1,'2026-10-17','2026-10-18','SEARCH')", profile, itemSeq);
                assertThat(count(connection, "SELECT COUNT(*) FROM my_drug WHERE my_drug_id=? AND dose_times='07:00,11:00,17:00'", myDrug))
                        .isEqualTo(1);
                assertSqlError(connection, 1048, "UPDATE my_drug SET end_date=NULL WHERE my_drug_id=?", myDrug);
                // ERD에는 프로필/약의 복합 UNIQUE가 없으므로 임의로 중복 등록을 금지하지 않습니다.
                insert(connection, "INSERT INTO my_drug(profile_id,item_seq,dose_per_time,start_date,end_date,source) "
                        + "VALUES(?,?,1,'2026-10-17','2026-10-18','SEARCH')", profile, itemSeq);

                long room = insert(connection,
                        "INSERT INTO chat_session(member_id,profile_id,created_at,snap_birth_year,snap_gender) "
                                + "VALUES(?,?,CURRENT_TIMESTAMP,2000,'FEMALE')", member, profile);
                execute(connection, "INSERT INTO chat_message(session_id,trun_no,role,content) VALUES(?,1,'user','question')", room);
                execute(connection, "INSERT INTO chat_message(session_id,trun_no,role,content) VALUES(?,1,'assistant','answer')", room);
                execute(connection, "INSERT INTO chat_summary(session_id,from_turn,to_turn,content,created_at) "
                        + "VALUES(?,1,1,'summary',CURRENT_TIMESTAMP)", room);

                long photo = insert(connection,
                        "INSERT INTO PHOTO(member_id,profile_id,kind,temp_path,mime_type,size_bytes,status,uploaded_at) "
                                + "VALUES(?,?,'PILL','fixture.jpg','image/jpeg',1,'IDENTIFIED',CURRENT_TIMESTAMP)", member, profile);
                execute(connection, "INSERT INTO OCR_CANDIDATE(photo_id,read_no,candidate_rank) VALUES(?,1,1)", photo);
                assertThat(count(connection, "SELECT COUNT(*) FROM OCR_CANDIDATE WHERE photo_id=? "
                        + "AND read_name IS NULL AND lookup_status IS NULL AND print_matched IS NULL", photo)).isEqualTo(1);
                // ERD의 FK에는 CASCADE가 없습니다. 자식을 먼저 지워야 부모를 삭제할 수 있습니다.
                assertSqlError(connection, 1451, "DELETE FROM PHOTO WHERE photo_id=?", photo);
                execute(connection, "DELETE FROM OCR_CANDIDATE WHERE photo_id=?", photo);
                execute(connection, "DELETE FROM PHOTO WHERE photo_id=?", photo);
                execute(connection, "INSERT INTO DUR_WARNING(item_seq,content) VALUES(?,'warning fixture')", itemSeq);
                execute(connection, "INSERT INTO DUR_INTERACTION(item_seq2) VALUES(?)", itemSeq);
                assertThat(count(connection, "SELECT COUNT(*) FROM DUR_INTERACTION WHERE item_seq2=? AND mixture_item_seq IS NULL", itemSeq))
                        .isEqualTo(1);
                assertSqlError(connection, 1452, "INSERT INTO DUR_INTERACTION(item_seq2) VALUES(?)", "missing-" + suffix);
            } finally {
                connection.rollback();
            }
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

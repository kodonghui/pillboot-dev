import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;

/** 읽기 전용 연결·스키마 검사. 회원·약·비밀번호·접속 URL은 출력하지 않는다. */
class CheckMedicationDb {
    public static void main(String[] args) throws Exception {
        var local = new Properties();
        if (Files.exists(Path.of(".env"))) {
            try (Reader reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) {
                local.load(reader);
            }
        }
        String url = setting(local, "DB_URL"), user = setting(local, "DB_USERNAME"), password = setting(local, "DB_PASSWORD");
        if (url.isBlank() || user.isBlank()) {
            System.out.println("FAIL db-settings-missing"); System.exit(1);
        }
        DriverManager.setLoginTimeout(5);
        Class.forName("com.mysql.cj.jdbc.Driver");
        int failures = 0;
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            System.out.println("PASS db-connection");
            for (String[] field : List.of(
                    new String[]{"profile", "relationship_type"}, new String[]{"drug", "name"},
                    new String[]{"drug", "material"}, new String[]{"my_drug", "start_date"},
                    new String[]{"my_drug", "end_date"}, new String[]{"my_drug", "dose_times"}, new String[]{"my_drug", "custom_name"},
                    new String[]{"notification_setting", "push_enabled"},
                    new String[]{"push_subscription", "endpoint"}, new String[]{"notification_dispatch", "dose_at"})) {
                try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=?")) {
                    statement.setString(1, field[0]); statement.setString(2, field[1]);
                    try (var rows = statement.executeQuery()) {
                        rows.next(); boolean exists = rows.getInt(1) == 1;
                        System.out.println((exists ? "PASS " : "FAIL ") + field[0] + "." + field[1]);
                        if (!exists) failures++;
                    }
                }
            }
            try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='my_drug' "
                    + "AND column_name IN ('start_date','end_date','dose_times','item_seq','custom_name') AND is_nullable='YES'")) {
                try (var rows = statement.executeQuery()) {
                    rows.next(); boolean nullable = rows.getInt(1) == 5;
                    System.out.println((nullable ? "PASS " : "FAIL ") + "optional-schedule-and-drug-columns");
                    if (!nullable) failures++;
                }
            }
            try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM consent "
                    + "WHERE terms_type='MEDICATION_REMINDER' AND is_active=TRUE AND effective_at<=CURRENT_TIMESTAMP")) {
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    System.out.println(rows.getInt(1) > 0 ? "PASS medication-reminder-terms" : "WARN medication-reminder-terms-missing");
                }
            }
            connection.rollback();
        } catch (java.sql.SQLException error) {
            System.out.println("FAIL db-check SQLState=" + error.getSQLState()); System.exit(1);
        }
        // 기존 전용 테스트 DB에 접속 가능한지만 검사한다. DB나 표를 만들지 않는다.
        String testUrl = setting(local, "MYSQL_TEST_URL");
        if (testUrl.isBlank()) testUrl = url.replaceFirst("(?<=:[0-9]{1,5}/)[^?;/]+", "pill_test");
        String testUser = setting(local, "MYSQL_TEST_USER"), testPassword = setting(local, "MYSQL_TEST_PASSWORD");
        if (testUser.isBlank()) testUser = user;
        if (testPassword.isBlank()) testPassword = password;
        if (!testUrl.contains("/pill_test") || testUrl.equals(url)) {
            System.out.println("SKIP dedicated-test-db-url-not-set");
        } else {
            try (Connection test = DriverManager.getConnection(testUrl, testUser, testPassword)) {
                test.setReadOnly(true);
                System.out.println("pill_test".equals(test.getCatalog()) ? "PASS dedicated-test-db-connection" : "SKIP dedicated-test-db-name-mismatch");
            } catch (java.sql.SQLException error) {
                System.out.println("SKIP dedicated-test-db-unavailable SQLState=" + error.getSQLState() + " code=" + error.getErrorCode());
            }
        }
        if (failures > 0) System.exit(1);
    }

    private static String setting(Properties local, String key) {
        String inherited = System.getenv(key);
        return inherited == null ? local.getProperty(key, "").trim() : inherited;
    }
}

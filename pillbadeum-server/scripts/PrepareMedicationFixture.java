import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Properties;

/** 기존 회원에 복약지도용 독립 테스트 프로필·내 약을 준비한다. 회원 비밀번호와 다른 프로필은 변경하지 않는다. */
class PrepareMedicationFixture {
    private record Drug(String item, String name) {}
    private record Slot(String profile, String relation, Drug drug, String initial) {}

    public static void main(String[] args) throws Exception {
        var arguments = List.of(args);
        int emailIndex = arguments.indexOf("--email");
        if (emailIndex < 0 || emailIndex + 1 >= args.length || !args[emailIndex + 1].contains("@")) {
            System.out.println("Usage: PrepareMedicationFixture.java --email <existing-account-email> [--apply] [--reset]");
            System.exit(1);
        }
        String email = args[emailIndex + 1];
        boolean apply = arguments.contains("--apply"), reset = arguments.contains("--reset");
        if (reset && !apply) throw new IllegalArgumentException("--reset requires --apply");
        var local = new Properties();
        if (Files.exists(Path.of(".env"))) {
            try (Reader reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) { local.load(reader); }
        }
        Class.forName("com.mysql.cj.jdbc.Driver");
        DriverManager.setLoginTimeout(5);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        try (Connection db = DriverManager.getConnection(setting(local, "DB_URL"), setting(local, "DB_USERNAME"), setting(local, "DB_PASSWORD"))) {
            db.setAutoCommit(false);
            db.setReadOnly(!apply);
            try {
                long member = member(db, email, apply);
                Drug tylenol = drug(db, "SELECT item_seq,name FROM drug WHERE name LIKE '%타이레놀%' ORDER BY item_seq LIMIT 1");
                Drug other = drug(db, "SELECT item_seq,name FROM drug WHERE name NOT LIKE '%타이레놀%' ORDER BY item_seq LIMIT 1");
                if (tylenol == null || other == null) throw new IllegalStateException("기존 drug에 타이레놀과 다른 약이 각각 필요합니다. 약 마스터는 이 도구에서 변경하지 않습니다.");
                var slots = List.of(
                        new Slot("테스트본인", "SELF", tylenol, "UNSCHEDULED"),
                        new Slot("테스트본인", "SELF", other, "ACTIVE"),
                        new Slot("테스트엄마", "MOTHER", tylenol, "ACTIVE"),
                        new Slot("테스트엄마", "MOTHER", other, "ENDED"),
                        new Slot("테스트아빠", "FATHER", tylenol, "ACTIVE"),
                        new Slot("테스트아빠", "FATHER", other, "FUTURE"));
                int createdProfiles = 0, createdDrugs = 0, resetDrugs = 0;
                for (Slot slot : slots) {
                    Long profile = profile(db, member, slot.profile);
                    if (profile == null && apply) { profile = createProfile(db, member, slot); createdProfiles++; }
                    Long medication = profile == null ? null : medication(db, profile, slot.drug.item);
                    if (medication == null && apply) {
                        medication = createMedication(db, profile, slot.drug.item);
                        initialize(db, profile, medication, slot.initial, today);
                        createdDrugs++;
                    } else if (medication != null && reset) {
                        initialize(db, profile, medication, slot.initial, today);
                        resetDrugs++;
                    }
                }
                if (apply) {
                    try (var statement = db.prepareStatement("INSERT INTO notification_setting(member_id,push_enabled,updated_at) "
                            + "VALUES(?,FALSE,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE setting_id=setting_id")) {
                        statement.setLong(1, member); statement.executeUpdate();
                    }
                    verify(db, member, slots, today, reset);
                    db.commit();
                }
                System.out.println((apply ? "COMMITTED" : "INSPECT ONLY") + " memberId=" + member + " today=" + today);
                System.out.println("createdProfiles=" + createdProfiles + " createdMedications=" + createdDrugs + " resetMedications=" + resetDrugs);
                System.out.println("profileName\tprofileId\tmyDrugId\tdrugName\tstate\tstartDate\tendDate\tdoseTimes");
                for (Slot slot : slots) print(db, member, slot, today);
                printForeignTarget(db, member);
                if (!apply) db.rollback();
            } catch (Exception error) {
                db.rollback();
                if (error instanceof java.sql.SQLException sql) {
                    System.out.println("FAIL SQLState=" + sql.getSQLState() + " code=" + sql.getErrorCode());
                } else {
                    System.out.println("FAIL " + error.getMessage());
                }
                System.exit(1);
            }
        } catch (java.sql.SQLException error) {
            System.out.println("FAIL db-connection SQLState=" + error.getSQLState() + " code=" + error.getErrorCode());
            System.exit(1);
        }
    }

    private static long member(Connection db, String email, boolean lock) throws Exception {
        try (var statement = db.prepareStatement("SELECT member_id FROM member WHERE email=?" + (lock ? " FOR UPDATE" : ""))) {
            statement.setString(1, email);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("기존 회원을 찾지 못했습니다. 가입한 이메일을 확인해주세요.");
                return rows.getLong(1);
            }
        }
    }
    private static Drug drug(Connection db, String sql) throws Exception {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            return rows.next() ? new Drug(rows.getString(1), rows.getString(2)) : null;
        }
    }
    private static Long profile(Connection db, long member, String name) throws Exception {
        try (var statement = db.prepareStatement("SELECT profile_id FROM profile WHERE member_id=? AND profile_name=? ORDER BY profile_id LIMIT 1")) {
            statement.setLong(1, member); statement.setString(2, name);
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : null; }
        }
    }
    private static Long medication(Connection db, long profile, String item) throws Exception {
        try (var statement = db.prepareStatement("SELECT my_drug_id FROM my_drug WHERE profile_id=? AND item_seq=? ORDER BY my_drug_id LIMIT 1")) {
            statement.setLong(1, profile); statement.setString(2, item);
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : null; }
        }
    }
    private static long createProfile(Connection db, long member, Slot slot) throws Exception {
        try (var statement = db.prepareStatement("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,profile_pregnant,created_at,updated_at) "
                + "VALUES(?,?,?,2000,'UNKNOWN',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, member); statement.setString(2, slot.profile); statement.setString(3, slot.relation);
            return insert(statement);
        }
    }
    private static long createMedication(Connection db, long profile, String item) throws Exception {
        try (var statement = db.prepareStatement("INSERT INTO my_drug(profile_id,item_seq,dose_per_time,source) VALUES(?,?,1,'SEARCH')", Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, profile); statement.setString(2, item);
            return insert(statement);
        }
    }
    private static long insert(PreparedStatement statement) throws Exception {
        statement.executeUpdate();
        try (var keys = statement.getGeneratedKeys()) {
            if (!keys.next()) throw new IllegalStateException("생성 ID를 확인하지 못했습니다.");
            return keys.getLong(1);
        }
    }
    private static void initialize(Connection db, long profile, long medication, String state, LocalDate today) throws Exception {
        LocalDate from = switch (state) { case "UNSCHEDULED" -> null; case "FUTURE" -> today.plusDays(1); case "ENDED" -> today.minusDays(7); default -> today.minusDays(1); };
        LocalDate to = switch (state) { case "UNSCHEDULED" -> null; case "ENDED" -> today.minusDays(1); default -> today.plusDays(7); };
        try (var statement = db.prepareStatement("UPDATE my_drug SET start_date=?,end_date=?,dose_times=?,dose_per_time=1,timing='식후 30분',updated_at=CURRENT_TIMESTAMP "
                + "WHERE profile_id=? AND my_drug_id=?")) {
            statement.setObject(1, from); statement.setObject(2, to);
            statement.setString(3, from == null ? null : "07:00,11:00,17:00");
            statement.setLong(4, profile); statement.setLong(5, medication);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("일정 초기화 대상을 확인하지 못했습니다.");
        }
    }
    private static void verify(Connection db, long member, List<Slot> slots, LocalDate today, boolean reset) throws Exception {
        for (Slot slot : slots) {
            Long profile = profile(db, member, slot.profile);
            if (profile == null || medication(db, profile, slot.drug.item) == null) throw new IllegalStateException("테스트 데이터 생성 검증 실패");
            if (reset) {
                try (var statement = db.prepareStatement("SELECT start_date,end_date,dose_times FROM my_drug WHERE my_drug_id=?")) {
                    statement.setLong(1, medication(db, profile, slot.drug.item));
                    try (var rows = statement.executeQuery()) {
                        rows.next();
                        String actual = state(rows.getDate(1), rows.getDate(2), rows.getString(3), today);
                        if (!actual.equals(slot.initial)) throw new IllegalStateException("테스트 일정 상태 검증 실패");
                    }
                }
            }
        }
    }
    private static void print(Connection db, long member, Slot slot, LocalDate today) throws Exception {
        Long profile = profile(db, member, slot.profile);
        Long medication = profile == null ? null : medication(db, profile, slot.drug.item);
        if (medication == null) {
            System.out.println(slot.profile + "\t" + profile + "\tMISSING\t" + slot.drug.name + "\t" + slot.initial);
            return;
        }
        try (var statement = db.prepareStatement("SELECT start_date,end_date,dose_times FROM my_drug WHERE my_drug_id=? AND profile_id=?")) {
            statement.setLong(1, medication); statement.setLong(2, profile);
            try (var rows = statement.executeQuery()) {
                rows.next();
                System.out.println(slot.profile + "\t" + profile + "\t" + medication + "\t" + slot.drug.name + "\t"
                        + state(rows.getDate(1), rows.getDate(2), rows.getString(3), today) + "\t" + rows.getDate(1) + "\t" + rows.getDate(2) + "\t" + rows.getString(3));
            }
        }
    }
    private static String state(java.sql.Date from, java.sql.Date to, String times, LocalDate today) {
        if (from == null && to == null && times == null) return "UNSCHEDULED";
        if (from == null || to == null || times == null) return "INVALID";
        if (from.toLocalDate().isAfter(today)) return "FUTURE";
        if (to.toLocalDate().isBefore(today)) return "ENDED";
        return "ACTIVE";
    }
    private static void printForeignTarget(Connection db, long member) throws Exception {
        try (var statement = db.prepareStatement("SELECT p.profile_id,md.my_drug_id FROM profile p JOIN my_drug md ON md.profile_id=p.profile_id "
                + "WHERE p.member_id<>? ORDER BY p.profile_id,md.my_drug_id LIMIT 1")) {
            statement.setLong(1, member);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) System.out.println("foreignOwnershipTest profileId=" + rows.getLong(1) + " myDrugId=" + rows.getLong(2) + " expectedHTTP=404");
            }
        }
    }
    private static String setting(Properties local, String key) {
        String inherited = System.getenv(key);
        return inherited == null ? local.getProperty(key, "").trim() : inherited;
    }
}

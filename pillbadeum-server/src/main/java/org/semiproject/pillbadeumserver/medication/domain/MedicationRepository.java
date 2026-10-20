package org.semiproject.pillbadeumserver.medication.domain;

import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ProfileChoice;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 일정 화면용 조회 전용 저장소. Profile 엔티티가 아직 없어 프로필 이름이 필요한 조회만 SQL로 한다.
 * my_drug 저장·수정은 기존 drug.domain.MyDrug를 통해서만 해서 같은 검사 규칙을 지킨다.
 */
@Repository
public class MedicationRepository {
    private final JdbcClient jdbc;
    private static final String SELECT = """
            SELECT md.my_drug_id, md.profile_id, p.profile_name, p.relationship_type, COALESCE(d.name,md.custom_name) AS drug_name,
                   md.dose_per_time, md.timing, md.start_date, md.end_date, md.dose_times, md.updated_at, md.dose_confirmed
            FROM my_drug md JOIN profile p ON p.profile_id=md.profile_id LEFT JOIN drug d ON d.item_seq=md.item_seq
            WHERE p.member_id=:memberId
            """;
    private static final RowMapper<MedicationTarget> MAPPER = (rs, row) -> {
        Date start = rs.getDate("start_date"), end = rs.getDate("end_date");
        return new MedicationTarget(rs.getLong("my_drug_id"), rs.getLong("profile_id"), rs.getString("profile_name"),
                rs.getString("relationship_type"), rs.getString("drug_name"), rs.getInt("dose_per_time"),
                rs.getString("timing"), start == null ? null : start.toLocalDate(), end == null ? null : end.toLocalDate(),
                rs.getString("dose_times"), rs.getTimestamp("updated_at").toLocalDateTime(), rs.getBoolean("dose_confirmed"));
    };

    public MedicationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean ownsProfile(long memberId, long profileId) {
        return jdbc.sql("SELECT COUNT(*) FROM profile WHERE member_id=:memberId AND profile_id=:profileId")
                .param("memberId", memberId).param("profileId", profileId).query(Long.class).single() > 0;
    }

    public List<ProfileChoice> profiles(long memberId) {
        return jdbc.sql("SELECT profile_id,profile_name,relationship_type FROM profile WHERE member_id=:memberId ORDER BY profile_id")
                .param("memberId", memberId).query((rs, row) -> new ProfileChoice(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
    }

    public List<MedicationTarget> drugs(long memberId, long profileId) {
        return jdbc.sql(SELECT + " AND md.profile_id=:profileId ORDER BY md.my_drug_id")
                .param("memberId", memberId).param("profileId", profileId).query(MAPPER).list();
    }

    /** 음성 명령은 회원이 가진 약 안에서만 대상을 고른다. 일정 없는 약도 후보에 포함한다. */
    public List<MedicationTarget> allDrugs(long memberId) {
        return jdbc.sql(SELECT + " ORDER BY md.profile_id,md.my_drug_id")
                .param("memberId", memberId).query(MAPPER).list();
    }

    public Optional<MedicationTarget> find(long memberId, long profileId, long myDrugId) {
        return jdbc.sql(SELECT + " AND md.profile_id=:profileId AND md.my_drug_id=:myDrugId")
                .param("memberId", memberId).param("profileId", profileId).param("myDrugId", myDrugId).query(MAPPER).optional();
    }

    public List<MedicationTarget> calendar(long memberId, LocalDate from, LocalDate to) {
        return jdbc.sql(SELECT + " AND md.start_date IS NOT NULL AND md.end_date IS NOT NULL AND md.dose_times IS NOT NULL"
                        + " AND md.start_date<=:toDate AND md.end_date>=:fromDate ORDER BY md.profile_id,md.my_drug_id")
                .param("memberId", memberId).param("fromDate", from).param("toDate", to).query(MAPPER).list();
    }

    public boolean pushEnabled(long memberId) {
        return jdbc.sql("SELECT COUNT(*) FROM notification_setting WHERE member_id=:memberId AND push_enabled=TRUE")
                .param("memberId", memberId).query(Long.class).single() > 0;
    }
}

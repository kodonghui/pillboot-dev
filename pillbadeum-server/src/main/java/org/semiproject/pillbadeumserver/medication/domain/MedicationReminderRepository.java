package org.semiproject.pillbadeumserver.medication.domain;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 복약 알림 스케줄러용 조회. 복용 칸 표 없이 내 약의 기간·복용 시각으로 그 분에 먹을 약을 센다(NOTI-003). */
@Repository
public class MedicationReminderRepository {
    private final JdbcClient jdbc;

    public MedicationReminderRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 회원 1명 = 알림 1건. 가족 프로필의 약도 그 회원으로 묶는다. */
    public record Target(long memberId, int drugCount) {}

    /**
     * 이 분의 발송을 맡는다. 다른 실행(재시작·서버 여러 대)이 이미 맡았으면 false.
     * 자동 커밋으로 바로 저장해 다른 실행이 곧바로 볼 수 있게 한다.
     */
    public boolean claim(LocalDateTime doseAt, LocalDateTime now) {
        try {
            jdbc.sql("INSERT INTO notification_dispatch(dose_at, claimed_at) VALUES(:doseAt, :now)")
                    .param("doseAt", doseAt).param("now", now).update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 오래된 발송 시각은 중복 확인에 필요 없으므로 지운다. */
    public int purgeBefore(LocalDateTime cutoff) {
        return jdbc.sql("DELETE FROM notification_dispatch WHERE dose_at < :cutoff").param("cutoff", cutoff).update();
    }

    /** 알림을 켠 회원의 약 중 오늘이 복용 기간 안이고 이 시:분이 복용 시각인 약을 회원별로 센다. 일정 없는 약은 빠진다. */
    public List<Target> targets(LocalDate date, String time) {
        return jdbc.sql("""
                SELECT p.member_id, COUNT(*) AS drug_count
                FROM my_drug md
                JOIN profile p ON p.profile_id = md.profile_id
                JOIN notification_setting ns ON ns.member_id = p.member_id AND ns.push_enabled = TRUE
                WHERE md.start_date <= :date AND md.end_date >= :date
                  AND FIND_IN_SET(:time, md.dose_times) > 0
                GROUP BY p.member_id
                ORDER BY p.member_id
                """).param("date", date).param("time", time)
                .query((rs, row) -> new Target(rs.getLong("member_id"), rs.getInt("drug_count"))).list();
    }
}

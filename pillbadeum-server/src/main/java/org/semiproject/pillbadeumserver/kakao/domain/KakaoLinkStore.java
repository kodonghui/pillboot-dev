package org.semiproject.pillbadeumserver.kakao.domain;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * V3-KAKAO-012~016: kakao_link_code·kakao_link 표(17_KAKAO_LINK.sql). 조건부 UPDATE·잠금 읽기를 그대로 쓰려고
 * JPA 대신 JdbcTemplate을 쓴다. 코드 원문·카카오 사용자 키 원문은 받지도 두지도 않는다(해시·HMAC만).
 */
@Repository
public class KakaoLinkStore {

    private final JdbcTemplate jdbc;

    public KakaoLinkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 다리 인증·연결 조회에 쓰는 연결 한 줄. */
    public record Link(Long linkId, Long memberId, Long currentProfileId) {
    }

    /** 회원 화면의 연결 한 줄. 지금 프로필 이름을 함께 읽는다. */
    public record MemberLink(Long linkId, Long currentProfileId, String currentProfileName,
                             LocalDateTime linkedAt, LocalDateTime lastUsedAt) {
    }

    /** 쓸 수 있는 코드. 연결을 만들 때 그 행을 잠근 채 읽는다. */
    public record UsableCode(Long codeId, Long memberId, String noticeVersion, LocalDateTime agreedAt) {
    }

    public record ProfileRow(Long profileId, String profileName, String relationshipType) {
    }

    /** 카톡 복약 알림 대상 한 줄(연결 + 그 연결이 지금 고른 프로필의 약 하나). */
    public record ReminderRow(String kakaoUserHmac, Long profileId, String profileName, Long myDrugId, String drugName) {
    }

    /**
     * V3-KAKAO-017: 이 날짜가 복용 기간 안이고 이 시:분이 복용 시각인 약을, 연결이 지금 고른 프로필에서만 읽는다.
     * 일정 없는 약과 프로필을 고르지 않은 연결은 빠진다. 메모·용량·건강정보는 읽지 않는다.
     */
    public List<ReminderRow> findReminderRows(LocalDate date, String time) {
        return jdbc.query("""
                        SELECT l.kakao_user_hmac, p.profile_id, p.profile_name, md.my_drug_id,
                               COALESCE(d.name, md.custom_name, '이름 없는 약')
                        FROM kakao_link l
                        JOIN profile p ON p.profile_id = l.current_profile_id AND p.member_id = l.member_id
                        JOIN my_drug md ON md.profile_id = p.profile_id
                        LEFT JOIN drug d ON d.item_seq = md.item_seq
                        WHERE md.start_date <= ? AND md.end_date >= ? AND FIND_IN_SET(?, md.dose_times) > 0
                        ORDER BY l.link_id, md.my_drug_id""",
                (rs, n) -> new ReminderRow(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5)),
                Date.valueOf(date), Date.valueOf(date), time);
    }

    public Optional<Link> findByUser(String kakaoUserHmac) {
        return jdbc.query("SELECT link_id, member_id, current_profile_id FROM kakao_link WHERE kakao_user_hmac=?",
                (rs, n) -> new Link(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3, Long.class)),
                kakaoUserHmac).stream().findFirst();
    }

    public List<MemberLink> findByMember(Long memberId) {
        return jdbc.query("""
                        SELECT l.link_id, l.current_profile_id, p.profile_name, l.linked_at, l.last_used_at
                        FROM kakao_link l
                        LEFT JOIN profile p ON p.profile_id = l.current_profile_id AND p.member_id = l.member_id
                        WHERE l.member_id=?
                        ORDER BY l.linked_at, l.link_id""",
                (rs, n) -> new MemberLink(rs.getLong(1), (Long) rs.getObject(2, Long.class), rs.getString(3),
                        rs.getTimestamp(4).toLocalDateTime(),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toLocalDateTime()),
                memberId);
    }

    public long countLinks(Long memberId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM kakao_link WHERE member_id=?", Long.class, memberId));
    }

    public long countCodesSince(Long memberId, LocalDateTime since) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM kakao_link_code WHERE member_id=? AND created_at>=?", Long.class,
                memberId, Timestamp.valueOf(since)));
    }

    /** 그 회원의 아직 쓸 수 있는 코드를 모두 못 쓰게 한다(새 코드를 받기 전). */
    public void revokeOpenCodes(Long memberId, LocalDateTime now) {
        jdbc.update("""
                        UPDATE kakao_link_code SET revoked_at=?
                        WHERE member_id=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at>?""",
                Timestamp.valueOf(now), memberId, Timestamp.valueOf(now));
    }

    public void insertCode(Long memberId, String codeHash, String noticeVersion, LocalDateTime agreedAt,
                           LocalDateTime createdAt, LocalDateTime expiresAt) {
        jdbc.update("""
                        INSERT INTO kakao_link_code(member_id, code_hash, notice_version, agreed_at, created_at, expires_at)
                        VALUES(?,?,?,?,?,?)""",
                memberId, codeHash, noticeVersion, Timestamp.valueOf(agreedAt), Timestamp.valueOf(createdAt),
                Timestamp.valueOf(expiresAt));
    }

    /** 쓸 수 있는 코드의 회원 번호(잠그지 않음). 잠금 순서를 회원 → 코드로 맞추려고 먼저 읽는다. */
    public Optional<Long> findUsableCodeMember(String codeHash, LocalDateTime now) {
        return jdbc.query("""
                        SELECT member_id FROM kakao_link_code
                        WHERE code_hash=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at>?""",
                (rs, n) -> rs.getLong(1), codeHash, Timestamp.valueOf(now)).stream().findFirst();
    }

    /** 쓰지 않았고 막히지 않았으며 시간이 남은 코드를 잠가 읽는다. 같은 코드의 두 번째 요청은 앞 트랜잭션 뒤에 빈 값을 받는다. */
    public Optional<UsableCode> lockUsableCode(String codeHash, LocalDateTime now) {
        return jdbc.query("""
                        SELECT code_id, member_id, notice_version, agreed_at FROM kakao_link_code
                        WHERE code_hash=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at>?
                        FOR UPDATE""",
                (rs, n) -> new UsableCode(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getTimestamp(4).toLocalDateTime()),
                codeHash, Timestamp.valueOf(now)).stream().findFirst();
    }

    public void markCodeUsed(Long codeId, LocalDateTime now) {
        jdbc.update("UPDATE kakao_link_code SET used_at=? WHERE code_id=? AND used_at IS NULL",
                Timestamp.valueOf(now), codeId);
    }

    public Long insertLink(Long memberId, String kakaoUserHmac, Long currentProfileId, String noticeVersion,
                           LocalDateTime agreedAt, LocalDateTime linkedAt) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO kakao_link(member_id, kakao_user_hmac, current_profile_id, notice_version, agreed_at, linked_at)
                    VALUES(?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, memberId);
            statement.setString(2, kakaoUserHmac);
            statement.setObject(3, currentProfileId);
            statement.setString(4, noticeVersion);
            statement.setTimestamp(5, Timestamp.valueOf(agreedAt));
            statement.setTimestamp(6, Timestamp.valueOf(linkedAt));
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    public int updateCurrentProfile(Long linkId, Long profileId) {
        return jdbc.update("UPDATE kakao_link SET current_profile_id=? WHERE link_id=?", profileId, linkId);
    }

    /** 마지막 사용 시각은 1분에 한 번만 쓴다(요청마다 쓰기를 늘리지 않음). */
    public void touch(Long linkId, LocalDateTime now) {
        jdbc.update("UPDATE kakao_link SET last_used_at=? WHERE link_id=? AND (last_used_at IS NULL OR last_used_at<?)",
                Timestamp.valueOf(now), linkId, Timestamp.valueOf(now.minusMinutes(1)));
    }

    public int deleteByMember(Long linkId, Long memberId) {
        return jdbc.update("DELETE FROM kakao_link WHERE link_id=? AND member_id=?", linkId, memberId);
    }

    public int deleteByUser(String kakaoUserHmac) {
        return jdbc.update("DELETE FROM kakao_link WHERE kakao_user_hmac=?", kakaoUserHmac);
    }

    public List<ProfileRow> profiles(Long memberId) {
        return jdbc.query("""
                        SELECT profile_id, profile_name, relationship_type FROM profile
                        WHERE member_id=? ORDER BY profile_id""",
                (rs, n) -> new ProfileRow(rs.getLong(1), rs.getString(2), rs.getString(3)), memberId);
    }

    public Optional<Long> lockMember(Long memberId) {
        return jdbc.query("SELECT member_id FROM member WHERE member_id=? FOR UPDATE",
                (rs, n) -> rs.getLong(1), memberId).stream().findFirst();
    }
}

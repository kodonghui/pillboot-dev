package org.semiproject.pillbadeumserver.chat.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 대화방 저장·조회와 소유권 확인에 필요한 프로필 열 조회를 담당한다. */
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    @Query(value = "SELECT member_id FROM member WHERE member_id=:memberId FOR UPDATE", nativeQuery = true)
    java.util.Optional<Long> lockMember(@Param("memberId") Long memberId);

    /** CHAT-001의 소유권 확인용 조회이다. 프로필이 없으면 빈 Optional이며 행을 잠그지 않는다. */
    @Query(value = "SELECT member_id FROM profile WHERE profile_id = :profileId", nativeQuery = true)
    Optional<Long> findProfileOwner(@Param("profileId") Long profileId);

    /** CHAT-004의 외부 호출 전 권한 확인용이다. 건강 사본·질문 본문은 읽지 않는다. */
    @Query(value = "SELECT member_id AS memberId, profile_id AS profileId FROM chat_session WHERE session_id = :sessionId",
            nativeQuery = true)
    Optional<SessionOwner> findSessionOwner(@Param("sessionId") Long sessionId);

    /** CHAT-003: 소유권 확인 뒤 현재 프로필 이름·내 약 개수만 읽고 건강 사본·회원 정보는 응답에 넣지 않는다. */
    @Query(value = """
            SELECT s.profile_id AS profileId, p.profile_name AS profileName, s.title AS title,
                   (SELECT COUNT(*) FROM my_drug d WHERE d.profile_id=s.profile_id) AS drugCount
            FROM chat_session s JOIN profile p ON p.profile_id=s.profile_id
            WHERE s.session_id=:sessionId AND s.member_id=:memberId AND p.member_id=:memberId
            """, nativeQuery = true)
    Optional<HistoryHeader> findHistoryHeader(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId);

    /** 프로필 → 대화방 순서로 잠가 CHAT-002와 잠금 순서를 맞춘다. 저장 직전에 현재 행을 다시 읽는다. */
    @Query(value = "SELECT * FROM chat_session WHERE session_id = :sessionId FOR UPDATE", nativeQuery = true)
    Optional<ChatSession> lockSession(@Param("sessionId") Long sessionId);

    /** 회원·프로필 조건을 모두 적용하고, 활동 시각이 같으면 방 번호 역순으로 순서를 고정한다. */
    List<ChatSession> findAllByMemberIdAndProfileIdOrderByUpdatedAtDescSessionIdDesc(Long memberId, Long profileId);

    /**
     * CHAT-002는 방이 아직 없어도 존재하는 프로필 행을 잠가 생성·재사용 요청의 순서를 정한다.
     * 타 담당의 Profile 엔티티·CRUD를 추가하지 않고 소유 회원 번호만 읽는다.
     */
    @Query(value = "SELECT member_id FROM profile WHERE profile_id = :profileId FOR UPDATE", nativeQuery = true)
    Optional<Long> lockProfileOwner(@Param("profileId") Long profileId);

    /** 소유권 확인 뒤 새 방의 초기 사본에 필요한 출생연도·성별·임신 여부만 읽는다. */
    @Query(value = """
            SELECT profile_birth_year AS birthYear, profile_gender AS gender,
                   CASE WHEN profile_pregnant THEN 1 ELSE 0 END AS pregnantFlag
            FROM profile WHERE profile_id = :profileId AND member_id = :memberId
            """, nativeQuery = true)
    Optional<ProfileSnapshot> findProfileSnapshot(@Param("profileId") Long profileId,
                                                 @Param("memberId") Long memberId);

    /**
     * 같은 회원·프로필의 방 중 메시지가 하나도 없는 가장 최근 방 한 개를 찾는다.
     * 제목이 null이어도 메시지가 있으면 사용한 방이므로 빈 방으로 판단하지 않는다.
     */
    @Query(value = """
            SELECT cs.* FROM chat_session cs
            WHERE cs.member_id = :memberId AND cs.profile_id = :profileId
              AND NOT EXISTS (SELECT 1 FROM chat_message m WHERE m.session_id = cs.session_id)
            ORDER BY cs.updated_at DESC, cs.session_id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<ChatSession> findEmptySession(@Param("memberId") Long memberId,
                                          @Param("profileId") Long profileId);

    /** SQL 별칭으로 받는 초기 사본 값이다. pregnantFlag의 0·1은 Service에서 boolean으로 바꾼다. */
    interface ProfileSnapshot {
        Integer getBirthYear();
        String getGender();
        Integer getPregnantFlag();
    }

    interface SessionOwner {
        Long getMemberId();
        Long getProfileId();
    }

    interface HistoryHeader {
        Long getProfileId();
        String getProfileName();
        String getTitle();
        long getDrugCount();
    }
}

package org.semiproject.pillbadeumserver.chat.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    // 방이 아직 없어도 항상 존재하는 프로필 행을 잠가 생성/재사용 요청의 순서를 정한다.
    // 타 담당의 Profile 엔티티/CRUD를 새로 만들지 않고 필요한 열만 읽는다.
    @Query(value = "SELECT member_id FROM profile WHERE profile_id = :profileId FOR UPDATE", nativeQuery = true)
    Optional<Long> lockProfileOwner(@Param("profileId") Long profileId);

    @Query(value = """
            SELECT profile_birth_year AS birthYear, profile_gender AS gender,
                   CASE WHEN profile_pregnant THEN 1 ELSE 0 END AS pregnantFlag
            FROM profile WHERE profile_id = :profileId AND member_id = :memberId
            """, nativeQuery = true)
    Optional<ProfileSnapshot> findProfileSnapshot(@Param("profileId") Long profileId,
                                                 @Param("memberId") Long memberId);

    @Query(value = """
            SELECT cs.* FROM chat_session cs
            WHERE cs.member_id = :memberId AND cs.profile_id = :profileId
              AND NOT EXISTS (SELECT 1 FROM chat_message m WHERE m.session_id = cs.session_id)
            ORDER BY cs.updated_at DESC, cs.session_id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<ChatSession> findEmptySession(@Param("memberId") Long memberId,
                                          @Param("profileId") Long profileId);

    interface ProfileSnapshot {
        Integer getBirthYear();
        String getGender();
        Integer getPregnantFlag();
    }
}

package org.semiproject.pillbadeumserver.medication.domain;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/** 푸시 구독 조회·저장·삭제. 알림을 끄면 그 회원의 브라우저 주소를 서버에 남기지 않는다. */
public interface PushSubscriptionRepository extends Repository<PushSubscription, Long> {
    long countByMemberId(Long memberId);

    Optional<PushSubscription> findByEndpoint(String endpoint);

    /** 같은 브라우저(endpoint)면 키·브라우저 정보를 고치고 지금 회원으로 옮긴다. 처음 저장 시각은 그대로 둔다. */
    @Modifying
    @Query(value = """
            INSERT INTO push_subscription(member_id, endpoint, p256dh, auth, user_agent, created_at, updated_at)
            VALUES(:memberId, :endpoint, :p256dh, :auth, :userAgent, :now, :now) AS incoming
            ON DUPLICATE KEY UPDATE member_id = incoming.member_id, p256dh = incoming.p256dh, auth = incoming.auth,
                user_agent = incoming.user_agent, updated_at = incoming.updated_at
            """, nativeQuery = true)
    void upsert(@Param("memberId") long memberId, @Param("endpoint") String endpoint, @Param("p256dh") String p256dh,
                @Param("auth") String auth, @Param("userAgent") String userAgent, @Param("now") LocalDateTime now);

    @Modifying
    @Query("delete from PushSubscription p where p.memberId = :memberId")
    int deleteAllOfMember(@Param("memberId") Long memberId);
}

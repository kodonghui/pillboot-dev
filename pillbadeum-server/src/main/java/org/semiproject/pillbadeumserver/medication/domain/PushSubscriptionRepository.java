package org.semiproject.pillbadeumserver.medication.domain;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 푸시 구독 조회·삭제. 알림을 끄면 그 회원의 브라우저 주소를 서버에 남기지 않는다. */
public interface PushSubscriptionRepository extends Repository<PushSubscription, Long> {
    long countByMemberId(Long memberId);

    @Modifying
    @Query("delete from PushSubscription p where p.memberId = :memberId")
    int deleteAllOfMember(@Param("memberId") Long memberId);
}

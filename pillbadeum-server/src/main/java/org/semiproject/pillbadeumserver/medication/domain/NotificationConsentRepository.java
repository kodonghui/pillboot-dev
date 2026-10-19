package org.semiproject.pillbadeumserver.medication.domain;

import org.semiproject.pillbadeumserver.member.domain.Consent;
import org.semiproject.pillbadeumserver.member.domain.ConsentHistory;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 복약 알림 수신 동의(MEDICATION_REMINDER) 이력. 회원 담당의 Consent/ConsentHistory 엔티티를 그대로 쓴다.
 * 동의가 살아 있는지는 지금 시행 중인 약관에 대한 그 회원의 가장 최근 이력이 AGREED인지로 본다.
 */
public interface NotificationConsentRepository extends Repository<ConsentHistory, Long> {
    @Query("""
            select c from Consent c
            where c.termsType = 'MEDICATION_REMINDER' and c.active = true and c.effectiveAt <= :now
            order by c.effectiveAt desc, c.termsId desc
            """)
    List<Consent> findCurrentTerms(@Param("now") LocalDateTime now, Limit limit);

    @Query("""
            select h from ConsentHistory h
            where h.member.memberId = :memberId and h.consent.termsId = :termsId
            order by h.recordedAt desc, h.consentHistoryId desc
            """)
    List<ConsentHistory> findLatest(@Param("memberId") Long memberId, @Param("termsId") Long termsId, Limit limit);

    ConsentHistory save(ConsentHistory history);
}

package org.semiproject.pillbadeumserver.member.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ConsentRepository extends JpaRepository<Consent, Long> {

    /* 활성 상태이고, 시행일시가 now 이전인 약관을 약관 ID순으로 조회할 때 사용 */
    List<Consent> findByActiveTrueAndEffectiveAtLessThanEqualOrderByTermsIdAsc(
        LocalDateTime now
    );

}

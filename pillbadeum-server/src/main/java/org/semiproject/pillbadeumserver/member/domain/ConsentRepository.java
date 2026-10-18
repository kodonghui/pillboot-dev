package org.semiproject.pillbadeumserver.member.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ConsentRepository extends JpaRepository<Consent, Long> {

    /* 활성 상태이고, 시행일시가 now 이전인 약관을 약관 ID순으로 조회할 때 사용 */

    /* 내일부터 적용할 약관을 미리 db에 넣어뒀어도 오늘 회원가입 화면에는 나오지 않는다. (시행일시 <= 현재)
    (약관을 미리 등록해 두고 정해진 날부터 보여주고자 할 때 쓰기 위해) */

    List<Consent> findByActiveTrueAndEffectiveAtLessThanEqualOrderByTermsIdAsc(
        LocalDateTime now
    );

}

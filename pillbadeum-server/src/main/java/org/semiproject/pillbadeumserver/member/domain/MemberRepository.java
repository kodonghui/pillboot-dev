package org.semiproject.pillbadeumserver.member.domain;


import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /* 이메일 중복 확인에 사용 */
    boolean existsByEmail(String email);

    /* 로그인 할 회원 찾을 때 사용 */
    Optional<Member> findByEmail(String email);
}

package org.semiproject.pillbadeumserver.member.domain;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    @Query(value = "SELECT member_id FROM member WHERE member_id=:id FOR UPDATE", nativeQuery = true)
    Optional<Long> lockMemberId(@Param("id") Long id);

    /* 이메일 중복 확인에 사용 */
    boolean existsByEmail(String email);

    /* 로그인 할 회원 찾을 때 사용 */
    Optional<Member> findByEmail(String email);
}

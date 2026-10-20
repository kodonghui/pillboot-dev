package org.semiproject.pillbadeumserver.profile.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProfileRepository extends JpaRepository<Profile, Long> {

    /* 로그인 한 회원의 프로필 목록 */
    List<Profile> findByMember_MemberIdOrderByProfileIdAsc(Long memberId);

    /* 해당 회원 소유의 프로필 한 개 */
    Optional<Profile> findByProfileIdAndMember_MemberId(Long profileId, Long memberId);
}

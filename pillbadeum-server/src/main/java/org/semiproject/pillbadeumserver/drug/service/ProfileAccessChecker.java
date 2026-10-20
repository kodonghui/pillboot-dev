package org.semiproject.pillbadeumserver.drug.service;

import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.profile.domain.Profile;
import org.semiproject.pillbadeumserver.profile.domain.ProfileRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/* 설명. 경로의 profileId 가 로그인한 회원의 프로필인지 확인한다.
 *  ----------------------------------------------------------------------------------
 *  내 약 API 는 모두 /api/profiles/{profileId}/... 형태라서, 다른 회원의 profileId 를 넣으면
 *  남의 복용 약이 보이게 된다. 그래서 모든 서비스 메서드 맨 앞에서 checkOwner(profileId) 를 호출한다.
 *  ----------------------------------------------------------------------------------
 *  - 프로필 담당의 ProfileRepository 로 프로필을 찾는다. (예전에는 JdbcTemplate 으로 member_id 만 읽었다)
 *  - 없는 프로필 → 404, 남의 프로필 → 403, 비로그인 → 401 (SecurityUtil)
 *    ProfileRepository.findByProfileIdAndMember_MemberId 는 남의 프로필도 '없음'으로 보여서 403 · 404 를
 *    나눌 수 없으므로 findById 로 찾은 뒤 주인을 비교한다.
 *  - Profile.member 는 지연 로딩이다. 트랜잭션 밖에서 불리는 경우(MyDrugService.registerAll 은
 *    NOT_SUPPORTED)에도 member 를 읽을 수 있도록 이 메서드에 읽기 전용 트랜잭션을 건다.
 * */
@Component
public class ProfileAccessChecker {

    private final ProfileRepository profileRepository;

    public ProfileAccessChecker(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    /* 목차. 1. 프로필이 있고, 로그인한 회원의 것인지 확인한다. 아니면 예외를 던진다. */
    @Transactional(readOnly = true)
    public void checkOwner(Long profileId) {

        if (profileId == null) {
            throw new NotFoundException("존재하지 않는 프로필입니다.");
        }

        Profile profile = profileRepository.findById(profileId)
                .orElseThrow(() -> new NotFoundException("존재하지 않는 프로필입니다."));

        SecurityUtil.checkOwner(profile.getMember().getMemberId());
    }
}

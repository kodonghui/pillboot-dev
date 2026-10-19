package org.semiproject.pillbadeumserver.profile.service;


import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.member.domain.Member;
import org.semiproject.pillbadeumserver.member.domain.MemberRepository;
import org.semiproject.pillbadeumserver.profile.domain.Profile;
import org.semiproject.pillbadeumserver.profile.domain.ProfileRepository;
import org.semiproject.pillbadeumserver.profile.dto.ProfileCreateRequest;
import org.semiproject.pillbadeumserver.profile.dto.ProfileResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProfileService {

    private final ProfileRepository profileRepository;
    private final MemberRepository memberRepository;

    @Autowired
    public ProfileService(ProfileRepository profileRepository, MemberRepository memberRepository) {
        this.profileRepository = profileRepository;
        this.memberRepository = memberRepository;
    }

    /* 새 프로필 생성 */
    /* 해당 트랜잭션의 흐름은
    *  로그인한 회원 확인 -> 회원에 연결된 프로필 만들기 -> DB 저장 -> 응답 DTO로 변환
    */
    @Transactional
    public ProfileResponse createProfile(ProfileCreateRequest request) {
        // 1. 토큰에서 로그인 한 회원 ID를 가져온다.
        Long memberId = SecurityUtil.getCurrentMemberId();

        // 2. 새 프로필에 연결할 회원을 조회한다. 만약 없다면 404 오류 반환.
        Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new NotFoundException("해당 회원이 존재하지 않습니다."));

        // 3. 요청값과 조회한 회원으로 새 프로필을 만든다.
        Profile profile = new Profile(
            member,
            request.getProfileName(),
            request.getRelationshipType(),
            request.getProfileBirthYear(),
            request.getProfileGender(),
            request.getProfileHeight(),
            request.getProfileWeight(),
            request.getProfilePregnant()
        );

        Profile result = profileRepository.save(profile);

        return ProfileResponse.from(result);
    }


    /* 로그인 한 회원의 프로필 목록 조회 */
    @Transactional(readOnly = true)
    public List<ProfileResponse> getProfiles() {

        Long memberId = SecurityUtil.getCurrentMemberId();

        return profileRepository.findByMember_MemberIdOrderByProfileIdAsc(memberId)
                                .stream()
                                .map(profile -> (ProfileResponse.from(profile)))
                                .toList();
    }
}

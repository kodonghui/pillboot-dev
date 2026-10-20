package org.semiproject.pillbadeumserver.profile.service;


import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.member.domain.Member;
import org.semiproject.pillbadeumserver.member.domain.MemberRepository;
import org.semiproject.pillbadeumserver.profile.domain.Profile;
import org.semiproject.pillbadeumserver.profile.domain.ProfileRepository;
import org.semiproject.pillbadeumserver.profile.dto.ProfileCreateRequest;
import org.semiproject.pillbadeumserver.profile.dto.ProfileResponse;
import org.semiproject.pillbadeumserver.profile.dto.ProfileUpdateRequest;
import org.semiproject.pillbadeumserver.image.service.PhotoLifecycleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProfileService {

    private final ProfileRepository profileRepository;
    private final MemberRepository memberRepository;
    private final PhotoLifecycleService photos;
    private final org.semiproject.pillbadeumserver.chat.service.VectorCleanupService vectors;

    @Autowired
    public ProfileService(ProfileRepository profileRepository, MemberRepository memberRepository,
                          PhotoLifecycleService photos, org.semiproject.pillbadeumserver.chat.service.VectorCleanupService vectors) {
        this.vectors = vectors;
        this.profileRepository = profileRepository;
        this.photos = photos;
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


    /* 로그인 한 회원의 프로필 수정 */
    @Transactional
    public ProfileResponse updateProfile(Long profileId, ProfileUpdateRequest request) {

        // 1. 토큰에서 로그인 한 회원 ID를 가져온다.
        Long memberId = SecurityUtil.getCurrentMemberId();

        // 2. 로그인 한 회원 소유의 프로필을 조회한다.
        Profile profile  = profileRepository.findByProfileIdAndMember_MemberId(profileId, memberId)
            .orElseThrow(() -> new NotFoundException("해당 프로필이 존재하지 않습니다."));

        // 3. 프로필값과 수정일시를 변경한다.
        profile.updateProfile(
            request.getProfileName(),
            request.getRelationshipType(),
            request.getProfileBirthYear(),
            request.getProfileGender(),
            request.getProfileHeight(),
            request.getProfileWeight(),
            request.getProfilePregnant()
        );

        // 4. 변경된 객체를 응답 dto로 변환한다.
        return ProfileResponse.from(profile);
    }


    /* 로그인 한 회원의 프로필 삭제 */
    @Transactional
    public void deleteProfile(Long profileId) {

        // 1. 토큰에서 로그인 한 회원 ID를 가져온다.
        Long memberId = SecurityUtil.getCurrentMemberId();
        // PROF-004: 두 프로필을 동시에 지워도 개수 검사는 같은 회원 잠금 뒤의 최신 값으로 수행함.
        memberRepository.lockMemberId(memberId)
            .orElseThrow(() -> new NotFoundException("해당 회원이 존재하지 않습니다."));

        // 2. 다른 회원의 프로필이거나, 프로필이 존재하지 않으면 삭제하지 않는다.
        Profile profile = profileRepository
            .findByProfileIdAndMember_MemberId(profileId, memberId)
            .orElseThrow(()-> new NotFoundException("해당 프로필이 존재하지 않습니다."));

        // 3. 마지막 프로필은 삭제할 수 없다.
        if(profileRepository.countByMember_MemberId(memberId) <= 1) {
            throw new BusinessException(
                ErrorCode.LAST_PROFILE, "마지막 프로필은 삭제할 수 없습니다."
            );
        }

        vectors.profileDeleting(profileId);

        // 4. 채팅방을 참조하는 메세지/요약을 먼저 삭제한다.
        profileRepository.deleteChatMessagesByProfileId(profileId);
        profileRepository.deleteChatSummariesByProfileId(profileId);

        // 5. 채팅방과 이 프로필에 등록된 약을 삭제한다.
        profileRepository.deleteChatSessionsByProfileId(profileId);
        profileRepository.deleteMyDrugsByProfileId(profileId);

        // 사진 FK는 먼저 정리하고, 실제 파일은 프로필 삭제 커밋 후에만 지운다.
        photos.removeForProfile(profileId);

        // 6. 마지막으로 프로필을 삭제한다.
        profileRepository.delete(profile);

    }
}

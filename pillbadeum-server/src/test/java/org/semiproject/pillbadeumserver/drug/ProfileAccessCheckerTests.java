package org.semiproject.pillbadeumserver.drug;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.ForbiddenException;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.CustomUserDetails;
import org.semiproject.pillbadeumserver.drug.service.ProfileAccessChecker;
import org.semiproject.pillbadeumserver.member.domain.Member;
import org.semiproject.pillbadeumserver.profile.domain.Profile;
import org.semiproject.pillbadeumserver.profile.domain.ProfileRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/* 설명. ProfileAccessChecker (T1 교체 후 ProfileRepository 사용) 가 404 · 403 · 401 을 나누는지 확인한다. */
class ProfileAccessCheckerTests {

    private static final Long ME = 1L;
    private static final Long SOMEONE_ELSE = 9L;

    private final ProfileRepository profileRepository = mock(ProfileRepository.class);
    private final ProfileAccessChecker checker = new ProfileAccessChecker(profileRepository);

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownProfilePasses() {
        login(ME);
        Profile mine = profileOwnedBy(ME);   // when() 안에서 다른 가짜를 만들면 스터빙이 섞이므로 먼저 만든다
        when(profileRepository.findById(2L)).thenReturn(Optional.of(mine));

        assertThatCode(() -> checker.checkOwner(2L)).doesNotThrowAnyException();
    }

    @Test
    void foreignProfileIsForbidden() {
        login(ME);
        Profile others = profileOwnedBy(SOMEONE_ELSE);
        when(profileRepository.findById(4L)).thenReturn(Optional.of(others));

        assertThatThrownBy(() -> checker.checkOwner(4L)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void missingProfileIsNotFound() {
        login(ME);
        when(profileRepository.findById(99999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> checker.checkOwner(99999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void nullProfileIdIsNotFoundWithoutQuerying() {
        login(ME);

        assertThatThrownBy(() -> checker.checkOwner(null)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(profileRepository);
    }

    @Test
    void anonymousRequestIsUnauthorized() {
        Profile mine = profileOwnedBy(ME);
        when(profileRepository.findById(2L)).thenReturn(Optional.of(mine));

        assertThatThrownBy(() -> checker.checkOwner(2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    private static void login(Long memberId) {
        CustomUserDetails user = new CustomUserDetails(memberId, "fixture@example.test", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private static Profile profileOwnedBy(Long memberId) {
        Member member = mock(Member.class);
        when(member.getMemberId()).thenReturn(memberId);
        Profile profile = mock(Profile.class);
        when(profile.getMember()).thenReturn(member);
        return profile;
    }
}

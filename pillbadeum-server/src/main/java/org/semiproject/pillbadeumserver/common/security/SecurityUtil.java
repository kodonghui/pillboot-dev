package org.semiproject.pillbadeumserver.common.security;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.ForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Objects;
import java.util.Optional;

/* 설명. 현재 로그인한 회원을 꺼내고, '내 데이터인지' 확인하는 도구 (COMMON-003)
 *  ----------------------------------------------------------------------------------
 *  모든 도메인(프로필, 약, 일정, 알림, 대화방)이 "로그인한 회원의 것만 보기"를 지켜야 한다.
 *  각자 다르게 구현하면 한 군데만 빠져도 다른 사람의 복용 약이 보이게 되므로, 확인 방법을 하나로 통일한다.
 *  ----------------------------------------------------------------------------------
 *  사용 예) 서비스 계층에서
 *      Profile profile = profileRepository.findById(profileId)
 *              .orElseThrow(() -> new NotFoundException("존재하지 않는 프로필입니다."));
 *      SecurityUtil.checkOwner(profile.getMemberId());   // 내 프로필이 아니면 403
 * */
public final class SecurityUtil {

    private SecurityUtil() {
    }

    /* 설명. 로그인한 사용자. 비로그인 요청이면 빈 Optional */
    public static Optional<CustomUserDetails> findCurrentUser() {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /* 설명. 로그인한 회원의 PK. 비로그인이면 401 */
    public static Long getCurrentMemberId() {
        return findCurrentUser()
                .map(CustomUserDetails::getMemberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다."));
    }

    /* 설명. 데이터의 주인(회원 PK)이 로그인한 회원과 다르면 403 */
    public static void checkOwner(Long ownerMemberId) {
        if (!Objects.equals(ownerMemberId, getCurrentMemberId())) {
            throw new ForbiddenException();
        }
    }
}

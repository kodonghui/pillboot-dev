package org.semiproject.pillbadeumserver.common.exception;

/* 설명. 로그인은 했지만 내 데이터가 아닌 것에 접근할 때 -> 403 Forbidden (COMMON-003)
 *  예) 다른 회원의 프로필 · 약 · 일정 · 대화방을 조회하거나 수정하려는 경우
 *  보통은 직접 던지지 않고 SecurityUtil.checkOwner(ownerMemberId) 를 부른다. */
public class ForbiddenException extends BusinessException {

    public ForbiddenException() {
        super(ErrorCode.FORBIDDEN, "본인의 데이터만 접근할 수 있습니다.");
    }

    public ForbiddenException(String detail) {
        super(ErrorCode.FORBIDDEN, detail);
    }
}

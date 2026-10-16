package org.semiproject.pillbadeumserver.common.exception;

/* 설명. 아이디 또는 비밀번호가 틀렸을 때 -> 401 Unauthorized (AUTH-003)
 *  보안을 위해 '아이디가 없음'과 '비밀번호가 틀림'을 구별하지 않고 같은 메시지를 쓴다. */
public class LoginFailedException extends BusinessException {

    public LoginFailedException() {
        super(ErrorCode.LOGIN_FAILED, "아이디 또는 비밀번호가 올바르지 않습니다.");
    }

    public LoginFailedException(String detail) {
        super(ErrorCode.LOGIN_FAILED, detail);
    }
}

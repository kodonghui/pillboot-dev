package org.semiproject.pillbadeumserver.common.exception;

/* 설명. 이미 있는 값과 겹칠 때 -> 409 Conflict (예: 중복 이메일, 이미 등록한 약) */
public class DuplicateException extends BusinessException {

    public DuplicateException() {
        super(ErrorCode.DUPLICATE, "이미 존재하는 값입니다.");
    }

    public DuplicateException(String detail) {
        super(ErrorCode.DUPLICATE, detail);
    }
}

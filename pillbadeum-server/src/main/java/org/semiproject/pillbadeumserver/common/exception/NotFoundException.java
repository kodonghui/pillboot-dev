package org.semiproject.pillbadeumserver.common.exception;

/* 설명. 조회하려는 자원(회원, 프로필, 약, 일정, 대화방 등)이 없을 때 -> 404 Not Found */
public class NotFoundException extends BusinessException {

    public NotFoundException() {
        super(ErrorCode.NOT_FOUND, "요청한 데이터를 찾을 수 없습니다.");
    }

    public NotFoundException(String detail) {
        super(ErrorCode.NOT_FOUND, detail);
    }
}

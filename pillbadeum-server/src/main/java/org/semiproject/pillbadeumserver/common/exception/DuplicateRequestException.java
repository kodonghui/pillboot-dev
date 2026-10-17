package org.semiproject.pillbadeumserver.common.exception;

/* 설명. 같은 요청이 아직 처리 중인데 또 들어왔을 때 -> 409 Conflict (COMMON-002)
 *  사진 회원 단위/채팅방 단위의 처리 중 제어를 구현하는 서비스에서 던집니다. */
public class DuplicateRequestException extends BusinessException {

    public DuplicateRequestException() {
        super(ErrorCode.DUPLICATE_REQUEST, "같은 요청을 처리하고 있습니다. 잠시만 기다려주세요.");
    }

    public DuplicateRequestException(String detail) {
        super(ErrorCode.DUPLICATE_REQUEST, detail);
    }
}

package org.semiproject.pillbadeumserver.common.exception;

/* 설명. 우리 서비스가 '예상한' 실패를 나타내는 모든 예외의 부모
 *  ----------------------------------------------------------------------------------
 *  GlobalExceptionHandler 는 이 예외 하나만 잡으면, ErrorCode 를 보고 상태 코드와 응답을 만든다.
 *  그래서 새 예외를 만들 때 GlobalExceptionHandler 를 고칠 필요가 없다.
 *  - errorCode : 상태 코드 · 자체 코드 · 설명
 *  - message   : 화면에 보여줄 안전한 문구 (ErrorResponse 의 message로 나간다)
 * */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

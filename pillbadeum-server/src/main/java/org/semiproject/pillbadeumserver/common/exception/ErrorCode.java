package org.semiproject.pillbadeumserver.common.exception;

import org.springframework.http.HttpStatus;

/* 설명. 애플리케이션 전체에서 쓰는 에러 코드 모음
 *  ----------------------------------------------------------------------------------
 *  하나의 에러 코드 = HTTP 상태 코드 + 자체 코드 + 짧은 설명
 *  예외를 던질 때 상태 코드를 직접 고르지 않고 이 목록에서 고르면, 같은 상황에 같은 응답이 나간다.
 *  ----------------------------------------------------------------------------------
 *  사용 규칙
 *  1) 대부분은 공통 예외로 충분하다. (NotFoundException, ForbiddenException, DuplicateException 등)
 *     예) throw new NotFoundException("존재하지 않는 프로필입니다.");
 *  2) 내 기능만의 코드가 꼭 필요하면 아래 '도메인별 코드' 자리에 내 도메인 접두사로 추가하고
 *     throw new BusinessException(ErrorCode.XXX, "상세 메시지"); 로 던진다.
 *  3) 외부에 보내는 code는 API 명세의 영문 이름을 사용한다.
 *     기능별 실패는 해당 API의 오류 표에 맞는 코드를 추가한다.
 * */
public enum ErrorCode {

    /* 목차. 1. 공통 (COMMON) */
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해주세요."),
    INVALID_REQUEST_FORMAT(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "요청 형식을 확인해주세요."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "이 요청을 처리할 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 데이터를 찾을 수 없습니다."),
    DUPLICATE(HttpStatus.CONFLICT, "CONFLICT", "데이터 조건이 충돌합니다."),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "이미 처리 중인 요청입니다."),
    INVALID_FILE(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE", "지원하지 않는 이미지 형식입니다."),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "업로드할 수 있는 파일 크기를 넘었습니다."),
    EXTERNAL_API_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "EXTERNAL_API_FAILED", "외부 서비스 호출에 실패했습니다."),
    EXTERNAL_API_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "EXTERNAL_TIMEOUT", "외부 서비스 응답이 늦어지고 있습니다."),
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "EXTERNAL_API_FAILED", "AI 기능을 지금 사용할 수 없습니다."),
    REQUEST_FAILED(HttpStatus.BAD_REQUEST, "REQUEST_FAILED", "요청을 처리할 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),

    /* 목차. 2. 도메인별 코드 (필요할 때 자기 자리에 추가) */
    // 회원 (AUTH)
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "이메일 또는 비밀번호가 맞지 않습니다."),

    // 프로필 (PROF)

    // 약 등록 · 이미지 처리 (REG)

    // 내 약 목록 (DRUG)

    // 복약 일정 (SCHED)

    // 복약 알림 (NOTI)

    // 복약 기록 (DOSE)

    // AI 챗봇 (CHAT)

    ;

    private final HttpStatus status;
    private final String code;
    private final String description;

    ErrorCode(HttpStatus status, String code, String description) {
        this.status = status;
        this.code = code;
        this.description = description;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}

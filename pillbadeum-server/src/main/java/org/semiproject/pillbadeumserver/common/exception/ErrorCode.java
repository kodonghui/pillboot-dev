package org.semiproject.pillbadeumserver.common.exception;

import org.springframework.http.HttpStatus;

/* 설명. 애플리케이션 전체에서 쓰는 에러 코드 모음
 *  ----------------------------------------------------------------------------------
 *  하나의 에러 코드 = HTTP 상태 코드 + 자체 코드 + 짧은 설명
 *  예외를 던질 때 상태 코드를 직접 고르지 않고 이 목록에서 고르면, 같은 상황에 같은 응답이 나간다.
 *  ----------------------------------------------------------------------------------
 *  사용 규칙
 *  1) 대부분은 공통 예외로 충분하다. (NotFoundException, ForbiddenException, InvalidFileException 등)
 *     예) throw new NotFoundException("존재하지 않는 프로필입니다.");
 *  2) 중복 값·중복 요청·로그인 실패는 별도 예외 클래스 없이 기존 코드를 사용한다.
 *     예) throw new BusinessException(ErrorCode.DUPLICATE, "이미 등록된 값입니다.");
 *     같은 방식으로 ErrorCode.DUPLICATE_REQUEST, ErrorCode.LOGIN_FAILED 를 사용한다.
 *  3) 내 기능만의 코드가 꼭 필요하면 아래 '도메인별 코드' 자리에 내 도메인 접두사로 추가하고
 *     throw new BusinessException(ErrorCode.XXX, "상세 메시지"); 로 던진다.
 *  4) 외부에 보내는 code는 API 명세의 영문 이름을 사용한다.
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
    LAST_PROFILE(HttpStatus.CONFLICT, "LAST_PROFILE", "마지막 프로필은 삭제할 수 없습니다."),

    // 약 등록 · 이미지 처리 (REG)
    PILL_CATALOG_PREPARING(HttpStatus.SERVICE_UNAVAILABLE, "PILL_CATALOG_PREPARING", "의약품 식별 자료를 준비하고 있습니다."),
    IDENTIFICATION_EXPIRED(HttpStatus.GONE, "IDENTIFICATION_EXPIRED", "식별 결과가 만료됐습니다."),
    IDENTIFICATION_REPLACED(HttpStatus.CONFLICT, "IDENTIFICATION_REPLACED", "새 식별 결과가 필요합니다."),
    IDENTIFICATION_NOT_SELECTABLE(HttpStatus.CONFLICT, "IDENTIFICATION_NOT_SELECTABLE", "선택할 수 없는 식별 결과입니다."),
    IDENTIFICATION_SELECTION_CONFLICT(HttpStatus.CONFLICT, "IDENTIFICATION_SELECTION_CONFLICT", "이미 다른 후보를 저장한 결과입니다."),
    IDENTIFICATION_SCHEMA_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "IDENTIFICATION_SCHEMA_NOT_READY", "식별 DB 패치가 필요합니다."),
    IDENTIFICATION_RESULT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "IDENTIFICATION_RESULT_UNAVAILABLE", "식별 결과를 저장하지 못했습니다."),
    IDENTIFICATION_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "IDENTIFICATION_DISABLED", "식별 기능이 일시 중지됐습니다."),

    // 내 약 목록 (DRUG)

    // 복약 일정 (SCHED)

    // 복약 알림 (NOTI)
    CONSENT_REQUIRED(HttpStatus.CONFLICT, "CONSENT_REQUIRED", "알림 수신 동의가 필요합니다."),
    PUSH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "EXTERNAL_API_FAILED", "알림 기능을 지금 사용할 수 없습니다."),

    // 복약 기록 (DOSE)

    // AI 챗봇 (CHAT)
    RETRY_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "RETRY_NOT_ALLOWED", "답변 없는 마지막 질문만 다시 보낼 수 있습니다."),

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

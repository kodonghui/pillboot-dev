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
 *  3) 접두사는 요구사항 ID 와 같다. (AUTH, PROF, REG, DRUG, SCHED, NOTI, DOSE, CHAT)
 *     자기 도메인 자리에만 추가하면 Git 충돌이 거의 나지 않는다.
 * */
public enum ErrorCode {

    /* 목차. 1. 공통 (COMMON) */
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "입력값 검증 실패"),
    INVALID_REQUEST_FORMAT(HttpStatus.BAD_REQUEST, "COMMON_002", "잘못된 요청 형식"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON_003", "인증 실패"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_004", "권한 없음"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_005", "조회 실패"),
    DUPLICATE(HttpStatus.CONFLICT, "COMMON_006", "중복된 값"),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "COMMON_007", "이미 처리 중인 요청"),
    INVALID_FILE(HttpStatus.BAD_REQUEST, "COMMON_008", "잘못된 파일"),
    EXTERNAL_API_FAILED(HttpStatus.BAD_GATEWAY, "COMMON_009", "외부 서비스 호출 실패"),
    EXTERNAL_API_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "COMMON_010", "외부 서비스 응답 시간 초과"),
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "COMMON_011", "AI 기능 사용 불가"),
    REQUEST_FAILED(HttpStatus.BAD_REQUEST, "COMMON_998", "요청 처리 실패"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 내부 오류"),

    /* 목차. 2. 도메인별 코드 (필요할 때 자기 자리에 추가) */
    // 회원 (AUTH)
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "AUTH_001", "로그인 실패"),

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

package org.semiproject.pillbadeumserver.common.exception;

import jakarta.validation.ConstraintViolationException;
import org.semiproject.pillbadeumserver.common.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

/* 설명. 모든 컨트롤러에서 발생한 예외를 한곳에서 가로채 ErrorResponse 로 바꿔 주는 전역 예외 처리기
 *  ----------------------------------------------------------------------------------
 *  처리 순서
 *  1) BusinessException (우리가 던진 예외 전부)  -> ErrorCode 에 적힌 상태 코드
 *  2) 입력값 검증 실패 · 요청 형식 오류          -> 400
 *  3) 업로드 용량 초과                           -> 400 FILE_TOO_LARGE
 *  4) Spring MVC 표준 예외 (404, 405 등)         -> 원래 상태 코드
 *  5) 그 밖의 예외                              -> 500 (원인은 로그에만 남기고 화면에는 숨긴다)
 *  ----------------------------------------------------------------------------------
 *  참고. 401(토큰 없음 · 만료)과 403(권한 부족)은 컨트롤러 앞의 보안 필터에서 생기므로
 *       이 클래스가 아니라 JwtAuthenticationEntryPoint, JwtAccessDeniedHandler 가 응답한다.
 * */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /* 목차. 1. 우리가 던진 예외 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {

        ErrorCode errorCode = e.getErrorCode();

        // 외부 응답과 예외 메시지에는 키·프롬프트·건강정보가 섞일 수 있으므로 기록하지 않는다.
        if (e instanceof ExternalApiException external) {
            log.warn("[외부 API 실패] api={} code={} reason={} causeType={}", external.getApiName(), errorCode.getCode(), external.getFailureReason(),
                    e.getCause() != null ? e.getCause().getClass().getSimpleName() : "-");
        } else {
            log.info("[업무 요청 실패] code={}", errorCode.getCode());
        }

        return toResponse(errorCode, e.getMessage());
    }

    /* 목차. 2-1. @Valid 입력값 검증 실패 -> 400
     *  - @RequestBody(JSON) 검증 실패 : MethodArgumentNotValidException
     *  - @ModelAttribute(form-data) 검증 실패 : BindException
     *  MethodArgumentNotValidException 이 BindException 을 상속하므로 BindException 하나로 둘 다 잡힌다.
     *  DTO에 작성한 검증 메시지 하나를 보내고, 메시지가 없으면 공통 문구를 보낸다.
     *  DTO 검증 메시지에는 실제 입력값이나 민감정보를 넣지 않는다.
     * */

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleBindException(BindException e) {
        String message = "입력값을 확인해주세요.";

        FieldError fieldError = e.getBindingResult().getFieldError();
        if (fieldError != null && fieldError.getDefaultMessage() != null) {
            message = fieldError.getDefaultMessage();
        }

        return toResponse(ErrorCode.INVALID_INPUT, message);
    }


    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> handleMethodValidation(Exception e) {
        return toResponse(ErrorCode.INVALID_INPUT, "입력값을 확인해주세요.");
    }

    // 사전 중복 조회를 동시에 통과해도 409로 맞출 UNIQUE 이름(MySQL 8은 '표.키' 형식)과 안내 문구.
    private static final Map<String, String> DUPLICATE_MESSAGES = Map.of(
            "my_drug.uk_my_drug_profile_item", "이미 이 프로필에 등록된 약입니다.",
            "member.email", "이미 가입된 이메일입니다.");

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataConflict(DataIntegrityViolationException e) {
        String duplicate = concurrentDuplicateMessage(e);
        if (duplicate != null) {
            // 사전 중복 조회를 동시에 통과해도 DB UNIQUE 충돌은 기존 409 계약(등록 중복·가입 이메일 중복)으로 맞춘다.
            log.info("[업무 요청 실패] code={}", ErrorCode.DUPLICATE.getCode());
            return toResponse(ErrorCode.DUPLICATE, duplicate);
        }
        // 그 밖의 UNIQUE·NOT NULL·FK는 같은 사용자 오류라고 단정하지 않는다.
        log.error("[DB 제약 오류] type={} location={}", e.getClass().getSimpleName(), safeLocation(e));
        return toResponse(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDescription());
    }

    /** 알려진 UNIQUE의 MySQL 중복(1062)이면 안내 문구, 아니면 null. 키 이름만 오는 이전 형식도 받는다. */
    private String concurrentDuplicateMessage(Throwable error) {
        for (int depth = 0; error != null && depth < 10; depth++, error = error.getCause()) {
            if (error instanceof org.hibernate.exception.ConstraintViolationException constraint) {
                String name = constraint.getConstraintName();
                if (name == null || constraint.getSQLException().getErrorCode() != 1062) return null;
                String key = name.replace("`", "");
                return DUPLICATE_MESSAGES.entrySet().stream()
                        .filter(entry -> entry.getKey().equals(key) || entry.getKey().endsWith("." + key))
                        .map(Map.Entry::getValue)
                        .findFirst().orElse(null);
            }
        }
        return null;
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException e) {
        return toResponse(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        return toResponse(ErrorCode.FORBIDDEN, "이 요청을 처리할 권한이 없습니다.");
    }

    /* 목차. 2-2. 요청 형식 자체가 잘못된 경우 -> 400
     *  - HttpMessageNotReadableException      : JSON 문법이 틀렸거나 본문을 읽을 수 없음
     *  - MethodArgumentTypeMismatchException  : 경로 · 쿼리의 값 타입이 틀림 (예: /api/profiles/abc)
     *  - MissingServletRequestParameterException / MissingServletRequestPartException : 필수 파라미터 · 파일이 빠짐
     *  주의. 원래 예외 메시지에는 내부 클래스 이름이 들어 있으므로 화면에는 정해진 문구만 보낸다.
     * */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ErrorResponse> handleBadRequestFormat(Exception e) {

        log.info("[요청 형식 오류] {}", e.getClass().getSimpleName());

        return toResponse(ErrorCode.INVALID_REQUEST_FORMAT, "요청 형식과 필수 입력값을 확인해주세요.");
    }

    /* 목차. 3. 업로드 용량 초과 -> 400 (API 공통 계약) */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException e) {

        return toResponse(ErrorCode.FILE_TOO_LARGE, ErrorCode.FILE_TOO_LARGE.getDescription());
    }

    /* 목차. 4 ~ 5. 위에서 처리하지 못한 나머지 예외
     *  Spring MVC 의 표준 예외(없는 주소 404, 지원하지 않는 메서드 405 등)는
     *  org.springframework.web.ErrorResponse 를 구현하고 있으므로 원래 상태 코드를 살려서 응답한다.
     *  그 밖의 예외는 500으로 응답한다. 원인 메시지·외부 응답 본문은 로그와 응답에 넣지 않는다.
     *  SQL, 테이블 이름, 내부 구조가 그대로 노출될 수 있기 때문이다. (COMMON-004)
     * */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {

        if (e instanceof org.springframework.web.ErrorResponse springError) {

            log.info("[요청 처리 실패] {}", e.getClass().getSimpleName());

            ErrorCode code = springError.getStatusCode().is5xxServerError() ? ErrorCode.INTERNAL_ERROR
                    : springError.getStatusCode().value() == HttpStatus.NOT_FOUND.value() ? ErrorCode.NOT_FOUND
                    : springError.getStatusCode().value() == HttpStatus.BAD_REQUEST.value() ? ErrorCode.INVALID_INPUT
                    : ErrorCode.REQUEST_FAILED;
            ErrorResponse body = new ErrorResponse(code.getCode(), code.getDescription());

            return new ResponseEntity<>(body, springError.getHeaders(), springError.getStatusCode());
        }

        log.error("[서버 내부 오류] type={} location={}", e.getClass().getSimpleName(), safeLocation(e));

        return toResponse(ErrorCode.INTERNAL_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
    }

    private ResponseEntity<ErrorResponse> toResponse(ErrorCode errorCode, String detail) {

        ErrorResponse body = new ErrorResponse(errorCode.getCode(), detail);

        return new ResponseEntity<>(body, errorCode.getStatus());
    }

    private String safeLocation(Exception e) {
        for (StackTraceElement frame : e.getStackTrace()) {
            if (frame.getClassName().startsWith("org.semiproject.pillbadeumserver.")) {
                return frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        return "-";
    }
}

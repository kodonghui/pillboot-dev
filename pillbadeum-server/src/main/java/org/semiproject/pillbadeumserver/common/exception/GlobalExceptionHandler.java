package org.semiproject.pillbadeumserver.common.exception;

import jakarta.validation.ConstraintViolationException;
import org.semiproject.pillbadeumserver.common.ErrorResponse;
import org.semiproject.pillbadeumserver.common.util.SecretMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
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

/* 설명. 모든 컨트롤러에서 발생한 예외를 한곳에서 가로채 ErrorResponse 로 바꿔 주는 전역 예외 처리기
 *  ----------------------------------------------------------------------------------
 *  처리 순서
 *  1) BusinessException (우리가 던진 예외 전부)  -> ErrorCode 에 적힌 상태 코드
 *  2) 입력값 검증 실패 · 요청 형식 오류          -> 400
 *  3) 업로드 용량 초과                           -> 413
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

        // 외부 API 실패는 원인을 찾을 수 있도록 원래 예외까지 로그로 남긴다. (서비스 키 등은 가린다)
        if (e instanceof ExternalApiException) {
            log.warn("[외부 API 실패] {} / 원인: {}", e.getMessage(),
                    e.getCause() != null ? SecretMasker.mask(e.getCause().toString()) : "-");
        } else {
            log.info("[{}] {}", errorCode.getCode(), e.getMessage());
        }

        return toResponse(errorCode, e.getMessage());
    }

    /* 목차. 2-1. @Valid 입력값 검증 실패 -> 400
     *  - @RequestBody(JSON) 검증 실패 : MethodArgumentNotValidException
     *  - @ModelAttribute(form-data) 검증 실패 : BindException
     *  MethodArgumentNotValidException 이 BindException 을 상속하므로 BindException 하나로 둘 다 잡힌다.
     *  여러 필드가 동시에 틀렸더라도 화면에 보여주기 쉽도록 첫 번째 메시지만 담는다.
     * */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleBindException(BindException e) {

        FieldError fieldError = e.getBindingResult().getFieldError();
        String detail = fieldError != null
                ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
                : "입력값을 확인해주세요.";

        return toResponse(ErrorCode.INVALID_INPUT, detail);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> handleMethodValidation(Exception e) {
        return toResponse(ErrorCode.INVALID_INPUT, "입력값을 확인해주세요.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataConflict(DataIntegrityViolationException e) {
        return toResponse(ErrorCode.DUPLICATE, "데이터 조건이 충돌합니다. 입력값을 확인해주세요.");
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

        String detail = switch (e) {
            case MethodArgumentTypeMismatchException m -> m.getName() + " 값의 형식이 올바르지 않습니다.";
            case MissingServletRequestParameterException m -> m.getParameterName() + " 값이 필요합니다.";
            case MissingServletRequestPartException m -> m.getRequestPartName() + " 파일이 필요합니다.";
            default -> "요청 본문을 읽을 수 없습니다. JSON 형식을 확인해주세요.";
        };

        return toResponse(ErrorCode.INVALID_REQUEST_FORMAT, detail);
    }

    /* 목차. 3. 업로드 용량 초과 -> 413 (application.yaml 의 spring.servlet.multipart.* 기준) */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException e) {

        ErrorResponse body = new ErrorResponse(ErrorCode.INVALID_FILE.getCode(),
                ErrorCode.INVALID_FILE.getDescription(), "업로드할 수 있는 파일 크기를 넘었습니다.");

        return ResponseEntity.status(HttpStatusCode.valueOf(413)).body(body);
    }

    /* 목차. 4 ~ 5. 위에서 처리하지 못한 나머지 예외
     *  Spring MVC 의 표준 예외(없는 주소 404, 지원하지 않는 메서드 405 등)는
     *  org.springframework.web.ErrorResponse 를 구현하고 있으므로 원래 상태 코드를 살려서 응답한다.
     *  그 밖의 예외는 500 으로 응답하되, 원인(e.getMessage())은 로그에만 남기고 화면에는 보내지 않는다.
     *  SQL, 테이블 이름, 내부 구조가 그대로 노출될 수 있기 때문이다. (COMMON-004)
     * */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {

        if (e instanceof org.springframework.web.ErrorResponse springError) {

            log.info("[요청 처리 실패] {}", e.getClass().getSimpleName());

            ErrorResponse body = new ErrorResponse(ErrorCode.REQUEST_FAILED.getCode(),
                    ErrorCode.REQUEST_FAILED.getDescription(), springError.getBody().getDetail());

            return new ResponseEntity<>(body, springError.getHeaders(), springError.getStatusCode());
        }

        log.error("[서버 내부 오류] {}", e.getClass().getSimpleName());

        return toResponse(ErrorCode.INTERNAL_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
    }

    private ResponseEntity<ErrorResponse> toResponse(ErrorCode errorCode, String detail) {

        ErrorResponse body = new ErrorResponse(errorCode.getCode(), errorCode.getDescription(), detail);

        return new ResponseEntity<>(body, errorCode.getStatus());
    }
}

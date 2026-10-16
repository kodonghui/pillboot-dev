package org.semiproject.pillbadeumserver.common.external;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.util.SecretMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.function.Supplier;

/* 설명. 외부 API 호출을 감싸서 실패 · 시간 초과를 공통 예외로 바꿔 주는 도구 (COMMON-001)
 *  ----------------------------------------------------------------------------------
 *  외부 API 가 실패하는 모양은 여러 가지다. (연결 안 됨, 시간 초과, 4xx/5xx, 응답 형식 오류)
 *  도메인마다 따로 try-catch 를 쓰지 않고 이걸로 감싸면, 화면은 항상 같은 모양(502/504)의 오류를 받는다.
 *  ----------------------------------------------------------------------------------
 *  사용 예)
 *      String body = ExternalApiCaller.call("공공데이터포털", () ->
 *              restClient.get().uri("/...").retrieve().body(String.class));
 *  ----------------------------------------------------------------------------------
 *  - 시간 초과        -> ExternalApiException.timeout()  (504)
 *  - 그 밖의 실패     -> ExternalApiException.failed()   (502)
 *  - 이미 우리 예외   -> 그대로 다시 던진다 (AiUnavailableException 등)
 *  로그에는 원인을 남기되 서비스 키 · API 키는 SecretMasker 로 가린다. (COMMON-004)
 * */
public final class ExternalApiCaller {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiCaller.class);

    private ExternalApiCaller() {
    }

    public static <T> T call(String apiName, Supplier<T> request) {

        long start = System.currentTimeMillis();

        try {
            T result = request.get();
            log.debug("[외부 API] {} 성공 ({}ms)", apiName, System.currentTimeMillis() - start);
            return result;

        } catch (BusinessException e) {
            throw e;

        } catch (ResourceAccessException e) {
            // 연결 실패 · 시간 초과 (I/O 오류)
            log.warn("[외부 API] {} 연결 실패 ({}ms) : {}", apiName,
                    System.currentTimeMillis() - start, SecretMasker.mask(e.getMessage()));
            if (isTimeout(e)) {
                throw ExternalApiException.timeout(apiName, e);
            }
            throw ExternalApiException.failed(apiName, "서버에 연결할 수 없습니다.", e);

        } catch (RestClientResponseException e) {
            // 상대 서버가 4xx / 5xx 를 돌려줌
            log.warn("[외부 API] {} 오류 응답 HTTP {} : {}", apiName, e.getStatusCode().value(),
                    SecretMasker.mask(e.getResponseBodyAsString()));
            throw ExternalApiException.failed(apiName, "(HTTP " + e.getStatusCode().value() + ")", e);

        } catch (RestClientException e) {
            // 응답을 객체로 바꾸지 못함 등
            log.warn("[외부 API] {} 응답 처리 실패 : {}", apiName, SecretMasker.mask(e.getMessage()));
            throw ExternalApiException.failed(apiName, "응답을 처리할 수 없습니다.", e);
        }
    }

    /* 설명. 원인을 따라가며 시간 초과 예외가 있는지 본다. (JDK HttpClient 는 HttpTimeoutException 을 던진다) */
    private static boolean isTimeout(Throwable e) {

        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}

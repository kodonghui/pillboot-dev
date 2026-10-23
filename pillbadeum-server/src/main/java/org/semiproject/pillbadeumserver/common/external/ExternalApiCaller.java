package org.semiproject.pillbadeumserver.common.external;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 외부 호출의 예외 변환. 응답 본문, URL, 예외 메시지에는 키/건강정보가 있을 수 있어 로그에 넣지 않는다. */
public final class ExternalApiCaller {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiCaller.class);

    private ExternalApiCaller() {
    }

    public static <T> T call(String apiName, Supplier<T> request) {
        long start = System.nanoTime();
        try {
            T result = request.get();
            log.debug("[외부 API] api={} result=success durationMs={}", apiName, elapsedMillis(start));
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (ResourceAccessException e) {
            log.warn("[외부 API] api={} type={} durationMs={}", apiName,
                    e.getClass().getSimpleName(), elapsedMillis(start));
            if (isTimeout(e)) {
                throw ExternalApiException.timeout(apiName, e);
            }
            throw ExternalApiException.failed(apiName, "서버에 연결할 수 없습니다.", e);
        } catch (RestClientResponseException e) {
            log.warn("[외부 API] api={} status={} durationMs={}", apiName,
                    e.getStatusCode().value(), elapsedMillis(start));
            throw ExternalApiException.failed(apiName, "(HTTP " + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            log.warn("[외부 API] api={} type={} durationMs={}", apiName,
                    e.getClass().getSimpleName(), elapsedMillis(start));
            throw ExternalApiException.failed(apiName, "응답을 처리할 수 없습니다.", e);
        }
    }

    /** 공공데이터 GET과 임베딩에만 사용한다. 응답 해석/업무 검증은 이 호출 바깥에서 수행한다. */
    public static <T> T callWithRetry(String apiName, Supplier<T> request) {
        return callWithRetry(apiName, request, true);
    }

    /** OCR은 응답을 오래 기다린 뒤 같은 요청을 자동으로 다시 보내지 않음. */
    public static <T> T callWithQuickRetry(String apiName, Supplier<T> request) {
        return callWithRetry(apiName, request, false);
    }

    private static <T> T callWithRetry(String apiName, Supplier<T> request, boolean retryTimeouts) {
        return call(apiName, () -> {
            try {
                return request.get();
            } catch (ResourceAccessException | RestClientResponseException e) {
                boolean timeout = isTimeout(e) || e instanceof RestClientResponseException response
                        && (response.getStatusCode().value() == 408 || response.getStatusCode().value() == 504);
                if (!isTransient(e) || !retryTimeouts && timeout) {
                    throw e;
                }
                log.info("[외부 API] api={} retry=1 type={}", apiName, e.getClass().getSimpleName());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ExternalApiException.timeout(apiName, interrupted);
                }
                return request.get();
            }
        });
    }

    /** 가상 스레드 수와 별개로 실제 외부 호출 수를 제한하며 대기에도 상한을 둔다. */
    public static <T> T withPermit(String apiName, Semaphore permits, long waitMillis, Supplier<T> request) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ExternalApiException.timeout(apiName, e);
        }
        if (!acquired) {
            log.warn("[외부 API] api={} phase=permit result=busy", apiName);
            throw ExternalApiException.busy(apiName);
        }
        try {
            return request.get();
        } finally {
            permits.release();
        }
    }

    private static boolean isTransient(RuntimeException e) {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        if (e instanceof ResourceAccessException) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof InterruptedException) {
                    return false;
                }
            }
            return true;
        }
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return status == 408 || status == 429 || status >= 500;
        }
        return false;
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static long elapsedMillis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}

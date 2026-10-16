package org.semiproject.pillbadeumserver.common.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.semiproject.pillbadeumserver.common.exception.DuplicateRequestException;
import org.semiproject.pillbadeumserver.common.security.CustomUserDetails;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/* 설명. @PreventDuplicateRequest 가 붙은 API 의 '처리 중' 상태를 기억해서 같은 요청을 막는 인터셉터 (COMMON-002)
 *  ----------------------------------------------------------------------------------
 *  같은 요청의 기준 : 회원 PK + HTTP 메서드 + 주소   (예: 7:POST:/api/medications)
 *  - preHandle       : 처리 중 목록에 넣는다. 이미 있으면 DuplicateRequestException(409)
 *  - afterCompletion : 응답이 끝나면 목록에서 뺀다. (예외가 나도 호출된다)
 *  ----------------------------------------------------------------------------------
 *  SSE(챗봇 답변 스트리밍)처럼 비동기로 끝나는 요청은, 스트리밍이 다 끝난 뒤의 내부 처리(ASYNC)에서 뺀다.
 *  만약 어떤 이유로 빠지지 않더라도, 2분이 지난 기록은 무시해서 영원히 막히는 일이 없게 했다.
 * */
@Component
public class DuplicateRequestInterceptor implements AsyncHandlerInterceptor {

    private static final String LOCK_KEY_ATTRIBUTE = DuplicateRequestInterceptor.class.getName() + ".KEY";
    private static final long LOCK_TIMEOUT_MILLIS = 120_000;

    // 요청 기준 -> 시작 시각(밀리초)
    private final Map<String, Long> processing = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {

        // 비동기 요청이 끝날 때의 내부 처리는 검사하지 않는다. (처음 요청에서 이미 등록했다)
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }

        if (!(handler instanceof HandlerMethod handlerMethod)
                || !handlerMethod.hasMethodAnnotation(PreventDuplicateRequest.class)) {
            return true;
        }

        String requester = SecurityUtil.findCurrentUser()
                .map(CustomUserDetails::getMemberId)
                .map(String::valueOf)
                .orElse(request.getRemoteAddr());
        String key = requester + ":" + request.getMethod() + ":" + request.getRequestURI();

        long now = System.currentTimeMillis();
        Long startedAt = processing.putIfAbsent(key, now);

        if (startedAt != null) {
            if (now - startedAt < LOCK_TIMEOUT_MILLIS) {
                throw new DuplicateRequestException();
            }
            // 너무 오래된 기록은 끝나지 못한 것으로 보고 새로 시작한다.
            processing.put(key, now);
        }

        request.setAttribute(LOCK_KEY_ATTRIBUTE, key);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {

        Object key = request.getAttribute(LOCK_KEY_ATTRIBUTE);

        if (key != null) {
            request.removeAttribute(LOCK_KEY_ATTRIBUTE);
            processing.remove(key.toString());
        }
    }
}

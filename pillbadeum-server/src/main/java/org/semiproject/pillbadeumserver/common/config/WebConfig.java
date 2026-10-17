package org.semiproject.pillbadeumserver.common.config;

import java.util.Map;
import org.semiproject.pillbadeumserver.common.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;

/** 요청 추적과 비동기 로그 컨텍스트를 한 곳에서 등록합니다. */
@Configuration(proxyBeanMethods = false)
public class WebConfig {
    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        var registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    TaskDecorator loggingContextTaskDecorator() {
        return task -> {
            Map<String, String> captured = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    if (captured == null) MDC.clear(); else MDC.setContextMap(captured);
                    task.run();
                } finally {
                    if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
                }
            };
        };
    }
}

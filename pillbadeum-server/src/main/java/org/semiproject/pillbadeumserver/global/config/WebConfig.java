package org.semiproject.pillbadeumserver.global.config;

import java.util.Map;
import org.semiproject.pillbadeumserver.global.filter.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;

/** 팀원의 요청 추적 설정. MVC 인터셉터와 CORS 설정은 기존 common 패키지에서 등록합니다. */
@Configuration(value = "globalWebConfig", proxyBeanMethods = false)
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

package org.semiproject.pillbadeumserver;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;

class ExternalFailureDiagnosticsTests {
    @Test void fixedDiagnosticReasonIsLoggedWithoutExposingProviderCauseOrChangingTheErrorBody() {
        var logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var failure = ExternalApiException.failed("공공데이터", "후보 필수 정보 누락",
                new IllegalArgumentException("private_provider_body_with_key"));
            var response = new GlobalExceptionHandler().handleBusinessException(failure);
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody().getMessage()).doesNotContain("후보 필수 정보 누락", "private_provider_body_with_key");
            assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("reason=후보 필수 정보 누락"))
                .noneMatch(e -> e.getFormattedMessage().contains("private_provider_body_with_key"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void saturatedProviderUsesTheExistingCodeWithABusyMessage() {
        var response = new GlobalExceptionHandler().handleBusinessException(ExternalApiException.busy("OpenAI"));
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().getCode()).isEqualTo("EXTERNAL_API_FAILED");
        assertThat(response.getBody().getMessage()).contains("요청이 많습니다");
    }
}

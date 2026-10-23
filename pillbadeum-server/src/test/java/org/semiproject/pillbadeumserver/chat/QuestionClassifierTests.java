package org.semiproject.pillbadeumserver.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.semiproject.pillbadeumserver.chat.service.QuestionClassifier;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.exception.AiUnavailableException;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CHAT-005의 실제 RestClient 요청·분류 결과·실패를 로컬 HTTP 서버로 검증한다.
 * 실제 OpenAI·DB는 사용하지 않으며, 이 테스트 통과를 실제 모델의 분류 정확도로 해석하지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class QuestionClassifierTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INPUT = "synthetic-question-private-marker";
    private HttpServer server;
    private ExecutorService serverExecutor;
    private String baseUrl;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @ParameterizedTest
    @EnumSource(QuestionCategory.class)
    void fourChoicesUseDecisionsAndOnlyPassContinues(QuestionCategory category, CapturedOutput output) {
        AtomicReference<JsonNode> sent = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/decisions", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            sent.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            respond(exchange, 200, answer(category.name()));
        });
        QuestionCategory result = classifier("local-test-key", "decision-only", 1000).classify(" \t" + INPUT + "\n ");
        assertThat(result).isEqualTo(category);
        assertThat(result.isPassed()).isEqualTo(category == QuestionCategory.PASS);
        if (result.isPassed()) {
            assertThat(result.noticeMessage()).isNull();
        } else {
            assertThat(result.noticeMessage()).isNotBlank();
        }
        assertThat(QuestionCategory.OFF_TOPIC.noticeMessage()).contains("약과 복약", "물어봐 주세요");
        assertThat(QuestionCategory.UNSAFE.noticeMessage()).contains("답해 드릴 수 없어요", "의사나 약사");
        assertThat(QuestionCategory.EMERGENCY.noticeMessage()).contains("119", "응급실", "109");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(authorization.get()).isEqualTo("Bearer local-test-key");
        JsonNode request = sent.get();
        assertThat(request.path("model").asString()).isEqualTo("decision-only");
        assertThat(request.path("input").asString()).isEqualTo(INPUT);
        assertThat(request.has("store") || request.has("reasoning") || request.has("max_output_tokens")).isFalse();
        assertThat(request.path("questions").size()).isEqualTo(1);
        JsonNode question = request.path("questions").path(0);
        assertThat(question.path("type").asString()).isEqualTo("choice");
        assertThat(question.path("name").asString()).isEqualTo("medication_category");
        assertThat(question.path("instructions").asString()).contains("EMERGENCY를", "UNSAFE", "PASS", "OFF_TOPIC");
        List<String> choices = new ArrayList<>();
        for (JsonNode choice : question.path("choices")) {
            choices.add(choice.path("value").asString());
            assertThat(choice.path("description").asString()).isNotBlank();
        }
        assertThat(choices).containsExactly("PASS", "OFF_TOPIC", "UNSAFE", "EMERGENCY");
        assertThat(output.getAll()).contains("category=" + category.name()).doesNotContain(INPUT, "local-test-key");
        assertThat(output.getAll()).contains("inputTokens=unknown", "estimatedCostUsd=unknown");
    }

    @Test
    void decisionCostUsesOnlyReportedInputTokens(CapturedOutput output) {
        String response = answer("OFF_TOPIC");
        stub(200, response.substring(0, response.length() - 1)
                + ",\"usage\":{\"input_tokens\":581,\"output_tokens\":999999}}");
        assertThat(classifier("local-test-key", "decision-only", 1000).classify(INPUT))
                .isEqualTo(QuestionCategory.OFF_TOPIC);
        assertThat(output.getAll()).contains("inputTokens=581", "estimatedCostUsd=0.00005810",
                "inputUsdPerMillion=0.10").doesNotContain(INPUT, "local-test-key");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json", "null", "{}", "{\"answers\":[]}", "{\"answers\":{}}",
            "{\"answers\":[{\"type\":\"refusal\",\"name\":\"medication_category\",\"choice\":\"PASS\"}]}",
            "{\"answers\":[{\"type\":\"choice\",\"name\":\"other_question\",\"choice\":\"PASS\"}]}",
            "{\"answers\":[{\"type\":\"choice\",\"choice\":\"PASS\"}]}",
            "{\"answers\":[{\"name\":\"medication_category\",\"choice\":\"PASS\"}]}",
            "{\"answers\":[{\"type\":\"predicate\",\"name\":\"medication_category\",\"choice\":\"PASS\"}]}",
            "{\"answers\":[{\"type\":\"choice\",\"name\":\"medication_category\",\"choice\":\"pass\"}]}",
            "{\"answers\":[{\"type\":\"choice\",\"name\":\"medication_category\",\"choice\":\"UNKNOWN\"}]}",
            "{\"answers\":[{\"type\":\"choice\",\"name\":\"medication_category\"}]}",
            "{\"answers\":[{\"type\":\"choice\",\"name\":\"medication_category\",\"choice\":1}]}",
            "{\"answers\":[{\"type\":\"choice\",\"name\":\"medication_category\",\"choice\":null}]}",
            "{\"answers\":[{\"type\":\"choice\",\"name\":\"medication_category\",\"choice\":\"PASS\"},{}]}"
    })
    void malformedOrRefusedAnswersNeverBecomePassOrRetry(String body) {
        stub(200, body);
        ExternalApiException error = assertThrows(ExternalApiException.class,
                () -> classifier("local-test-key", "decision-only", 1000).classify(INPUT));
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_API_FAILED);
        assertThat(calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 503})
    void httpFailureDoesNotRetryOrExposeProviderBody(int status, CapturedOutput output) {
        stub(status, "provider-private-marker");
        BusinessException error = assertThrows(BusinessException.class,
                () -> classifier("local-test-key", "decision-only", 1000).classify(INPUT));
        assertThat(error.getErrorCode()).isIn(ErrorCode.AI_UNAVAILABLE, ErrorCode.EXTERNAL_API_FAILED);
        assertThat(error.getMessage()).doesNotContain(INPUT, "provider-private-marker", "local-test-key");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(INPUT, "provider-private-marker", "local-test-key");
    }

    @Test
    void shortTimeoutDoesNotRetryAndReleasesSharedPermit() {
        server.createContext("/decisions", exchange -> {
            if (calls.incrementAndGet() == 1) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            respond(exchange, 200, answer("PASS"));
        });
        QuestionClassifier classifier = classifier("local-test-key", "decision-only", 100);
        ExternalApiException error = assertThrows(ExternalApiException.class, () -> classifier.classify(INPUT));
        assertThat(error.isTimeout()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(classifier.classify(INPUT)).isEqualTo(QuestionCategory.PASS);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void absentKeyOrDecisionModelDoesNotFallBackToChatModel() {
        stub(200, answer("PASS"));
        assertThrows(AiUnavailableException.class, () -> classifier("", "decision-only", 1000).classify(INPUT));
        assertThrows(AiUnavailableException.class, () -> classifier("local-test-key", "", 1000).classify(INPUT));
        assertThat(calls.get()).isZero();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " \n\t "})
    void blankQuestionDoesNotCallOpenAi(String content) {
        stub(200, answer("PASS"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> classifier("local-test-key", "decision-only", 1000).classify(content));
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(calls.get()).isZero();
    }

    @Test
    void lengthLimitAppliesAfterTrimmingWithoutTruncatingQuestion() {
        stub(200, answer("PASS"));
        QuestionClassifier classifier = classifier("local-test-key", "decision-only", 1000);
        assertThrows(BusinessException.class, () -> classifier.classify("가".repeat(1001)));
        assertThat(calls.get()).isZero();
        assertThat(classifier.classify("  " + "가".repeat(1000) + "  ")).isEqualTo(QuestionCategory.PASS);
        assertThat(calls.get()).isEqualTo(1);
    }

    private QuestionClassifier classifier(String key, String decisionModel, long shortTimeout) {
        return new QuestionClassifier(new OpenAiClient(JSON, key, baseUrl,
                "chat-unused", decisionModel, "vision-unused", "embedding-unused", "high",
                2000, shortTimeout, 2000, 60000, 1, 50, new java.math.BigDecimal("0.10"), 85_000));
    }

    private void stub(int status, String body) {
        server.createContext("/decisions", exchange -> {
            calls.incrementAndGet();
            respond(exchange, status, body);
        });
    }

    private static String answer(String choice) {
        return "{\"answers\":[{\"type\":\"choice\",\"name\":\"medication_category\",\"choice\":\"" + choice + "\"}]}";
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // 시간 초과로 호출자가 연결을 닫아도 테스트 서버의 종료 처리를 수행한다.
        } finally {
            exchange.close();
        }
    }
}

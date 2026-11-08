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
import org.semiproject.pillbadeumserver.chat.service.QuestionPlan;
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

    // QA 2026-11-05: 모델이 '약 한 통을 다 드셨어'를 일반 질문으로 넘겼음.
    @Test
    void anOverdoseAlreadyTakenBecomesAnEmergencyEvenWhenTheModelPassesIt() {
        server.createContext("/decisions", exchange -> respond(exchange, 200, answer("PASS_OFFICIAL")));
        var classifier = classifier("local-test-key", "decision-only", 1000);
        var empty = QuestionClassifier.Context.empty();
        assertThat(classifier.plan("아버지가 약 한 통을 다 드셨어", empty).category()).isEqualTo(QuestionCategory.SELF_HARM);
        assertThat(classifier.plan("실수로 약 한 통을 다 드셨어", empty).category()).isEqualTo(QuestionCategory.EMERGENCY);
        assertThat(classifier.plan("수면제를 너무 많이 먹었어", empty).category()).isEqualTo(QuestionCategory.SELF_HARM);
        assertThat(classifier.plan("오늘 약 다 먹었어", empty).category()).isEqualTo(QuestionCategory.PASS);
        assertThat(classifier.plan("밥을 너무 많이 먹었어. 약 먹어도 돼?", empty).category()).isEqualTo(QuestionCategory.PASS);
    }

    // QA 2026-11-05: 모델이 '실수로 약 한 통을 다 드셨어'를 자해로 골라 109가 같이 나왔음.
    @Test
    void anAccidentalOverdoseShowsOnly119EvenWhenTheModelChoosesSelfHarm() {
        server.createContext("/decisions", exchange -> respond(exchange, 200, answer("SELF_HARM")));
        var classifier = classifier("local-test-key", "decision-only", 1000);
        var empty = QuestionClassifier.Context.empty();
        assertThat(classifier.plan("실수로 약 한 통을 다 드셨어", empty).category()).isEqualTo(QuestionCategory.EMERGENCY);
        assertThat(classifier.plan("아버지가 약 한 통을 다 드셨어", empty).category()).isEqualTo(QuestionCategory.SELF_HARM);
        assertThat(classifier.plan("내가 잘못했어. 약 한 통 먹었어", empty).category()).isEqualTo(QuestionCategory.SELF_HARM);
        assertThat(classifier.plan("죽고 싶어서 약 한 통 먹었어. 실수로 깬 거야", empty).category()).isEqualTo(QuestionCategory.SELF_HARM);
        assertThat(classifier.plan("아무도 모르고 있어. 죽기 위해 약 한 통 먹었어", empty).category()).isEqualTo(QuestionCategory.SELF_HARM);
        assertThat(classifier.plan("잘못 먹은 게 아니고 스스로 선택해서 약 한 통 먹었어", empty).category()).isEqualTo(QuestionCategory.SELF_HARM);
    }

    // QA 2026-11-08: '타이레놀 20알 먹었어' 뒤 '괜찮겠지?'에 안심시키는 답, '119 안 부르면 안돼?'에 '꼭 부를 필요는 없다',
    // 자해 말 뒤 '농담이야'에 잡담 안내가 나갔음(막은 질문은 저장하지 않아 분류가 앞 상황을 몰랐음).
    @Test
    void followUpsWithin30MinutesOfAnEmergencyGetTheSameNoticeEvenWhenTheModelPassesThem() {
        var said = new java.util.concurrent.ConcurrentLinkedQueue<JsonNode>();
        server.createContext("/decisions", exchange -> {
            calls.incrementAndGet();
            var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            var data = JSON.readTree(request.path("input").asString());
            said.add(data);
            String question = data.path("question").asString();
            String choice = question.contains("20알") ? "SELF_HARM" : question.equals("농담이야") ? "OFF_TOPIC"
                    : question.contains("일정") ? "PASS_RECORD" : "PASS_OFFICIAL";
            respond(exchange, 200, answer(choice));
        });
        var classifier = classifier("local-test-key", "decision-only", 1000);
        var mine = new QuestionClassifier.Context(List.of(), List.of(), List.of(), List.of(), 10L);
        var other = new QuestionClassifier.Context(List.of(), List.of(), List.of(), List.of(), 11L);

        var first = classifier.plan("타이레놀 20알 먹었어", mine);
        assertThat(first.category()).isEqualTo(QuestionCategory.SELF_HARM);
        assertThat(first.afterEmergency()).as("처음 막은 질문은 처음 안내").isFalse();
        for (String followUp : List.of("괜찮겠지?", "119 안 부르면 안돼?", "토할 것 같아", "먹은 건 30분 전이야", "농담이야",
                "상담 전화 번호 알려줘", "고마워")) {
            var plan = classifier.plan(followUp, mine);
            assertThat(plan.category()).as(followUp).isEqualTo(QuestionCategory.SELF_HARM);
            assertThat(plan.afterEmergency()).as(followUp).isTrue();
        }
        assertThat(said).filteredOn(data -> data.path("question").asString().equals("괜찮겠지?"))
                .singleElement().satisfies(data -> assertThat(data.path("recentEmergency").asString()).isEqualTo("SELF_HARM"));
        assertThat(said).filteredOn(data -> data.path("question").asString().equals("타이레놀 20알 먹었어"))
                .singleElement().satisfies(data -> assertThat(data.has("recentEmergency")).isFalse());

        var unrelated = classifier.plan("내 약 일정 보여줘", mine);
        assertThat(unrelated.category()).as("앞 상황과 상관없는 기록 확인은 통과").isEqualTo(QuestionCategory.PASS);
        assertThat(unrelated.afterEmergency()).isFalse();
        assertThat(classifier.plan("괜찮겠지?", other).category()).as("다른 프로필은 상관없음").isEqualTo(QuestionCategory.PASS);
        assertThat(classifier.plan("괜찮겠지?", QuestionClassifier.Context.empty()).category())
                .as("프로필을 모르면 기억하지 않음").isEqualTo(QuestionCategory.PASS);
    }

    @Test
    void anEmergencyThenSelfHarmKeeps109AndAClassifierOutageStillShowsTheNotice() {
        var next = new AtomicReference<>("EMERGENCY");
        server.createContext("/decisions", exchange -> {
            calls.incrementAndGet();
            if (next.get() == null) {
                respond(exchange, 500, "{}");
                return;
            }
            respond(exchange, 200, answer(next.get()));
        });
        var classifier = classifier("local-test-key", "decision-only", 1000);
        var mine = new QuestionClassifier.Context(List.of(), List.of(), List.of(), List.of(), 20L);
        assertThat(classifier.plan("실수로 두 알 더 먹었어", mine).category()).isEqualTo(QuestionCategory.EMERGENCY);
        next.set("PASS_OFFICIAL");
        assertThat(classifier.plan("괜찮겠지?", mine)).extracting(QuestionPlan::category, QuestionPlan::afterEmergency)
                .containsExactly(QuestionCategory.EMERGENCY, true);
        next.set("SELF_HARM");
        assertThat(classifier.plan("사실 죽고 싶었어", mine).category()).isEqualTo(QuestionCategory.SELF_HARM);
        next.set("EMERGENCY");
        assertThat(classifier.plan("숨이 차", mine).category()).as("한 번 자해였으면 109 안내를 유지").isEqualTo(QuestionCategory.SELF_HARM);
        next.set(null);
        assertThat(classifier.plan("어떡해", mine)).as("분류 장애여도 이 30분 안에서는 안내")
                .extracting(QuestionPlan::category, QuestionPlan::afterEmergency).containsExactly(QuestionCategory.SELF_HARM, true);
    }

    @Test
    void followUpNoticesDoNotAskAgainAndKeepTheirNumbers() {
        assertThat(QuestionCategory.EMERGENCY.followUpMessage()).contains("늦게 나빠질 수", "119").doesNotContain("109", "드신 시각");
        assertThat(QuestionCategory.SELF_HARM.followUpMessage()).contains("늦게 나빠질 수", "119", "109");
        assertThat(QuestionCategory.OFF_TOPIC.followUpMessage()).isEqualTo(QuestionCategory.OFF_TOPIC.noticeMessage());
        var mapper = new tools.jackson.databind.json.JsonMapper();
        var body = mapper.readTree(mapper.writeValueAsString(org.semiproject.pillbadeumserver.chat.dto.QuestionResponse
                .blocked(QuestionCategory.SELF_HARM, QuestionCategory.SELF_HARM.followUpMessage())));
        assertThat(body.path("notice").path("category").asString()).isEqualTo("EMERGENCY");
        assertThat(body.path("notice").path("message").asString()).contains("109", "늦게 나빠질 수");
    }

    @Test
    void selfHarmKeepsTheEmergencyValueInThePublicResponse() {
        var mapper = new tools.jackson.databind.json.JsonMapper();
        var body = mapper.readTree(mapper.writeValueAsString(
                org.semiproject.pillbadeumserver.chat.dto.QuestionResponse.blocked(QuestionCategory.SELF_HARM)));
        assertThat(body.path("notice").path("category").asString()).isEqualTo("EMERGENCY");
        assertThat(body.path("notice").path("message").asString()).contains("109");
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
        assertThat(QuestionCategory.EMERGENCY.noticeMessage()).contains("119", "응급실", "드신 시각").doesNotContain("109");
        assertThat(QuestionCategory.SELF_HARM.noticeMessage()).contains("119", "응급실", "109");
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
        assertThat(choices).containsExactly("PASS", "OFF_TOPIC", "UNSAFE", "EMERGENCY", "SELF_HARM");
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

    @Test
    void oneDecisionIncludesOwnedContextAndKeepsMedicalQuestionsOutOfTheRecordRoute() {
        AtomicReference<JsonNode> sent = new AtomicReference<>();
        server.createContext("/decisions", exchange -> {
            calls.incrementAndGet();
            sent.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            respond(exchange, 200, answer("PASS_RECORD"));
        });
        var plan = classifier("local-test-key", "decision-only", 1000).plan("임산부는 먹으면 안되는 약이 있어?",
                new QuestionClassifier.Context(List.of("내 약 이름은?"), List.of("197400040 합성약")));
        assertThat(plan.category()).isEqualTo(QuestionCategory.PASS);
        assertThat(plan.purpose()).isEqualTo(QuestionPlan.Purpose.COMBINED);
        assertThat(calls.get()).isEqualTo(1);
        var request = sent.get();
        var context = JSON.readTree(request.path("input").asString());
        assertThat(context.path("previousQuestions").path(0).asString()).isEqualTo("내 약 이름은?");
        assertThat(context.path("products").path(0).asString()).isEqualTo("197400040 합성약");
        assertThat(request.path("questions").size()).isEqualTo(1);
        assertThat(request.path("questions").path(0).path("choices").size()).isEqualTo(7);
    }

    @Test
    void retryClassifiesPurposeOnlyWithoutReintroducingBlockedCategories() {
        AtomicReference<JsonNode> sent = new AtomicReference<>();
        server.createContext("/decisions", exchange -> {
            calls.incrementAndGet();
            sent.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            respond(exchange, 200, answer("PASS_RECORD"));
        });
        var input = new org.semiproject.pillbadeumserver.chat.service.AnswerInput(1993, "MALE", false,
                "내 저녁 약 이름과 시각은?", List.of(), List.of(), false);
        assertThat(classifier("local-test-key", "decision-only", 1000).retryPlan(input).purpose()).isEqualTo(QuestionPlan.Purpose.RECORD);
        assertThat(calls.get()).isEqualTo(1);
        var choices = sent.get().path("questions").path(0).path("choices");
        assertThat(choices.size()).isEqualTo(3);
        assertThat(choices.toString()).doesNotContain("OFF_TOPIC", "UNSAFE", "EMERGENCY");
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

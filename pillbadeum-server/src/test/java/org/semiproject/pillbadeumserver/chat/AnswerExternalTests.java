package org.semiproject.pillbadeumserver.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.semiproject.pillbadeumserver.chat.service.AnswerGenerator;
import org.semiproject.pillbadeumserver.chat.service.AnswerInput;
import org.semiproject.pillbadeumserver.chat.service.AnswerSources;
import org.semiproject.pillbadeumserver.chat.service.ChatDeadline;
import org.semiproject.pillbadeumserver.chat.service.OfficialDrugEvidence;
import org.semiproject.pillbadeumserver.chat.service.DurLookupService;
import org.semiproject.pillbadeumserver.chat.service.DurEvaluator;
import org.semiproject.pillbadeumserver.chat.domain.AnswerSource;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 실제 RestClient를 로컬 HTTP 서버에 연결한다. 유료 API·실제 의약품 데이터·DB는 사용하지 않는다. */
@ExtendWith(OutputCaptureExtension.class)
class AnswerExternalTests {
    private static final String CODE = "199900001";
    private static final String PRIVATE = "private-evidence-sentinel";
    private final JsonMapper json = new JsonMapper();
    private final AnswerSources sources = new AnswerSources(json);
    private HttpServer server;
    private ExecutorService httpThreads;
    private SimpleAsyncTaskExecutor executor;
    private AnswerGenerator generator;
    private final AtomicReference<String> easyBody = new AtomicReference<>();
    private final AtomicReference<String> permitBody = new AtomicReference<>();
    private final AtomicReference<String> chatBody = new AtomicReference<>();
    private final AtomicInteger easyStatus = new AtomicInteger(200);
    private final AtomicInteger permitStatus = new AtomicInteger(200);
    private final AtomicInteger chatStatus = new AtomicInteger(200);
    private final AtomicInteger publicCalls = new AtomicInteger();
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final AtomicReference<JsonNode> sent = new AtomicReference<>();
    private final java.util.List<String> queries = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void prepareLocalServersAndRealClients() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpThreads = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(httpThreads);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        executor = new SimpleAsyncTaskExecutor("answer-http-test-");
        executor.setVirtualThreads(true);
        var publicData = new PublicDataClient(json, base, "private-public-key", 1000, 300, 4, 20);
        var openAi = new OpenAiClient(json, "private-ai-key", base, "chat-only", "decision-only", "vision-only", "embedding-only",
                "", 1000, 300, 500, 60000, 4, 20, new BigDecimal("0.10"), 85_000);
        generator = new AnswerGenerator(openAi, new OfficialDrugEvidence(publicData, executor, 4),
                new DurLookupService(publicData, executor, 4), new DurEvaluator(), sources, json, executor);
        easyBody.set(success(List.of(Map.of("itemSeq", CODE, "efcyQesitm", "<p>" + PRIVATE + "</p>",
                "useMethodQesitm", "synthetic official directions", "updateDe", "2026-10-17"))));
        permitBody.set(success(List.of(Map.of("ITEM_SEQ", CODE, "EE_DOC_DATA", "<DOC><![CDATA[synthetic permit facts]]></DOC>",
                "CHANGE_DATE", "20261016", "CANCEL_NAME", "정상"))));
        chatBody.set(completed("근거에 따른 안내\n@@SOURCES@@\n[{\"source\":\"fabricated\"}]"));
    }

    @AfterEach
    void close() {
        server.stop(0);
        httpThreads.shutdownNow();
        executor.close();
    }

    @Test
    void realRequestUsesResponsesOnlyAndServerSourcesReplaceModelClaims(CapturedOutput output) {
        var result = generate();
        assertThat(chatCalls.get()).isEqualTo(1);
        assertThat(publicCalls.get()).isEqualTo(2);
        assertThat(queries).anyMatch(query -> query.contains("itemSeq=" + CODE))
                .anyMatch(query -> query.contains("item_seq=" + CODE)).allMatch(query -> !query.contains("itemName"));
        assertThat(sent.get().path("model").asString()).isEqualTo("chat-only");
        assertThat(sent.get().path("reasoning").path("effort").asString()).isEqualTo("high");
        assertThat(sent.get().path("store").asBoolean()).isFalse();
        assertThat(sent.get().has("max_output_tokens")).isFalse();
        assertThat(sent.get().has("previous_response_id") || sent.get().has("tools")).isFalse();
        assertThat(sent.get().path("instructions").asString()).contains("진단·처방·용량 변경·복용 중단", "UNKNOWN", "FAILED·PARTIAL");
        assertThat(sent.get().path("input").toString()).contains(PRIVATE, "synthetic permit facts").doesNotContain("<DOC>", "<p>");
        assertThat(result.responseId()).isEqualTo("resp-fixture");
        assertThat(result.content()).doesNotContain("fabricated").contains("지금은 금기 정보를 확인하지 못했어요");
        var provenance = sources.sources(result.content());
        assertThat(provenance).hasSize(4).extracting(AnswerSource::source)
                .containsExactly(AnswerSource.Type.EASY_DRUG, AnswerSource.Type.PERMIT, AnswerSource.Type.USER_INPUT, AnswerSource.Type.DUR);
        assertThat(provenance.getFirst().updatedAt()).isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(provenance.get(1).updatedAt()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(provenance.subList(0, 3)).allMatch(AnswerSource::ok);
        assertThat(provenance.getLast().ok()).isFalse();
        assertThat(result.durNotice().lookup()).isEqualTo("FAILED");
        assertThat(result.durNotice().result()).isNull();
        assertThat(result.durNotice().checkedCount()).isZero();
        assertThat(output.getAll()).doesNotContain(PRIVATE, "private-ai-key", "private-public-key", "fabricated");
    }

    @Test
    void unscheduledRegisteredDrugStillReceivesAnEvidenceBasedAnswer() {
        var drug = new AnswerInput.Medication(CODE, "합성등록약정", "maker", "OTC", 1, null,
                null, null, List.of(), LocalDate.now(TimeZoneConfig.ZONE));
        var input = new AnswerInput(2000, "FEMALE", false, "등록약 정보가 궁금해요", List.of(drug), List.of(), false);
        var result = generator.generate(input, ChatDeadline.after(Duration.ofSeconds(10)));
        assertThat(chatCalls.get()).isEqualTo(1);
        assertThat(publicCalls.get()).isEqualTo(2);
        var background = json.readTree(sent.get().path("input").get(0).path("content").asString().split("\n", 2)[1]);
        assertThat(background.path("userSchedules").isArray()).isTrue();
        assertThat(background.path("userSchedules").size()).isZero();
        assertThat(sources.sources(result.content())).filteredOn(s -> s.source() == AnswerSource.Type.EASY_DRUG)
                .allMatch(AnswerSource::ok).hasSize(1);
    }

    @Test
    void onlyUnidentifiedDrugsNeverTriggerNameBasedLookupOrModelGuessing() {
        var input = new AnswerInput(2000, "MALE", false, "이 약 먹어도 돼요?", List.of(), List.of(), false)
                .withUnidentifiedDrugNames(List.of("직접 입력 이름"));
        var result = generator.generate(input, ChatDeadline.after(Duration.ofSeconds(10)));
        assertThat(publicCalls.get()).isZero();
        assertThat(chatCalls.get()).isZero();
        assertThat(result.content()).contains("품목코드", "확인하지 못했어요");
        assertThat(result.durNotice().lookup()).isEqualTo("FAILED");
        assertThat(result.durNotice().uncheckedDrugNames()).containsExactly("직접 입력 이름");
    }

    @Test
    void permitSupplementsAnAbsentEasyDrugRecord() {
        easyBody.set(success(List.of()));
        var result = generate();
        assertThat(chatCalls.get()).isEqualTo(1);
        assertThat(sources.sources(result.content())).extracting(AnswerSource::ok).containsExactly(false, true, true, false);
        assertThat(sent.get().path("input").toString()).contains("synthetic permit facts");
    }

    @Test
    void noOfficialRecordsProduceAFixedAnswerWithoutModelGuessing() {
        easyBody.set(success(List.of()));
        permitBody.set(success(List.of()));
        var result = generate();
        assertThat(chatCalls.get()).isZero();
        assertThat(sources.body(result.content())).contains("공식 자료에서 확인할 수 없어요");
        assertThat(sources.sources(result.content())).extracting(AnswerSource::ok).containsExactly(false, false, true, false);
    }

    @Test
    void contradictoryCountsNeverReachTheModelAsOfficialFacts() {
        easyBody.set(easyBody.get().replace("\"totalCount\":1", "\"totalCount\":0"));
        permitBody.set(permitBody.get().replace("\"totalCount\":1", "\"totalCount\":0"));
        var result = generate();
        assertThat(chatCalls.get()).isZero();
        assertThat(result.content()).doesNotContain(PRIVATE, "synthetic permit facts");
        assertThat(sources.sources(result.content())).extracting(AnswerSource::ok).containsExactly(false, false, true, false);
    }

    @Test
    void partialFailureIsVisibleAndTheRemainingRealEvidenceIsUsed() {
        easyStatus.set(400);
        var result = generate();
        assertThat(chatCalls.get()).isEqualTo(1);
        assertThat(publicCalls.get()).isEqualTo(2);
        assertThat(result.content()).contains("일부 약의 공식 정보를 지금 확인하지 못했어요");
        assertThat(sources.sources(result.content())).extracting(AnswerSource::ok).containsExactly(false, true, true, false);
    }

    @Test
    void bothPublicServicesFailAfterOneRetryAndDoNotCallTheModel() {
        easyStatus.set(503);
        permitStatus.set(503);
        var result = generate();
        assertThat(chatCalls.get()).isZero();
        assertThat(publicCalls.get()).isEqualTo(4);
        assertThat(sources.body(result.content())).contains("지금은 공식 정보를 확인하지 못해 답할 수 없어요");
    }

    @Test
    void similarlyNamedWrongCodesAndMissingSuccessHeadersAreNotSources() {
        easyBody.set(success(List.of(Map.of("itemSeq", "other-code", "efcyQesitm", "wrong drug"))));
        permitBody.set(json.writeValueAsString(Map.of("body", Map.of("items", List.of(Map.of("ITEM_SEQ", CODE, "EE_DOC_DATA", "unverified")), "totalCount", 1))));
        var result = generate();
        assertThat(chatCalls.get()).isZero();
        assertThat(sources.sources(result.content())).extracting(AnswerSource::ok).containsExactly(false, false, true, false);
    }

    @Test
    void excessiveClinicalDocumentsAreUnavailableRatherThanCutMidSentence() {
        easyBody.set(success(List.of(Map.of("itemSeq", CODE, "efcyQesitm", "x".repeat(33_000)))));
        permitBody.set(success(List.of()));
        var result = generate();
        assertThat(chatCalls.get()).isZero();
        assertThat(sources.body(result.content())).contains("공식 정보를 확인하지 못해");
    }

    @Test
    void verifiedCancellationIsReportedButDoesNotBecomeAStopMedicationInstruction() {
        permitBody.set(success(List.of(Map.of("ITEM_SEQ", CODE, "CANCEL_DATE", "20261001", "CANCEL_NAME", "취하"))));
        var result = generate();
        assertThat(sources.sources(result.content()).get(1).cancelled()).isTrue();
        assertThat(result.content()).contains("허가 취하·취소가 확인된 약", "의사나 약사에게 확인해 주세요");
    }

    @Test
    void noRegisteredMedicationUsesOnlyAFixedRegistrationGuide() {
        var result = generator.generate(new AnswerInput(2000, "FEMALE", null, "목록에 없는 약 질문",
                List.of(), List.of(), true), ChatDeadline.after(Duration.ofSeconds(5)));
        assertThat(publicCalls.get()).isZero();
        assertThat(chatCalls.get()).isZero();
        assertThat(sources.sources(result.content())).isEmpty();
        assertThat(result.content()).contains("약을 등록하면", "지금 확인할 수 없어요");
    }

    @ParameterizedTest
    @ValueSource(strings = {"incomplete", "failed", "in_progress", "queued", "cancelled", ""})
    void aNonCompletedResponseCannotBecomeAStoredAnswer(String status) {
        chatBody.set(json.writeValueAsString(Map.of("status", status, "output", List.of(Map.of("type", "message",
                "content", List.of(Map.of("type", "output_text", "text", "partial response")))))));
        assertThrows(BusinessException.class, this::generate);
        assertThat(chatCalls.get()).isEqualTo(1);
    }

    @Test
    void refusalWhitespaceAndInventedSourcesOnlyAreGenerationFailures() {
        for (String response : List.of(
                json.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of("type", "message", "content",
                        List.of(Map.of("type", "output_text", "text", "partial"), Map.of("type", "refusal", "refusal", "private refusal")))))),
                completed("   "), completed("@@SOURCES@@\n[]"),
                json.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of("type", "message", "content",
                        List.of(Map.of("type", "output_text", "text", 123)))))))) {
            chatBody.set(response);
            assertThrows(BusinessException.class, this::generate);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 503})
    void generationHttpErrorsAreNotRetried(int status) {
        chatStatus.set(status);
        assertThrows(BusinessException.class, this::generate);
        assertThat(chatCalls.get()).isEqualTo(1);
    }

    private org.semiproject.pillbadeumserver.chat.service.GeneratedAnswer generate() {
        var today = LocalDate.now(TimeZoneConfig.ZONE);
        var medication = new AnswerInput.Medication(CODE, "합성등록약정", "maker", "OTC", 1, "사용자 입력",
                today, today.plusDays(1), List.of(LocalTime.of(7, 0)), today);
        return generator.generate(new AnswerInput(2000, "FEMALE", null, "합성등록약정에 관한 질문",
                List.of(medication), List.of(), false), ChatDeadline.after(Duration.ofSeconds(10)));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/responses")) {
            chatCalls.incrementAndGet();
            sent.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            respond(exchange, chatStatus.get(), chatBody.get());
        } else if (path.equals(OfficialDrugEvidence.EASY_PATH) || path.equals(OfficialDrugEvidence.PERMIT_PATH)) {
            publicCalls.incrementAndGet();
            queries.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            boolean easy = path.equals(OfficialDrugEvidence.EASY_PATH);
            respond(exchange, easy ? easyStatus.get() : permitStatus.get(), easy ? easyBody.get() : permitBody.get());
        } else respond(exchange, 404, "{}");
    }

    private String success(List<Map<String, String>> items) {
        return json.writeValueAsString(Map.of("header", Map.of("resultCode", "00"), "body", Map.of("totalCount", items.size(), "items", items)));
    }

    private String completed(String text) {
        return json.writeValueAsString(Map.of("id", "resp-fixture", "status", "completed", "output", List.of(Map.of(
                "type", "message", "content", List.of(Map.of("type", "output_text", "text", text))))));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}

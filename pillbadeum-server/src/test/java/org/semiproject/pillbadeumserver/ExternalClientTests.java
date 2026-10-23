package org.semiproject.pillbadeumserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.semiproject.pillbadeumserver.common.ai.AiMessage;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.exception.AiUnavailableException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.ExternalApiCaller;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.service.PillDrugIdentificationService;
import org.semiproject.pillbadeumserver.image.service.PillCatalog;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 실제 HTTP 요청을 로컬 테스트 서버로만 보내며 API 키/외부 서비스는 사용하지 않는다. */
@ExtendWith(OutputCaptureExtension.class)
class ExternalClientTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> SCHEMA = Map.of("type", "object");
    private static final List<AiMessage> MESSAGES = List.of(AiMessage.user("테스트 질문"));
    private HttpServer server;
    private ExecutorService serverExecutor;
    private String baseUrl;
    @TempDir Path catalogDirectory;
    private final List<PillCatalog> catalogs = new java.util.ArrayList<>();

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
        catalogs.forEach(PillCatalog::close);
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void publicDataRetriesOnceAndEncodesParameterValues() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/drugs", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 503, "temporary");
            } else {
                respond(exchange, 200, """
                        {"response":{"header":{"resultCode":"00"},"body":{
                        "totalCount":1,"items":{"item":{"itemSeq":"123"}}}}}
                        """);
            }
        });
        var result = publicData().get("/drugs", Map.of("itemName", "약 +"));
        assertThat(calls.get()).isEqualTo(2);
        assertThat(query.get()).contains("serviceKey=a%2Bb%2Fc%3D", "itemName=%EC%95%BD%20%2B");
        assertThat(PublicDataClient.totalCount(result)).isEqualTo(1);
        assertThat(PublicDataClient.items(result)).hasSize(1);
        assertThat(PublicDataClient.items(result).getFirst().path("itemSeq").asString()).isEqualTo("123");
    }

    @Test
    void publicDataDoesNotRetryHttpClientErrors() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/invalid", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 400, "invalid parameter");
        });
        assertThrows(ExternalApiException.class, () -> publicData().get("/invalid", Map.of()));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void publicDataDoesNotRetryBusinessFailureOrExposeMessage(CapturedOutput output) {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/invalid-key", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, """
                    {"header":{"resultCode":"30","resultMsg":"secret-key-and-health-data"}}
                    """);
        });
        var error = assertThrows(ExternalApiException.class,
                () -> publicData().get("/invalid-key", Map.of()));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(error.getMessage()).doesNotContain("secret-key-and-health-data");
        assertThat(output.getAll()).doesNotContain("secret-key-and-health-data");
    }

    @Test
    void publicDataStopsAfterSecondTransientFailureAndDoesNotLogBody(CapturedOutput output) {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/unavailable", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 503, "secret-response-body");
        });
        assertThrows(ExternalApiException.class, () -> publicData().get("/unavailable", Map.of()));
        assertThat(calls.get()).isEqualTo(2);
        assertThat(output.getAll()).doesNotContain("secret-response-body");
    }

    @Test
    void embeddingRetriesTransientFailureAndExtractsVector() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/embeddings", exchange -> {
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 429, "slow down");
            } else {
                respond(exchange, 200, "{\"data\":[{\"embedding\":[0.25,-0.5]}]}");
            }
        });
        assertThat(openAi(1000, 1000, 1).embed("테스트")).containsExactly(0.25f, -0.5f);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void embeddingLogsPromptTokensWithoutInputOrKey(CapturedOutput output) {
        var request = new AtomicReference<tools.jackson.databind.JsonNode>();
        server.createContext("/embeddings", exchange -> {
            request.set(JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respond(exchange, 200, "{\"data\":[{\"embedding\":[0.25,-0.5]}],\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}");
        });
        assertThat(openAi(1000,1000,1).embed("private-embedding-input")).containsExactly(0.25f,-0.5f);
        assertThat(request.get().path("encoding_format").asString()).isEqualTo("float");
        assertThat(output.getAll()).contains("api=embeddings", "inputTokens=7", "elapsedMs=")
                .doesNotContain("private-embedding-input", "local-test-key");
    }

    @Test
    void overflowingEmbeddingNumberDoesNotBecomeAnInfiniteVectorOrRetry() {
        var calls = new AtomicInteger();
        server.createContext("/embeddings", exchange -> {
            calls.incrementAndGet(); respond(exchange, 200, "{\"data\":[{\"embedding\":[1e39]}]}");
        });
        assertThrows(ExternalApiException.class, () -> openAi(1000,1000,1).embed("fixture"));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void answerIsNotRetriedAndReleasesPermitAfterFailure() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/responses", exchange -> {
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 503, "secret-openai-response");
            } else {
                respond(exchange, 200, textResponse("답변"));
            }
        });
        OpenAiClient client = openAi(1000, 1000, 1);
        assertThrows(ExternalApiException.class, () -> client.createText("규칙", MESSAGES, 100));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(client.createText("규칙", MESSAGES, 100)).isEqualTo("답변");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void classificationUsesShortTimeoutButVisionUsesLongTimeout() {
        server.createContext("/responses", exchange -> {
            try {
                Thread.sleep(250);
                respond(exchange, 200, textResponse("{\"category\":\"MEDICATION\"}"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        OpenAiClient client = openAi(50, 2000, 1);
        var timeout = assertThrows(ExternalApiException.class,
                () -> client.createJson("규칙", MESSAGES, 100, "category", SCHEMA));
        assertThat(timeout.isTimeout()).isTrue();
        var result = client.createJsonFromImages("규칙", "사진 식별", List.of("data:image/png;base64,AA=="),
                100, "category", SCHEMA);
        assertThat(result.path("category").asString()).isEqualTo("MEDICATION");
    }

    @Test
    void concurrentOpenAiCallsWaitOnlyWithinPermitTimeout() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        server.createContext("/responses", exchange -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                finish.await(5, TimeUnit.SECONDS);
                respond(exchange, 200, textResponse("답변"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        OpenAiClient client = openAi(1000, 2000, 1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> client.createText("규칙", MESSAGES, 100));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThrows(ExternalApiException.class, () -> client.createText("규칙", MESSAGES, 100));
                assertThat(calls.get()).isEqualTo(1);
            } finally {
                finish.countDown();
            }
            assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo("답변");
        }
    }

    @Test
    void tenSecondPermitBudgetLetsHealthyCallsFinishAfterTheOldOneSecondCutoff() throws Exception {
        for (boolean ai : List.of(true, false)) {
            String path = ai ? "/responses" : "/queued-public";
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1);
            server.createContext(path, exchange -> {
                exchange.getRequestBody().readAllBytes();
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    try { Thread.sleep(1500); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                respond(exchange, 200, ai ? textResponse("답변") : "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":0,\"items\":[]}}}");
            });
            var aiClient = new OpenAiClient(JSON, "synthetic-key", baseUrl, "chat", "decision", "vision", "embedding", "",
                    2000, 3000, 3000, 60000, 1, 10000, new java.math.BigDecimal("0.10"), 85000);
            var publicClient = new PublicDataClient(JSON, baseUrl, "synthetic-key", 2000, 3000, 1, 10000);
            java.util.concurrent.Callable<Object> request = () -> ai
                    ? aiClient.createText("규칙", MESSAGES, 100) : publicClient.get(path, Map.of());
            try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = callers.submit(request);
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(request.call()).isNotNull();
                assertThat(first.get(2, TimeUnit.SECONDS)).isNotNull();
                assertThat(calls.get()).isEqualTo(2);
            }
        }
    }

    @Test
    void interruptedPermitWaitPreservesInterruptAndDoesNotReleaseUnownedPermit() {
        Semaphore blocked = new Semaphore(0);
        Thread.currentThread().interrupt();
        try {
            assertThrows(ExternalApiException.class,
                    () -> ExternalApiCaller.withPermit("test", blocked, 20, () -> "unexpected"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(blocked.availablePermits()).isZero();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void malformedEmbeddingDoesNotSucceedAsEmptyVectorOrRetry() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/embeddings", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, "{\"data\":[]}");
        });
        assertThrows(ExternalApiException.class, () -> openAi(1000, 1000, 1).embed("테스트"));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void absentAiKeyDoesNotCallExternalService() {
        OpenAiClient client = new OpenAiClient(JSON, "", baseUrl, "chat", "decision", "vision", "embedding", "",
                2000, 1000, 1000, 60000, 1, 20, new java.math.BigDecimal("0.10"), 85_000);
        assertThat(client.isEnabled()).isFalse();
        assertThrows(AiUnavailableException.class, () -> client.createText("규칙", MESSAGES, 100));
    }

    @Test
    void visionEffortIsIndependentFromChatAndKeepsStructuredOutput() {
        var requests = new CopyOnWriteArrayList<tools.jackson.databind.JsonNode>();
        server.createContext("/responses", exchange -> {
            requests.add(JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respond(exchange, 200, textResponse("{}"));
        });
        var client = new OpenAiClient(JSON, "local-test-key", baseUrl, "chat", "decision", "pill-model", "embedding", "high",
                2000, 1000, 1000, 60000, 1, 20, new java.math.BigDecimal("0.10"), 85_000);
        ReflectionTestUtils.setField(client, "prescriptionModel", "gpt-6-luna");
        ReflectionTestUtils.setField(client, "prescriptionReasoningEffort", "max");
        client.createJsonFromImages("규칙", "사진", List.of("data:image/png;base64,AA=="), 1600, "photo_pill_clues", SCHEMA);
        for (String schema : List.of("photo_prescription_names", "prescription_medicine_names"))
            client.createJsonFromImages("규칙", "사진", List.of("data:image/png;base64,AA=="), 1600, schema, SCHEMA);
        client.createText("규칙", MESSAGES, 100);
        assertThat(requests.getFirst().path("model").asString()).isEqualTo("pill-model");
        assertThat(requests.getFirst().path("reasoning").path("effort").asString()).isEqualTo("high");
        assertThat(requests.getFirst().has("max_output_tokens")).isFalse();
        assertThat(requests.getFirst().path("text").path("format").path("strict").asBoolean()).isTrue();
        for (var request : requests.subList(1, 3)) {
            assertThat(request.path("model").asString()).isEqualTo("gpt-6-luna");
            assertThat(request.path("reasoning").path("effort").asString()).isEqualTo("max");
            assertThat(request.has("max_output_tokens")).isFalse();
        }
        assertThat(requests.getLast().path("model").asString()).isEqualTo("chat");
        assertThat(requests.getLast().path("reasoning").path("effort").asString()).isEqualTo("high");
        assertThat(requests.getLast().has("max_output_tokens")).isFalse();
    }

    @Test
    void photoBudgetsAreIndependentOfShorterChatTimeout() {
        server.createContext("/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try { Thread.sleep(350); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            try { respond(exchange, 200, textResponse("{}")); }
            catch (IOException ignored) { exchange.close(); } // 짧은 경로는 이미 시간 초과로 연결을 닫음.
        });
        var client = new OpenAiClient(JSON, "local-test-key", baseUrl, "chat", "decision", "vision", "embedding", "",
                2000, 100, 100, 2000, 1, 20, new java.math.BigDecimal("0.10"), 85_000);
        for (String schema : List.of("photo_prescription_names", "prescription_medicine_names"))
            assertThat(client.createJsonFromImages("규칙", "사진", List.of("data:image/png;base64,AA=="), 100, schema, SCHEMA).isObject()).isTrue();
        assertThat(client.createJsonFromImages("규칙", "사진", List.of("data:image/png;base64,AA=="),
                100, "photo_pill_clues", SCHEMA).isObject()).isTrue();
        assertThat(assertThrows(ExternalApiException.class, () -> client.createText("규칙", MESSAGES, 100)).isTimeout()).isTrue();
    }

    @Test
    void imageReadTimeoutIsNotAutomaticallyRetried() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/responses", exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            try { Thread.sleep(1500); respond(exchange, 200, textResponse("{}")); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (IOException ignored) { exchange.close(); }
        });
        var client = new OpenAiClient(JSON, "synthetic-key", baseUrl, "chat", "decision", "vision", "embedding", "",
                2000, 100, 100, 150, 4, 100, new java.math.BigDecimal("0.10"), 150);
        assertThat(assertThrows(ExternalApiException.class, () -> client.createJsonFromImages("규칙", "사진",
                List.of("data:image/png;base64,AA=="), 1600, "photo_pill_clues", SCHEMA)).isTimeout()).isTrue();
        Thread.sleep(650);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void imageGatewayTimeoutDoesNotRepeat() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/responses", exchange -> {
            int n = requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            respond(exchange, n == 1 ? 504 : 200, n == 1 ? "{}" : textResponse("{}"));
        });
        var client = openAi(1000, 1000, 4);
        assertThrows(ExternalApiException.class, () -> client.createJsonFromImages("규칙", "사진", List.of(), 1600, "photo_pill_clues", SCHEMA));
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void imageImmediateServerFailureStillRetriesOnce() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/responses", exchange -> {
            int n = requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            respond(exchange, n == 1 ? 500 : 200, n == 1 ? "{}" : textResponse("{}"));
        });
        assertThat(openAi(1000, 1000, 4).createJsonFromImages("규칙", "사진", List.of(), 1600,
                "photo_pill_clues", SCHEMA).isObject()).isTrue();
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void unconfiguredGenerationModelFailsBeforeNetworkButDoesNotDisableEmbedding() {
        AtomicInteger generations = new AtomicInteger();
        server.createContext("/responses", exchange -> {
            generations.incrementAndGet();
            respond(exchange, 200, textResponse("unexpected"));
        });
        server.createContext("/embeddings", exchange ->
                respond(exchange, 200, "{\"data\":[{\"embedding\":[0.5]}]}"));
        OpenAiClient client = new OpenAiClient(JSON, "local-test-key", baseUrl, "", "", "", "embedding", "",
                2000, 1000, 1000, 60000, 1, 20, new java.math.BigDecimal("0.10"), 85_000);
        assertThat(client.isEnabled()).isTrue(); // 키 설정 여부와 모델 설정 여부는 다르다.
        assertThrows(AiUnavailableException.class, () -> client.createText("규칙", MESSAGES, 100));
        assertThrows(AiUnavailableException.class, () -> client.createJsonFromImages("규칙", "사진",
                List.of("data:image/png;base64,AA=="), 100, "category", SCHEMA));
        assertThat(generations.get()).isZero();
        assertThat(client.embed("테스트")).containsExactly(0.5f);
    }

    @Test
    void pillIdentificationUsesKeyFromSpringConfigurationWithoutOsEnvironmentVariable() {
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/1471000/MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    {"response":{"header":{"resultCode":"00"},"body":{"totalCount":0,"items":[]}}}
                    """);
        });
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, () -> JSON)
                .withUserConfiguration(PillDrugIdentificationService.class, PublicDataClient.class, PillCatalog.class)
                .withPropertyValues("PUBLIC_DATA_SERVICE_KEY=local+test/key=",
                        "public-data.service-key=${PUBLIC_DATA_SERVICE_KEY:}",
                        "public-data.base-url=" + baseUrl,
                        "public-data.connect-timeout-ms=2000", "public-data.read-timeout-ms=1000",
                        "pill-identification.catalog-file=" + catalogDirectory.resolve("configured.json"),
                        "pill-identification.catalog-refresh-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(PillCatalog.class).refresh();
                    var service = context.getBean(PillDrugIdentificationService.class);
                    assertThat(service).isNotNull();
                    assertThat(context.getBean(PillCatalog.class).rows()).isEmpty();
                    assertThat(query.get()).contains("serviceKey=local%2Btest%2Fkey%3D");
                });
    }

    @Test
    void legacyPillCandidatesReadOfficialSpecialtyFieldFromCatalog() {
        server.createContext("/1471000/MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03", exchange ->
                respond(exchange, 200, """
                        {"response":{"header":{"resultCode":"00"},"body":{"totalCount":1,"items":[
                        {"ITEM_SEQ":"900001","ITEM_NAME":"합성약","ENTP_NAME":"합성제약","ETC_OTC_NAME":"일반의약품","PRINT_FRONT":"AB","DRUG_SHAPE":"원형","COLOR_CLASS1":"흰색"}]}}}
                        """));
        server.createContext("/1471000/DrugPrdtPrmsnInfoService08/getDrugPrdtPrmsnInq08", exchange ->
                respond(exchange, 200, """
                        {"response":{"header":{"resultCode":"00"},"body":{"items":[
                        {"ITEM_SEQ":"900001","SPCLTY_PBLC":"일반의약품"}]}}}
                        """));
        server.createContext("/1471000/DrbEasyDrugInfoService/getDrbEasyDrugList", exchange ->
                respond(exchange, 200, """
                        {"response":{"header":{"resultCode":"00"},"body":{"items":[]}}}
                        """));

        var candidates = identificationService().identify(new PillIdentification(
                "합성약", null, "AB", null, "원형", "흰색", null, null, null));

        assertThat(candidates).singleElement().satisfies(candidate ->
                assertThat(candidate.etcOtcName()).isEqualTo("일반의약품"));
    }

    @Test
    void pillIdentificationRejectsOcrSubstitutionWithoutQueryingUnsupportedFields() {
        List<String> queries = new CopyOnWriteArrayList<>();
        server.createContext("/1471000/MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    {"response":{"header":{"resultCode":"00"},"body":{"totalCount":2,"items":[
                    {"ITEM_SEQ":"100001","ITEM_NAME":"TEST","ENTP_NAME":"OTHER","PRINT_FRONT":"A2","PRINT_BACK":"X9","DRUG_SHAPE":"ROUND","COLOR_CLASS1":"WHITE","COLOR_CLASS2":"BLUE","LINE_FRONT":"-","LINE_BACK":"-"},
                    {"ITEM_SEQ":"100002","ITEM_NAME":"TEST","ENTP_NAME":"MAKER","PRINT_FRONT":"AO","PRINT_BACK":"B1","DRUG_SHAPE":"ROUND","COLOR_CLASS1":"WHITE","COLOR_CLASS2":"BLUE","LINE_FRONT":"+","LINE_BACK":"-"}
                    ]}}}
                    """);
        });
        server.createContext("/1471000/DrugPrdtPrmsnInfoService08/getDrugPrdtPrmsnInq08", exchange ->
                respond(exchange, 200, """
                        {"response":{"header":{"resultCode":"00"},"body":{"items":[]}}}
                        """));
        server.createContext("/1471000/DrbEasyDrugInfoService/getDrbEasyDrugList", exchange ->
                respond(exchange, 200, """
                        {"response":{"header":{"resultCode":"00"},"body":{"items":[]}}}
                        """));

        var candidates = identificationService().identify(new PillIdentification(
                "TEST", "MAKER", "A0", "B1", "ROUND", "WHITE", "BLUE", "+", "-"));

        assertThat(candidates).isEmpty();
        // 낱알식별 목록 API는 식별필드 검색을 지원하지 않으므로 전체 페이지를 가져와 로컬에서 조건을 비교한다.
        assertThat(queries).allSatisfy(query -> assertThat(query)
                .doesNotContain("entp_name=").doesNotContain("drug_shape=").doesNotContain("line_front="));
        assertThat(queries).singleElement().satisfies(query -> assertThat(query).contains("pageNo=1").contains("numOfRows=100"));
    }

    @Test
    void pillIdentificationFindsIndenolFromImprintsWhenNameAndMakerAreMissing() {
        server.createContext("/1471000/MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03", exchange ->
                respond(exchange, 200, """
                        {"response":{"header":{"resultCode":"00"},"body":{"numOfRows":100,"totalCount":5,"items":[
                        {"ITEM_SEQ":"197400040","ITEM_NAME":"인데놀정10mg(프로프라놀롤염산염)","ENTP_NAME":"동광제약(주)","ETC_OTC_NAME":"전문의약품","PRINT_FRONT":"DK","PRINT_BACK":"10","DRUG_SHAPE":"원형","COLOR_CLASS1":"하양"},
                        {"ITEM_SEQ":"900001","ITEM_NAME":"다른약1","PRINT_FRONT":"DK","PRINT_BACK":"20","DRUG_SHAPE":"타원형","COLOR_CLASS1":"노랑"},
                        {"ITEM_SEQ":"900002","ITEM_NAME":"다른약2","PRINT_FRONT":"XY","PRINT_BACK":"10","DRUG_SHAPE":"장방형","COLOR_CLASS1":"분홍"},
                        {"ITEM_SEQ":"900003","ITEM_NAME":"다른약3","PRINT_FRONT":"DK","PRINT_BACK":"1","DRUG_SHAPE":"타원형","COLOR_CLASS1":"노랑"},
                        {"ITEM_SEQ":"900004","ITEM_NAME":"다른약4","PRINT_FRONT":"M","PRINT_BACK":"10","DRUG_SHAPE":"장방형","COLOR_CLASS1":"분홍"}
                        ]}}}
                        """));

        var candidates = identificationService().identify(new PillIdentification(
                "null", "null", "DK", "10", "원형", "흰색", "null", "null", "null"));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().itemName()).isEqualTo("인데놀정10mg(프로프라놀롤염산염)");
        assertThat(candidates.getFirst().similarityScore()).isNull();
    }

    @Test
    void pillIdentificationOnlyUsesPageSizeAcceptedByTheLiveApi() {
        List<String> requests = new CopyOnWriteArrayList<>();
        server.createContext("/1471000/MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            requests.add(query);
            if (query.contains("numOfRows=999")) {
                respond(exchange, 200, """
                        {"response":{"header":{"resultCode":"10"},"body":{}}}
                        """);
            } else {
                respond(exchange, 200, """
                        {"response":{"header":{"resultCode":"00"},"body":{"numOfRows":100,"totalCount":1,"items":[
                        {"ITEM_SEQ":"10001","ITEM_NAME":"테스트약","ENTP_NAME":"합성제약","ETC_OTC_NAME":"일반의약품","PRINT_FRONT":"DK","DRUG_SHAPE":"원형","COLOR_CLASS1":"흰색"}]}}}
                        """);
            }
        });

        var candidates = identificationService().identify(new PillIdentification(
                null, null, "DK", null, "원형", "흰색", null, null, null));

        assertThat(candidates).singleElement().satisfies(candidate ->
                assertThat(candidate.printFront()).isEqualTo("DK"));
        assertThat(requests).allSatisfy(query -> assertThat(query).doesNotContain("numOfRows=999"));
        assertThat(requests).anySatisfy(query -> assertThat(query).contains("numOfRows=100"));
    }

    private PublicDataClient publicData() {
        return new PublicDataClient(JSON, baseUrl, "a+b/c=", 2000, 1000, 4, 20);
    }

    private PillDrugIdentificationService identificationService() {
        var client = publicData();
        var catalog = new PillCatalog(client, JSON, catalogDirectory.resolve(java.util.UUID.randomUUID() + ".json").toString(), false, 4);
        catalogs.add(catalog);
        catalog.refresh();
        return new PillDrugIdentificationService(client, catalog);
    }

    private OpenAiClient openAi(long shortTimeout, long longTimeout, int maxConcurrentCalls) {
        return new OpenAiClient(JSON, "local-test-key", baseUrl, "chat", "decision", "vision", "embedding", "",
                2000, shortTimeout, longTimeout, 60000, maxConcurrentCalls, 20, new java.math.BigDecimal("0.10"), 85_000);
    }

    private static String textResponse(String text) {
        return JSON.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of(
                "type", "message", "content", List.of(Map.of("type", "output_text", "text", text))))));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

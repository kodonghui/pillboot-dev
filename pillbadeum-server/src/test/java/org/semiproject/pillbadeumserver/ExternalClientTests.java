package org.semiproject.pillbadeumserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.semiproject.pillbadeumserver.common.ai.AiMessage;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.exception.AiUnavailableException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.ExternalApiCaller;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.service.PillDrugIdentificationService;
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
                2000, 1000, 1000, 1, 20, new java.math.BigDecimal("0.10"));
        assertThat(client.isEnabled()).isFalse();
        assertThrows(AiUnavailableException.class, () -> client.createText("규칙", MESSAGES, 100));
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
                2000, 1000, 1000, 1, 20, new java.math.BigDecimal("0.10"));
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
                    {"response":{"header":{"resultCode":"00"},"body":{"items":[]}}}
                    """);
        });
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, () -> JSON)
                .withUserConfiguration(PillDrugIdentificationService.class, PublicDataClient.class)
                .withPropertyValues("PUBLIC_DATA_SERVICE_KEY=local+test/key=",
                        "public-data.service-key=${PUBLIC_DATA_SERVICE_KEY:}",
                        "public-data.base-url=" + baseUrl,
                        "public-data.connect-timeout-ms=2000", "public-data.read-timeout-ms=1000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var service = context.getBean(PillDrugIdentificationService.class);
                    assertThat(service.identify(new PillIdentification(
                            "테스트약", null, null, null, null, null, null, null, null))).isEmpty();
                    assertThat(query.get()).contains("serviceKey=local%2Btest%2Fkey%3D");
                });
    }

    private PublicDataClient publicData() {
        return new PublicDataClient(JSON, baseUrl, "a+b/c=", 2000, 1000, 4, 20);
    }

    private OpenAiClient openAi(long shortTimeout, long longTimeout, int maxConcurrentCalls) {
        return new OpenAiClient(JSON, "local-test-key", baseUrl, "chat", "decision", "vision", "embedding", "",
                2000, shortTimeout, longTimeout, maxConcurrentCalls, 20, new java.math.BigDecimal("0.10"));
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

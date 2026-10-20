package org.semiproject.pillbadeumserver.chat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semiproject.pillbadeumserver.chat.service.OfficialDrugEvidence;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 앱의 RestClient·근거 수집기를 실제 로컬 HTTP로 검사한다. 외부 API·DB·모델은 사용하지 않는다. */
class OfficialEvidenceCliTests {
    private static final String CODE = "199900001";
    private static final String KEY = "synthetic+/=key";
    private static final String FACT = "synthetic-fact-must-not-be-logged";
    private final JsonMapper json = new JsonMapper();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger easyStatus = new AtomicInteger(200);
    private HttpServer server;
    private String easy;
    private String permit;
    @TempDir Path temporary;

    @BeforeEach
    void start() throws Exception {
        easy = success(1, List.of(Map.of("itemSeq", CODE, "efcyQesitm", "<p>" + FACT + "</p>")));
        permit = success(1, List.of(Map.of("ITEM_SEQ", CODE, "UD_DOC_DATA", "<DOC><![CDATA[" + FACT + "]]></DOC>")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            boolean isEasy = exchange.getRequestURI().getPath().equals(OfficialDrugEvidence.EASY_PATH);
            byte[] bytes = (isEasy ? easy : permit).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(isEasy ? easyStatus.get() : 200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    @Test
    void usesExactCodeFiltersAndWritesOnlySafeMetrics() throws Exception {
        var run = run(CODE, KEY);
        assertThat(run.exit()).isZero();
        assertThat(run.report().path("result").asString()).isEqualTo("READY");
        assertThat(requests).hasSize(2).anySatisfy(uri -> {
            assertThat(uri).startsWith(OfficialDrugEvidence.EASY_PATH + "?").contains("itemSeq=" + CODE);
        }).anySatisfy(uri -> {
            assertThat(uri).startsWith(OfficialDrugEvidence.PERMIT_PATH + "?").contains("item_seq=" + CODE);
        }).allSatisfy(uri -> {
            assertThat(URLDecoder.decode(uri, StandardCharsets.UTF_8)).contains("serviceKey=" + KEY);
            assertThat(uri).doesNotContain("itemName", "item_name");
        });
        for (var source : run.report().path("sources")) {
            assertThat(source.path("result").asString()).isEqualTo("FOUND");
            assertThat(source.path("exactItemMatched").asBoolean()).isTrue();
            assertThat(source.path("factCharacters").asInt()).isEqualTo(FACT.length());
            assertThat(source.path("factFields").asInt()).isEqualTo(1);
            assertThat(source.path("elapsedMs").asLong()).isGreaterThanOrEqualTo(0);
        }
        assertThat(run.text()).doesNotContain(KEY, FACT, "<p>", "http://", "serviceKey");
        assertThat(Files.readString(temporary.resolve("probe.json")).strip()).isEqualTo(run.text().strip());
    }

    @Test
    void permitEvidenceCanSupplementANormalEmptyEasyRecord() throws Exception {
        easy = success(0, List.of());
        var run = run(CODE, KEY);
        assertThat(run.exit()).isZero();
        assertThat(run.report().path("result").asString()).isEqualTo("READY");
        assertThat(run.report().path("sources").get(0).path("result").asString()).isEqualTo("NOT_FOUND");
        assertThat(run.report().path("sources").get(1).path("result").asString()).isEqualTo("FOUND");
    }

    @Test
    void reportRetainsTheSourceDatesAndVerifiedPermitCancellation() throws Exception {
        easy = success(1, List.of(Map.of("itemSeq", CODE, "efcyQesitm", FACT, "updateDe", "2026-10-01")));
        permit = success(1, List.of(Map.of("ITEM_SEQ", CODE, "UD_DOC_DATA", FACT,
                "CHANGE_DATE", "20260930", "CANCEL_NAME", "취하")));
        var run = run(CODE, KEY);
        assertThat(run.exit()).isZero();
        var sources = run.report().path("sources");
        assertThat(sources.path(0).path("updatedAt").asString()).isEqualTo("2026-10-01");
        assertThat(sources.path(0).path("cancelled").asBoolean()).isFalse();
        assertThat(sources.path(1).path("updatedAt").asString()).isEqualTo("2026-09-30");
        assertThat(sources.path(1).path("cancelled").asBoolean()).isTrue();
        assertThat(run.text()).doesNotContain(KEY, FACT);
    }

    @Test
    void successfulZeroResultsAreNotReportedAsAProviderFailure() throws Exception {
        easy = permit = success(0, List.of());
        var run = run(CODE, KEY);
        assertThat(run.exit()).isZero();
        assertThat(run.report().path("result").asString()).isEqualTo("NO_INFORMATION");
        for (var source : run.report().path("sources")) {
            assertThat(source.path("result").asString()).isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void httpErrorIsPartialFailureAndDoesNotDiscloseItsBody() throws Exception {
        easyStatus.set(400);
        easy = "INVALID_REQUEST_PARAMETER_ERROR " + KEY + " " + FACT;
        var run = run(CODE, KEY);
        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.report().path("result").asString()).isEqualTo("PARTIAL");
        var source = run.report().path("sources").get(0);
        assertThat(source.path("result").asString()).isEqualTo("FAILED");
        assertThat(source.path("httpStatus").asInt()).isEqualTo(400);
        assertThat(source.path("providerCode").asString()).isEqualTo("INVALID_REQUEST_PARAMETER_ERROR");
        assertThat(run.text()).doesNotContain(KEY, FACT, "serviceKey", "http://");
        assertThat(requests).hasSize(2);
    }

    @Test
    void mismatchedItemIsRejectedEvenWhenTheProviderSaysSuccess() throws Exception {
        easy = success(1, List.of(Map.of("itemSeq", "199900002", "efcyQesitm", FACT)));
        var run = run(CODE, KEY);
        assertThat(run.exit()).isEqualTo(1);
        var source = run.report().path("sources").get(0);
        assertThat(source.path("result").asString()).isEqualTo("FAILED");
        assertThat(source.path("exactItemMatched").asBoolean()).isFalse();
        assertThat(source.path("factFields").asInt()).isZero();
    }

    @Test
    void zeroCountWithAnItemIsRejectedAsAnInconsistentResponse() throws Exception {
        easy = success(0, List.of(Map.of("itemSeq", CODE, "efcyQesitm", FACT)));
        var run = run(CODE, KEY);
        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.report().path("sources").get(0).path("result").asString()).isEqualTo("FAILED");
        assertThat(run.report().path("sources").get(0).path("factFields").asInt()).isZero();
    }

    @Test
    void anItemWithoutEvidenceFieldsIsNotReadyEvidence() throws Exception {
        easy = success(1, List.of(Map.of("itemSeq", CODE)));
        permit = success(1, List.of(Map.of("ITEM_SEQ", CODE)));
        var run = run(CODE, KEY);
        assertThat(run.exit()).isZero();
        assertThat(run.report().path("result").asString()).isEqualTo("NO_INFORMATION");
        for (var source : run.report().path("sources")) {
            assertThat(source.path("result").asString()).isEqualTo("NO_CONTENT");
        }
    }

    @Test
    void missingKeyOrInvalidInputStopsBeforeAnyRequestOrReport() throws Exception {
        assertThat(run(CODE, "").exit()).isEqualTo(2);
        assertThat(run("https://invalid.example/?serviceKey=private", KEY).exit()).isEqualTo(2);
        assertThat(requests).isEmpty();
        assertThat(Files.exists(temporary.resolve("probe.json"))).isFalse();
    }

    private Run run(String code, String key) throws Exception {
        var output = new ByteArrayOutputStream();
        int exit = OfficialEvidenceCli.probe(code, "http://127.0.0.1:" + server.getAddress().getPort(), key,
                temporary.resolve("probe.json"), new PrintStream(output, true, StandardCharsets.UTF_8));
        String text = output.toString(StandardCharsets.UTF_8);
        return new Run(exit, text.startsWith("{") ? json.readTree(text) : null, text);
    }

    private String success(int total, List<Map<String, String>> items) {
        return json.writeValueAsString(Map.of("header", Map.of("resultCode", "00"),
                "body", Map.of("totalCount", total, "items", items)));
    }
    private record Run(int exit, JsonNode report, String text) {}
}

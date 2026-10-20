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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semiproject.pillbadeumserver.chat.service.DurLookup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 앱 DUR 수집기와 실제 로컬 HTTP를 사용해 진단의 호출 계약·실패 구분·출력 경계를 확인한다. */
class DurEvidenceCliTests {
    private static final String CODE = "199900001", KEY = "synthetic+/=key", PRIVATE = "not-for-report";
    private final JsonMapper json = new JsonMapper();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String mode = "empty";
    @TempDir Path temporary;

    @BeforeEach void prepare() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            queries.add(exchange.getRequestURI().toString());
            var type = List.of(DurLookup.Type.values()).stream().filter(t -> t.path().equals(exchange.getRequestURI().getPath())).findFirst().orElseThrow();
            int status = mode.equals("http-error") && type == DurLookup.Type.AGE ? 400 : 200;
            List<Map<String, String>> items = mode.equals("found") || mode.equals("wrong-item") && type == DurLookup.Type.AGE
                    ? List.of(Map.of("ITEM_SEQ", mode.equals("wrong-item") ? "199900003" : CODE,
                            "TYPE_NAME", type.label(), "MIXTURE_ITEM_SEQ", "199900002", "PROHBT_CONTENT", PRIVATE,
                            "INGR_NAME", PRIVATE, "INGR_KOR_NAME", PRIVATE, "CHANGE_DATE", "20261002")) : List.of();
            String body = status == 400 ? "INVALID_REQUEST_PARAMETER_ERROR " + KEY + PRIVATE
                    : json.writeValueAsString(Map.of("header", Map.of("resultCode", "00"), "body", Map.of("totalCount", items.size(), "items", items)));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }
    @AfterEach void close() { server.stop(0); }

    @Test void allTypesUseOnlyTheExactPublicItemFilterAndExcludeClinicalText() throws Exception {
        mode = "found";
        var run = probe(CODE, KEY);
        assertThat(run.exit()).isZero();
        assertThat(run.report().path("lookup").asString()).isEqualTo("ALL");
        assertThat(run.report().path("clientCalls").asInt()).isEqualTo(4);
        assertThat(queries).hasSize(4).allSatisfy(q -> {
            assertThat(URLDecoder.decode(q, StandardCharsets.UTF_8)).contains("itemSeq=" + CODE, "serviceKey=" + KEY);
            assertThat(q).doesNotContain("itemName", "birthYear", "pregnant", "gender");
        });
        for (var type : run.report().path("types")) {
            assertThat(type.path("result").asString()).isEqualTo("FOUND");
            assertThat(type.path("rows").asInt()).isEqualTo(1);
            assertThat(type.path("updatedAt").asString()).isEqualTo("2026-10-02");
        }
        assertThat(run.text()).doesNotContain(KEY, PRIVATE, "199900002", "serviceKey", "http://");
        assertThat(Files.readString(temporary.resolve("result.json")).strip()).isEqualTo(run.text().strip());
    }
    @Test void normalZeroRowsMeanSuccessfulEmptyLookupWithoutPatientJudgment() throws Exception {
        var run = probe(CODE, KEY);
        assertThat(run.exit()).isZero();
        assertThat(run.report().path("lookup").asString()).isEqualTo("ALL");
        for (var type : run.report().path("types")) assertThat(type.path("result").asString()).isEqualTo("EMPTY");
        assertThat(run.report().has("result")).isFalse();
    }
    @Test void aFailedTypeRemainsPartialInsteadOfBecomingNoContraindications() throws Exception {
        mode = "http-error";
        var run = probe(CODE, KEY);
        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.report().path("lookup").asString()).isEqualTo("PARTIAL");
        for (var type : run.report().path("types")) if (type.path("type").asString().equals("AGE")) {
            assertThat(type.path("result").asString()).isEqualTo("FAILED");
            assertThat(type.path("httpStatus").asInt()).isEqualTo(400);
            assertThat(type.path("rows").isNull()).isTrue();
            assertThat(type.path("updatedAt").isNull()).isTrue();
        }
        assertThat(run.text()).doesNotContain(KEY, PRIVATE);
        assertThat(queries).hasSize(4);
    }
    @Test void wrongItemEvenOnHttp200IsNotReportedAsFound() throws Exception {
        mode = "wrong-item";
        var run = probe(CODE, KEY);
        assertThat(run.exit()).isEqualTo(1);
        for (var type : run.report().path("types")) if (type.path("type").asString().equals("AGE"))
            assertThat(type.path("result").asString()).isEqualTo("FAILED");
    }
    @Test void invalidInputOrMissingKeyDoesNotCallOrWrite() throws Exception {
        assertThat(probe("invalid", KEY).exit()).isEqualTo(2);
        assertThat(probe(CODE, "").exit()).isEqualTo(2);
        assertThat(queries).isEmpty();
        assertThat(Files.exists(temporary.resolve("result.json"))).isFalse();
    }
    private Run probe(String code, String key) throws Exception {
        var output = new ByteArrayOutputStream();
        int exit = DurEvidenceCli.probe(code, "http://127.0.0.1:" + server.getAddress().getPort(), key,
                temporary.resolve("result.json"), new PrintStream(output, true, StandardCharsets.UTF_8));
        String text = output.toString(StandardCharsets.UTF_8);
        return new Run(exit, text.startsWith("{") ? json.readTree(text) : null, text);
    }
    private record Run(int exit, JsonNode report, String text) {}
}

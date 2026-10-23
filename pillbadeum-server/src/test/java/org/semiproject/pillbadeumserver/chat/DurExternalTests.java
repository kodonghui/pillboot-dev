package org.semiproject.pillbadeumserver.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.semiproject.pillbadeumserver.chat.service.*;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 공식 Service03 필드·페이지·실패 의미를 로컬 합성 HTTP 응답과 실제 RestClient로 검증한다. */
class DurExternalTests {
    private final JsonMapper json = new JsonMapper();
    private final LocalDate today = LocalDate.now(TimeZoneConfig.ZONE);
    private HttpServer server;
    private ExecutorService httpThreads;
    private SimpleAsyncTaskExecutor executor;
    private DurLookupService service;
    private final AtomicReference<Function<Request, Reply>> responder = new AtomicReference<>();
    private final List<Request> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maxActive = new AtomicInteger();

    @BeforeEach
    void prepare() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpThreads = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(httpThreads);
        server.createContext("/", this::handle);
        server.start();
        executor = new SimpleAsyncTaskExecutor("dur-http-test-");
        executor.setVirtualThreads(true);
        service = new DurLookupService(new PublicDataClient(json, "http://127.0.0.1:" + server.getAddress().getPort(),
                "synthetic-dur-key", 1000, 250, 2, 100), executor, 2);
        responder.set(request -> success(0, request.page(), List.of()));
    }

    @AfterEach
    void close() {
        server.stop(0);
        httpThreads.shutdownNow();
        executor.close();
    }

    @Test
    void exactCodesAndService03PathsAreUsedWithoutQuestionOrPatientData() {
        var input = input(List.of("A+ /="), "FEMALE", true);
        responder.set(r -> success(1, r.page(), List.of(row(r.code(), r.type(), "synthetic official caution"))));
        var results = service.collect(input, deadline());
        assertThat(results).hasSize(2).allMatch(DurLookup::success);
        assertThat(requests).extracting(Request::path).containsExactlyInAnyOrder(DurLookup.Type.AGE.path(), DurLookup.Type.PREGNANCY.path());
        assertThat(requests).allSatisfy(r -> {
            assertThat(r.params()).containsOnlyKeys("serviceKey", "type", "itemSeq", "pageNo", "numOfRows");
            assertThat(r.code()).isEqualTo("A+ /=");
            assertThat(r.params().get("type")).isEqualTo("json");
            assertThat(r.params().get("numOfRows")).isEqualTo("100");
            assertThat(r.params().toString()).doesNotContain(input.question(), "FEMALE", "2000");
        });
        assertThat(results.getFirst().rows().getFirst().updatedAt()).isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(maxActive.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void backgroundPrefetchUsesAtMostTwoOfFourProviderSlots() {
        service = new DurLookupService(new PublicDataClient(json,
            "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-dur-key", 1000, 1000, 4, 1000), executor, 4);
        responder.set(r -> {
            try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return success(0, r.page(), List.of());
        });
        var result = service.collectForPrefetch(List.of("A", "B"), java.util.EnumSet.allOf(DurLookup.Type.class), deadline());
        assertThat(result).hasSize(8).allMatch(DurLookup::success);
        assertThat(maxActive.get()).isBetween(1, 2);
    }

    @Test
    void oneProviderSlotIsReservedForForegroundInsteadOfBackgroundPrefetch() {
        service = new DurLookupService(new PublicDataClient(json,
            "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-dur-key", 1000, 1000, 1, 1000), executor, 1);
        assertThat(service.collectForPrefetch(List.of("A"), java.util.EnumSet.allOf(DurLookup.Type.class), deadline())).isEmpty();
        assertThat(requests).isEmpty();
        assertThat(service.collectItems(List.of("A"), java.util.Set.of(DurLookup.Type.AGE), deadline())).singleElement()
            .satisfies(r -> assertThat(r.success()).isTrue());
    }

    @Test
    void allPagesAreReadBeforeATypeCanBeSuccessful() {
        responder.set(r -> {
            var rows = new ArrayList<Map<String, String>>();
            int count = r.page() == 1 ? 100 : 1;
            for (int i = 0; i < count; i++) {
                var row = new LinkedHashMap<>(row(r.code(), r.type(), "12세 미만"));
                row.put("INGR_NAME", "ingredient-" + r.page() + "-" + i);
                rows.add(row);
            }
            return success(101, r.page(), rows);
        });
        var result = collect("A").getFirst();
        assertThat(result.success()).isTrue();
        assertThat(result.rows()).hasSize(101);
        assertThat(requests).extracting(Request::page).containsExactly(1, 2);
    }

    @Test
    void aMissingLastPageDoesNotBecomePartialSuccessfulCacheData() {
        responder.set(r -> {
            var rows = new ArrayList<Map<String, String>>();
            if (r.page() == 1) for (int i = 0; i < 100; i++) {
                var row = new LinkedHashMap<>(row(r.code(), r.type(), "12세 미만"));row.put("INGR_NAME", "i-" + i);rows.add(row);
            }
            return success(101, r.page(), rows);
        });
        var result = collect("A").getFirst();
        assertThat(result.success()).isFalse();
        assertThat(result.rows()).isEmpty();
        assertThat(requests).hasSize(2);
    }

    @Test
    void allThreePagesAboveTheOldTwoHundredRowLimitAreRetained() {
        responder.set(r -> {
            var values = new ArrayList<Map<String, String>>();
            for (int i = (r.page() - 1) * 100; i < Math.min(r.page() * 100, 201); i++) {
                var value = new LinkedHashMap<>(row(r.code(), r.type(), "12세 미만"));
                value.put("INGR_NAME", "성분" + i); values.add(value);
            }
            return success(201, r.page(), values);
        });
        var result = collect("A").getFirst();
        assertThat(result.success()).isTrue();
        assertThat(result.rows()).hasSize(201);
        assertThat(requests).extracting(Request::page).containsExactly(1, 2, 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-code", "wrong-type", "blank-content", "missing-header", "oversized-total", "non-string-field", "contradictory-zero"})
    void malformedOrUnboundedResultsAreUnavailable(String scenario) {
        responder.set(r -> {
            var item = new LinkedHashMap<String, Object>(row(r.code(), r.type(), "12세 미만"));
            if (scenario.equals("wrong-code")) item.put("ITEM_SEQ", "different-code");
            if (scenario.equals("wrong-type")) item.put("TYPE_NAME", "임부금기");
            if (scenario.equals("blank-content")) item.put("PROHBT_CONTENT", " ");
            if (scenario.equals("non-string-field")) item.put("PROHBT_CONTENT", Map.of("text", "untrusted"));
            String body = success(scenario.equals("oversized-total") ? 201 : scenario.equals("contradictory-zero") ? 0 : 1,
                    r.page(), List.of(item)).body();
            if (scenario.equals("missing-header")) body = json.writeValueAsString(Map.of("body", Map.of("totalCount", 0, "items", List.of())));
            return new Reply(200, body);
        });
        var input = input(List.of("A"), "MALE", false);
        var notice = new DurEvaluator().evaluate(input, service.collect(input, deadline())).notice();
        assertThat(notice.lookup()).isEqualTo("FAILED");
        assertThat(notice.result()).isNull();
        assertThat(requests).hasSize(1);
    }

    @Test
    void changingCountsRepeatedPagesAndExcessiveClinicalTextAreNotSilentlyTruncated() {
        responder.set(r -> success(2, r.page(), List.of(row(r.code(), r.type(), "12세 미만"), row(r.code(), r.type(), "12세 미만"))));
        assertThat(collect("A").getFirst().success()).isFalse();
        responder.set(r -> success(1, r.page(), List.of(row(r.code(), r.type(), "x".repeat(8_001)))));
        assertThat(collect("A").getFirst().success()).isFalse();
        responder.set(r -> success(6, r.page(), IntStream.range(0, 6).mapToObj(i -> row(r.code(), r.type(), i + "x".repeat(5000))).toList()));
        var large = collect("A").getFirst();
        assertThat(large.success()).isTrue();
        assertThat(large.rows()).hasSize(6).allSatisfy(row -> assertThat(row.content().length()).isEqualTo(5001));
    }

    @Test
    void temporaryHttpFailureRetriesOnceAndPermanentFailureDoesNot() {
        var attempts = new AtomicInteger();
        responder.set(r -> attempts.incrementAndGet() == 1 ? new Reply(503, "{}") : success(0, r.page(), List.of()));
        assertThat(collect("A").getFirst().success()).isTrue();
        assertThat(attempts.get()).isEqualTo(2);
        responder.set(r -> new Reply(403, "{}"));
        requests.clear();
        assertThat(collect("A").getFirst().success()).isFalse();
        assertThat(requests).hasSize(1);
    }

    @Test
    void duplicateRegistrationsAreOneLookupAndEleventhDrugIsAlsoChecked() {
        var codes = new ArrayList<>(IntStream.range(0, 11).mapToObj(i -> "C" + i).toList());
        codes.addFirst("C0");
        var input = input(codes, "MALE", false);
        var results = service.collect(input, deadline());
        var notice = new DurEvaluator().evaluate(input, results).notice();
        assertThat(requests).hasSize(22);
        assertThat(requests).anyMatch(r -> r.code().equals("C10"));
        assertThat(notice.lookup()).isEqualTo("ALL");
        assertThat(notice.checkedCount()).isEqualTo(11);
        assertThat(notice.uncheckedDrugNames()).isEmpty();
    }

    @Test
    void aShortSharedDeadlineReturnsUnverifiedAndHasNoDbWritePath() {
        responder.set(r -> {
            try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return success(0, r.page(), List.of());
        });
        var input = input(List.of("A"), "MALE", false);
        var results = service.collect(input, ChatDeadline.after(Duration.ofMillis(60)));
        assertThat(results).allMatch(r -> !r.success());
        assertThat(new DurEvaluator().evaluate(input, results).notice().result()).isNull();
    }

    private List<DurLookup> collect(String code) { return service.collect(input(List.of(code), "MALE", false), deadline()); }
    private ChatDeadline deadline() { return ChatDeadline.after(Duration.ofSeconds(10)); }
    private AnswerInput input(List<String> codes, String gender, Boolean pregnant) {
        return new AnswerInput(today.getYear() - 26, gender, pregnant, "private-question-sentinel", codes.stream().map(code ->
                new AnswerInput.Medication(code, "drug-" + code, "maker", "OTC", 1, "식후", today, today,
                        List.of(LocalTime.of(7, 0)), today)).toList(), List.of(), false);
    }
    private Map<String, String> row(String code, DurLookup.Type type, String text) {
        return Map.of("ITEM_SEQ", code, "TYPE_NAME", type.label(), "PROHBT_CONTENT", text,
                "CHANGE_DATE", "20261017", "INGR_NAME", "synthetic ingredient");
    }
    private Reply success(int count, int page, List<?> items) {
        return new Reply(200, json.writeValueAsString(Map.of("header", Map.of("resultCode", "00"),
                "body", Map.of("totalCount", count, "pageNo", page, "items", items))));
    }
    private void handle(HttpExchange exchange) throws IOException {
        maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
        try {
            String path = exchange.getRequestURI().getPath();
            var type = java.util.Arrays.stream(DurLookup.Type.values()).filter(t -> t.path().equals(path)).findFirst().orElseThrow();
            Map<String, String> params = new LinkedHashMap<>();
            for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                params.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            var request = new Request(path, type, params.get("itemSeq"), Integer.parseInt(params.get("pageNo")), params);
            requests.add(request);
            var reply = responder.get().apply(request);
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        } finally { active.decrementAndGet(); exchange.close(); }
    }
    private record Request(String path, DurLookup.Type type, String code, int page, Map<String, String> params) {}
    private record Reply(int status, String body) {}
}

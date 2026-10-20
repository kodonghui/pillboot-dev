package org.semiproject.pillbadeumserver.chat;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.semiproject.pillbadeumserver.chat.service.ChatDeadline;
import org.semiproject.pillbadeumserver.chat.service.DurLookup;
import org.semiproject.pillbadeumserver.chat.service.DurLookupService;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** CHAT-008: 공개 품목 하나의 DUR 전 유형을 앱 수집기로 확인한다. 환자 대조·DB·모델 호출은 없다. */
public final class DurEvidenceCli {
    private DurEvidenceCli() {}

    public static void main(String[] args) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(TimeZoneConfig.ZONE));
        int exit = 2;
        if (args.length != 1 || !valid(args[0])) {
            System.out.println("사용법: tryDurEvidence --args=<9자리 공개 품목기준코드>");
        } else {
            try {
                Properties local = new Properties();
                if (Files.isRegularFile(Path.of(".env"))) {
                    try (var reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) { local.load(reader); }
                }
                ChatProviderCli.configureProxy();
                exit = probe(args[0], "https://apis.data.go.kr", ChatProviderCli.setting(local, "PUBLIC_DATA_SERVICE_KEY", ""),
                        Path.of("build/local-probes/dur-evidence.json"), System.out);
            } catch (Exception exception) {
                System.out.println("durEvidence=SETUP_FAILED");
            }
        }
        System.exit(exit);
    }

    /** 로컬 HTTP 시험만 공식 주소 대신 합성 서버를 주입한다. 임의 주소를 터미널 인자로 받지 않는다. */
    static int probe(String code, String base, String key, Path reportPath, PrintStream out) throws Exception {
        if (!valid(code)) { out.println("durEvidence=INVALID_ITEM_SEQ"); return 2; }
        if (key == null || key.isBlank()) { out.println("durEvidence=SKIPPED_MISSING_KEY"); return 2; }
        var json = new JsonMapper();
        var client = new MeasuredClient(json, base, key);
        long start = System.nanoTime();
        List<TypeResult> types = new ArrayList<>();
        try (var executor = new SimpleAsyncTaskExecutor("dur-evidence-probe-")) {
            executor.setVirtualThreads(true);
            executor.setTaskTerminationTimeout(1000);
            var collector = new DurLookupService(client, executor, 4);
            var lookups = collector.collectItems(List.of(code), EnumSet.allOf(DurLookup.Type.class),
                    ChatDeadline.after(Duration.ofSeconds(30)));
            for (var lookup : lookups) {
                var call = client.calls.get(lookup.type().path());
                types.add(new TypeResult(lookup.type().name(), lookup.success() ? lookup.rows().isEmpty() ? "EMPTY" : "FOUND" : "FAILED",
                        lookup.success() ? lookup.rows().size() : null,
                        lookup.success() ? lookup.rows().stream().mapToInt(r -> r.content().length()).sum() : null,
                        call == null ? null : call.elapsedMs(), call == null ? null : call.httpStatus(),
                        call == null ? null : call.providerCode(), lookup.success() ? lookup.updatedAt() : null));
            }
        }
        long successful = types.stream().filter(t -> !t.result().equals("FAILED")).count();
        var report = new Report(OffsetDateTime.now(TimeZoneConfig.ZONE).toString(), code,
                successful == types.size() ? "ALL" : successful == 0 ? "FAILED" : "PARTIAL",
                (System.nanoTime() - start) / 1_000_000, client.clientCalls.get(), List.copyOf(types));
        // 공식 본문/성분명/상대 품목 목록·제품명·키·URL은 보고서에 복사하지 않는다.
        String serialized = json.writeValueAsString(report);
        Files.createDirectories(reportPath.toAbsolutePath().getParent());
        Files.writeString(reportPath, serialized + System.lineSeparator(), StandardCharsets.UTF_8);
        out.println(serialized);
        return successful == types.size() ? 0 : 1;
    }

    private static boolean valid(String code) { return code != null && code.matches("[0-9]{9}"); }
    record Report(String observedAt, String itemSeq, String lookup, long elapsedMs, int clientCalls, List<TypeResult> types) {}
    record TypeResult(String type, String result, Integer rows, Integer contentCharacters, Long elapsedMs,
                      Integer httpStatus, String providerCode, LocalDate updatedAt) {}
    private record Call(long elapsedMs, Integer httpStatus, String providerCode) {}

    /** 한 유형의 페이지별 호출 시간을 합산한다. 공통 재시도 안의 실제 HTTP 횟수와 clientCalls는 구분한다. */
    private static final class MeasuredClient extends PublicDataClient {
        final Map<String, Call> calls = new ConcurrentHashMap<>();
        final AtomicInteger clientCalls = new AtomicInteger();
        MeasuredClient(JsonMapper json, String base, String key) { super(json, base, key, 2000, 5000, 4, 1000); }
        @Override public JsonNode get(String path, Map<String, ?> params) {
            return measured(path, () -> super.get(path, params));
        }
        @Override public JsonNode get(String path, Map<String, ?> params, java.time.Duration budget) {
            return measured(path, () -> super.get(path, params, budget));
        }
        private JsonNode measured(String path, java.util.function.Supplier<JsonNode> call) {
            long start = System.nanoTime();
            clientCalls.incrementAndGet();
            Integer status = null; String code = null;
            try { return call.get(); }
            catch (RuntimeException exception) {
                for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                    if (cause instanceof RestClientResponseException http) {
                        status = http.getStatusCode().value(); code = "unknown";
                        for (String known : List.of("INVALID_REQUEST_PARAMETER_ERROR", "SERVICE_KEY_IS_NULL",
                                "SERVICE_KEY_IS_NOT_REGISTERED_ERROR", "SERVICE_ACCESS_DENIED_ERROR", "PERMISSION_DENIED",
                                "BLACKLIST_IP_ACCESS_ERROR", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR")) {
                            if (http.getResponseBodyAsString().contains(known)) { code = known; break; }
                        }
                        break;
                    }
                }
                throw exception;
            } finally {
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                var previous = calls.get(path);
                calls.put(path, new Call(elapsed + (previous == null ? 0 : previous.elapsedMs()), status, code));
            }
        }
    }
}

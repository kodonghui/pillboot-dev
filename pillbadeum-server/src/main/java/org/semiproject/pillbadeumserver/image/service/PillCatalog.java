package org.semiproject.pillbadeumserver.image.service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.semiproject.pillbadeumserver.common.exception.*;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Complete public identification catalog. HTTP handlers never download the catalog or wait for its lock. */
@Service
public class PillCatalog implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PillCatalog.class);
    private static final int PAGE_SIZE = 100; // Verified against the live API: 999 returns resultCode 11.
    private static final Duration MAX_AGE = Duration.ofHours(6);
    private static final List<String> FIELDS = List.of("ITEM_SEQ", "ITEM_NAME", "ENTP_NAME", "ITEM_IMAGE",
            "PRINT_FRONT", "PRINT_BACK", "DRUG_SHAPE", "COLOR_CLASS1", "COLOR_CLASS2", "LINE_FRONT", "LINE_BACK",
            "ETC_OTC_NAME", "CLASS_NAME", "FORM_CODE_NAME", "CHART", "ITEM_PERMIT_DATE", "CHANGE_DATE");
    private final PublicDataClient client;
    private final ObjectMapper json;
    private final Path file;
    private final boolean refreshEnabled;
    private final int parallelism;
    private final Object refreshLock = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("pill-catalog-").factory());
    private CompletableFuture<Void> refreshing;
    private Instant retryAfter = Instant.EPOCH;
    private int failures;
    private volatile Snapshot snapshot;

    public PillCatalog(PublicDataClient client, ObjectMapper json,
                       @Value("${pill-identification.catalog-file:./.cache/pill-identification/catalog.json}") String file,
                       @Value("${pill-identification.catalog-refresh-enabled:true}") boolean refreshEnabled,
                       @Value("${public-data.max-concurrent-calls:4}") int maxConcurrentCalls) {
        this.client = client;
        this.json = json;
        this.file = Path.of(file).toAbsolutePath().normalize();
        this.refreshEnabled = refreshEnabled;
        this.parallelism = Math.max(1, Math.min(3, maxConcurrentCalls));
        loadSnapshot();
    }

    public List<JsonNode> rows() {
        Snapshot current = snapshot;
        if (current == null || current.loadedAt().plus(MAX_AGE).isBefore(Instant.now())) refreshInBackground();
        if (current == null) throw new BusinessException(ErrorCode.PILL_CATALOG_PREPARING,
                "의약품 식별 자료를 준비하고 있습니다. 잠시 후 다시 요청해주세요.");
        return current.rows();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { if (snapshot == null || snapshot.loadedAt().plus(MAX_AGE).isBefore(Instant.now())) refreshInBackground(); }

    @Scheduled(initialDelayString = "PT6H", fixedDelayString = "PT6H")
    public void scheduledRefresh() { refreshInBackground(); }

    private synchronized void refreshInBackground() {
        if (!refreshEnabled || Instant.now().isBefore(retryAfter) || (refreshing != null && !refreshing.isDone())) return;
        refreshing = CompletableFuture.runAsync(() -> {
            try { refresh(); synchronized (this) { failures = 0; } }
            catch (RuntimeException error) {
                synchronized (this) { retryAfter = Instant.now().plus(retryDelay(++failures)); }
                log.warn("[pill catalog] refresh=failed type={} cacheRetained={}", error.getClass().getSimpleName(), snapshot != null);
            }
        }, worker);
    }

    /** 연속 실패는 1·2·4분씩 늘리고 정상 갱신 주기인 6시간을 상한으로 사용함. */
    static Duration retryDelay(int failures) {
        long minutes = 1L << Math.min(Math.max(failures, 1) - 1, 9);
        return Duration.ofMinutes(Math.min(minutes, MAX_AGE.toMinutes()));
    }

    /** CLI and background worker use this method; a snapshot is replaced only after every page passes validation. */
    public void refresh() { synchronized (refreshLock) { refreshSnapshot(); } }

    private void refreshSnapshot() {
        long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
        JsonNode first = page(1, deadline);
        int total = first.path("totalCount").asInt(-1);
        List<JsonNode> firstRows = PublicDataClient.items(first);
        if (total < 0 || total > 1_000_000 || (total > 0 && firstRows.isEmpty())) throw invalid();
        int pageSize = first.path("numOfRows").asInt(PAGE_SIZE);
        if (pageSize < 1 || pageSize > PAGE_SIZE) throw invalid();
        int pages = (int)Math.ceil((double)total / pageSize);
        List<JsonNode> all = new ArrayList<>(firstRows);
        Set<List<String>> seenPages = new HashSet<>();
        seenPages.add(firstRows.stream().map(row -> PillSimilarity.text(row, "ITEM_SEQ")).toList());
        log.info("[pill catalog] refresh=started total={} pages={}", total, pages);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int start = 2; start <= pages; start += parallelism) {
                List<Future<JsonNode>> tasks = new ArrayList<>();
                try {
                    for (int number = start; number < start + parallelism && number <= pages; number++) {
                        int requested = number;
                        tasks.add(pool.submit(() -> page(requested, deadline)));
                    }
                    for (Future<JsonNode> task : tasks) {
                        var rows = PublicDataClient.items(task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                        if (rows.isEmpty()) throw invalid();
                        if (!seenPages.add(rows.stream().map(row -> PillSimilarity.text(row, "ITEM_SEQ")).toList())) throw invalid();
                        all.addAll(rows);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw ExternalApiException.timeout("의약품 식별자료", e);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException r) throw r;
                    throw invalid();
                } catch (TimeoutException e) {
                    throw ExternalApiException.timeout("의약품 식별자료", e);
                } finally { tasks.stream().filter(t -> !t.isDone()).forEach(t -> t.cancel(true)); }
            }
        }
        log.info("[pill catalog] pages=received expectedRows={} actualRows={}", total, all.size());
        if (all.size() != total) throw invalid();
        List<JsonNode> compactRows = new ArrayList<>();
        Set<String> uniqueCodes = new HashSet<>();
        for (JsonNode row : all) {
            String code = PillSimilarity.text(row, "ITEM_SEQ");
            if (code == null || PillSimilarity.text(row, "ITEM_NAME") == null) throw invalid();
            var compact = json.createObjectNode();
            for (String field : FIELDS) { String value = PillSimilarity.text(row, field); if (value != null) compact.put(field, value); }
            compactRows.add(compact);
            uniqueCodes.add(code);
        }
        // The source legitimately contains multiple records per code. Preserve their imprints;
        // the shared matcher chooses the best record per code before applying the five-candidate limit.
        log.info("[pill catalog] uniqueCodes={} sourceRows={}", uniqueCodes.size(), total);
        Snapshot next = new Snapshot(Instant.now(), List.copyOf(compactRows));
        persist(next);
        snapshot = next;
        log.info("[pill catalog] refresh=complete rows={}", next.rows().size());
    }

    private JsonNode page(int number, long deadline) {
        JsonNode body = client.get(DrugSyncService.GRAIN_INFO_PATH, Map.of("pageNo", number, "numOfRows", PAGE_SIZE),
                Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        if (body.has("pageNo") && body.path("pageNo").asInt(-1) != number) throw invalid();
        return body;
    }

    private void loadSnapshot() {
        if (!Files.isRegularFile(file)) return;
        try {
            JsonNode data = json.readTree(Files.readString(file));
            if (data.path("version").asInt(0) != 1 || !data.path("items").isArray()) return;
            List<JsonNode> rows = PublicDataClient.items(data);
            if (data.path("totalCount").asInt(-1) != rows.size()) return;
            for (JsonNode row : rows) {
                String code = PillSimilarity.text(row, "ITEM_SEQ");
                if (code == null || PillSimilarity.text(row, "ITEM_NAME") == null) return;
            }
            snapshot = new Snapshot(Instant.ofEpochSecond(data.path("loadedAt").asLong(0)), List.copyOf(rows));
            log.info("[pill catalog] loaded rows={}", rows.size());
        } catch (IOException | RuntimeException error) { log.warn("[pill catalog] cache=unreadable type={}", error.getClass().getSimpleName()); }
    }

    private void persist(Snapshot value) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "catalog-", ".tmp");
            Files.writeString(temporary, json.writeValueAsString(Map.of("version", 1,
                    "loadedAt", value.loadedAt().getEpochSecond(), "totalCount", value.rows().size(), "items", value.rows())));
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException error) { throw ExternalApiException.failed("의약품 식별자료", "식별 자료를 저장하지 못했습니다.", error); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }

    private static ExternalApiException invalid() { return ExternalApiException.failed("의약품 식별자료", "전체 식별 자료를 확인하지 못했습니다.", null); }
    private record Snapshot(Instant loadedAt, List<JsonNode> rows) {}
    @PreDestroy @Override public void close() { worker.shutdownNow(); }
}

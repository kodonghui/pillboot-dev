package org.semiproject.pillbadeumserver.image;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semiproject.pillbadeumserver.common.exception.*;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.service.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PillCatalogTests {
    @TempDir Path directory;
    final JsonMapper json = new JsonMapper();
    final PublicDataClient client = mock(PublicDataClient.class);
    final PillIdentification clues = new PillIdentification("null", null, "DK", "10", "원형", "흰색", null, null, null);

    @Test
    void searchesPastThreePagesAndReloadsCompleteSnapshotWithoutExternalCalls() {
        var all = new ArrayList<JsonNode>();
        for (int i = 0; i < 400; i++) all.add(row("9" + String.format("%08d", i), "다른약", i < 4 ? "DK" : "AB", "20"));
        all.add(row("197400040", "인데놀정10mg(프로프라놀롤염산염)", "DK", "10"));
        when(client.get(eq(DrugSyncService.GRAIN_INFO_PATH), anyMap(), any(Duration.class))).thenAnswer(call -> {
            Map<String, Object> query = call.getArgument(1);
            assertThat(query).containsEntry("numOfRows", 100).hasSize(2);
            int page = (int)query.get("pageNo"), start = (page - 1) * 100;
            return json.valueToTree(Map.of("pageNo", page, "numOfRows", 100, "totalCount", all.size(),
                    "items", all.subList(start, Math.min(start + 100, all.size()))));
        });
        Path file = directory.resolve("catalog.json");
        try (var catalog = new PillCatalog(client, json, file.toString(), false, 4)) {
            catalog.refresh();
            var result = new PillDrugIdentificationService(client, catalog).identify(clues);
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().itemSeq()).isEqualTo("197400040");
            assertThat(result.getFirst().similarityScore()).isNull();
            verify(client, times(5)).get(eq(DrugSyncService.GRAIN_INFO_PATH), anyMap(), any(Duration.class));
        }
        clearInvocations(client);
        try (var reloaded = new PillCatalog(client, json, file.toString(), false, 4)) {
            assertThat(reloaded.rows()).hasSize(401);
            assertThat(new PillDrugIdentificationService(client, reloaded).identify(clues).getFirst().itemSeq()).isEqualTo("197400040");
            verifyNoInteractions(client);
        }
    }

    @Test
    void preservesMultipleSourceRecordsPerCodeButReturnsOneBestCandidate() {
        var variants = List.of(row("197400040", "인데놀정10mg", "DK", "20"), row("197400040", "인데놀정10mg", "DK", "10"));
        when(client.get(anyString(), anyMap(), any(Duration.class))).thenReturn(json.valueToTree(Map.of(
                "totalCount", 2, "numOfRows", 100, "items", variants)));
        Path file = directory.resolve("variants.json");
        try (var catalog = new PillCatalog(client, json, file.toString(), false, 4)) {
            catalog.refresh();
            assertThat(catalog.rows()).hasSize(2);
            assertThat(new PillDrugIdentificationService(client, catalog).identify(clues)).singleElement()
                    .satisfies(candidate -> assertThat(candidate.printBack()).isEqualTo("10"));
        }
        try (var reloaded = new PillCatalog(client, json, file.toString(), false, 4)) { assertThat(reloaded.rows()).hasSize(2); }
    }

    @Test
    void failedRefreshDoesNotReplacePreviousCompleteSnapshot() {
        when(client.get(anyString(), anyMap(), any(Duration.class))).thenReturn(json.valueToTree(Map.of(
                "numOfRows", 100, "totalCount", 1, "items", List.of(row("197400040", "인데놀정10mg", "DK", "10")))));
        Path file = directory.resolve("catalog.json");
        try (var catalog = new PillCatalog(client, json, file.toString(), false, 1)) {
            catalog.refresh();
            when(client.get(anyString(), anyMap(), any(Duration.class))).thenAnswer(call -> {
                Map<String, Object> query = call.getArgument(1);
                if ((int)query.get("pageNo") > 1) throw ExternalApiException.failed("공공데이터", "", null);
                return json.valueToTree(Map.of("numOfRows", 100, "totalCount", 101, "items", catalog.rows()));
            });
            assertThatThrownBy(catalog::refresh).isInstanceOf(ExternalApiException.class);
            assertThat(catalog.rows()).hasSize(1);
        }
        try (var reloaded = new PillCatalog(client, json, file.toString(), false, 1)) { assertThat(reloaded.rows()).hasSize(1); }
    }

    @Test
    void repeatedRefreshFailuresWaitLongerInsteadOfDownloadingEveryMinute() throws Exception {
        var failSecondPage = new java.util.concurrent.atomic.AtomicBoolean(true);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(client.get(anyString(), anyMap(), any(Duration.class))).thenAnswer(call -> {
            calls.incrementAndGet();
            Map<String, Object> query = call.getArgument(1);
            int page = (int)query.get("pageNo");
            if (page == 2 && failSecondPage.get()) throw ExternalApiException.failed("공공데이터", "", null);
            var rows = new ArrayList<JsonNode>();
            for (int i = (page - 1) * 100; i < Math.min(page * 100, 101); i++) rows.add(row(String.valueOf(100000 + i), "약" + i, "P" + i, "10"));
            return json.valueToTree(Map.of("pageNo", page, "numOfRows", 100, "totalCount", 101, "items", rows));
        });
        try (var catalog = new PillCatalog(client, json, directory.resolve("retry.json").toString(), true, 4)) {
            assertThatThrownBy(catalog::rows).isInstanceOf(BusinessException.class);
            assertThat(waitForRetryAfter(catalog)).isBetween(Duration.ofSeconds(50), Duration.ofMinutes(1));
            assertThat(calls.get()).isEqualTo(2);
            assertThatThrownBy(catalog::rows).isInstanceOf(BusinessException.class); // 기다리는 동안은 다시 받지 않음
            assertThat(calls.get()).isEqualTo(2);
            org.springframework.test.util.ReflectionTestUtils.setField(catalog, "retryAfter", java.time.Instant.EPOCH);
            assertThatThrownBy(catalog::rows).isInstanceOf(BusinessException.class);
            assertThat(waitForRetryAfter(catalog)).isBetween(Duration.ofSeconds(110), Duration.ofMinutes(2));
            failSecondPage.set(false);
            org.springframework.test.util.ReflectionTestUtils.setField(catalog, "retryAfter", java.time.Instant.EPOCH);
            assertThatThrownBy(catalog::rows).isInstanceOf(BusinessException.class);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            List<JsonNode> loaded = null;
            while (loaded == null && System.nanoTime() < end) {
                try { loaded = catalog.rows(); } catch (BusinessException preparing) { Thread.sleep(10); }
            }
            assertThat(loaded).hasSize(101);
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(catalog, "failures")).isEqualTo(0);
        }
    }

    @Test
    void retryDelayDoublesFromOneMinuteUpToTheRegularSixHourRefresh() {
        assertThat(List.of(1, 2, 3, 9, 10, 50).stream().map(failures -> (Duration) org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(PillCatalog.class, "retryDelay", failures)).toList())
                .containsExactly(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4),
                        Duration.ofMinutes(256), Duration.ofHours(6), Duration.ofHours(6));
    }

    private static Duration waitForRetryAfter(PillCatalog catalog) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            var retryAfter = (java.time.Instant) org.springframework.test.util.ReflectionTestUtils.getField(catalog, "retryAfter");
            if (retryAfter.isAfter(java.time.Instant.now().plusSeconds(1))) return Duration.between(java.time.Instant.now(), retryAfter);
            Thread.sleep(10);
        }
        throw new AssertionError("refresh did not fail in time");
    }

    @Test
    void requestDoesNotWaitOnCatalogDownloadLock() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(client.get(anyString(), anyMap(), any(Duration.class))).thenAnswer(call -> {
            started.countDown(); release.await(5, TimeUnit.SECONDS);
            return json.valueToTree(Map.of("totalCount", 0, "items", List.of()));
        });
        try (var catalog = new PillCatalog(client, json, directory.resolve("new.json").toString(), false, 1);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> download = executor.submit(catalog::refresh);
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                        assertThatThrownBy(catalog::rows).isInstanceOfSatisfying(BusinessException.class,
                                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PILL_CATALOG_PREPARING)));
            } finally { release.countDown(); }
            download.get(2, TimeUnit.SECONDS);
        }
    }

    JsonNode row(String code, String name, String front, String back) {
        return json.valueToTree(Map.of("ITEM_SEQ", code, "ITEM_NAME", name, "ENTP_NAME", "합성제약", "ETC_OTC_NAME", "일반의약품", "PRINT_FRONT", front,
                "PRINT_BACK", back, "DRUG_SHAPE", "원형", "COLOR_CLASS1", "하양"));
    }
}

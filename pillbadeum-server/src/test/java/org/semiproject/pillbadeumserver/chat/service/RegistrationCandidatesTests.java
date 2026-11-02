package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;
import org.semiproject.pillbadeumserver.drug.service.DrugSearchService;
import org.semiproject.pillbadeumserver.image.service.PillCatalog;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V3-CHAT-002의 후보 찾기(V3-REG-001 검색·V3-REG-002 함량 상태)를 가짜 공공데이터로 검증한다. 저장은 하지 않는다. */
class RegistrationCandidatesTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final PublicDataClient data = mock(PublicDataClient.class);
    private final PillCatalog catalog = mock(PillCatalog.class);
    private SimpleAsyncTaskExecutor executor;
    private RegistrationCandidates candidates;

    @BeforeEach
    void setup() {
        executor = new SimpleAsyncTaskExecutor();
        executor.setVirtualThreads(true);
        candidates = new RegistrationCandidates(data, catalog, executor);
        when(catalog.cachedImageUrls(any())).thenReturn(Map.of());
        when(data.get(eq(DrugSearchService.SEARCH_PATH), anyMap(), any(Duration.class)))
                .thenReturn(json.valueToTree(Map.of("totalCount", 0, "items", List.of())));
    }

    @AfterEach
    void close() { executor.close(); }

    private static Map<String, Object> row(String code, String name) {
        return Map.of("ITEM_SEQ", code, "ITEM_NAME", name, "ENTP_NAME", "시험제약", "ETC_OTC_CODE", "일반의약품");
    }

    private void results(String query, List<Map<String, Object>> rows) {
        when(data.get(eq(DrugSearchService.SEARCH_PATH), argThat(params -> query.equals(params.get("item_name"))),
                any(Duration.class))).thenReturn(json.valueToTree(Map.of("totalCount", rows.size(), "items", rows)));
    }

    private List<List<RegistrationCandidates.Candidate>> find(String... mentions) {
        return candidates.find(List.of(mentions), ChatDeadline.after(Duration.ofSeconds(5)));
    }

    @Test
    void bareNumberIsReadAsMilligramsAndTheMatchingStrengthComesFirst() {
        assertThat(RegistrationCandidates.searchName(" 타이레놀  500 ")).isEqualTo("타이레놀 500mg");
        assertThat(RegistrationCandidates.searchName("타이레놀정500밀리그람")).isEqualTo("타이레놀정500밀리그람");
        assertThat(RegistrationCandidates.searchName("판콜에이")).isEqualTo("판콜에이");
        results("타이레놀", List.of(row("199900001", "타이레놀8시간이알서방정650밀리그람(아세트아미노펜)"),
                row("199900002", "타이레놀정500밀리그람(아세트아미노펜)"), row("199900003", "어린이타이레놀현탁액")));

        var found = find("타이레놀 500").getFirst();
        assertThat(found).extracting(c -> c.drug().itemSeq()).containsExactly("199900002", "199900003", "199900001");
        assertThat(found).extracting(RegistrationCandidates.Candidate::strength)
                .containsExactly(StrengthMatch.MATCH, StrengthMatch.UNKNOWN, StrengthMatch.DIFFERENT);
        assertThat(found).allSatisfy(c -> assertThat(c.mention()).isEqualTo("타이레놀 500"));
    }

    @Test
    void namesWithoutStrengthKeepTheOfficialOrderUpToFive() {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= 7; i++) rows.add(row("19990000" + i, "판콜에이내복액" + i));
        results("판콜에이", rows);
        var found = find("판콜에이").getFirst();
        assertThat(found).hasSize(RegistrationCandidates.PER_MENTION)
                .extracting(c -> c.drug().itemSeq()).startsWith("199900001", "199900002");
        assertThat(found).allSatisfy(c -> assertThat(c.strength()).isEqualTo(StrengthMatch.UNKNOWN));
    }

    @Test
    void spacedNameIsSearchedAgainWithoutSpacesAndIncompleteRowsAreSkipped() {
        results("판콜에이", List.of(Map.of("ITEM_SEQ", "199900009", "ITEM_NAME", "판콜에이내복액"),
                row("199900010", "판콜에이내복액")));
        var found = find("판콜 에이").getFirst();
        assertThat(found).extracting(c -> c.drug().itemSeq()).containsExactly("199900010");
    }

    @Test
    void namesAreSearchedSeparatelyAndAnUnknownNameHasNoCandidates() {
        results("가상약정", List.of(row("199900011", "가상약정10밀리그램")));
        var found = find("가상약정", "없는약");
        assertThat(found).hasSize(2);
        assertThat(found.get(0)).hasSize(1);
        assertThat(found.get(1)).isEmpty();
    }

    @Test
    void tooShortOrLongNamesAreNotSearched() {
        var found = find("약", "가".repeat(51));
        assertThat(found).allSatisfy(list -> assertThat(list).isEmpty());
        verify(data, never()).get(any(), anyMap(), any(Duration.class));
    }

    @Test
    void publicDataFailureIsNotHiddenAsNoCandidates() {
        when(data.get(eq(DrugSearchService.SEARCH_PATH), anyMap(), any(Duration.class)))
                .thenThrow(ExternalApiException.failed("공공데이터포털", "장애", null));
        assertThatThrownBy(() -> find("타이레놀 500")).isInstanceOf(ExternalApiException.class);
    }
}

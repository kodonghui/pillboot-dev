package org.semiproject.pillbadeumserver.drug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.domain.Drug;
import org.semiproject.pillbadeumserver.drug.domain.DrugStrength;
import org.semiproject.pillbadeumserver.drug.domain.MyDrugRepository;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;
import org.semiproject.pillbadeumserver.drug.dto.DrugInfoResponse;
import org.semiproject.pillbadeumserver.drug.dto.DrugSearchResponse;
import org.semiproject.pillbadeumserver.drug.service.DrugSearchService;
import org.semiproject.pillbadeumserver.drug.service.ProfileAccessChecker;
import org.semiproject.pillbadeumserver.image.service.PillCatalog;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V3-DRUG-001·004, V3-REG-001: 품목명에서 함량을 떼고, 검색어 함량과 같은 후보를 앞에 두는지 확인함. */
class DrugStrengthTests {

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "-", value = {
        // 품목명                                | 기본 이름                 | 원문           | μg      | 단위
        "알프람정 10mg                           | 알프람정                  | 10mg          | 10000   | mg",
        "알프람정0.25밀리그램(알프라졸람)          | 알프람정                  | 0.25밀리그램    | 250     | mg",
        "타이레놀8시간이알서방정650밀리그람        | 타이레놀8시간이알서방정     | 650밀리그람     | 650000  | mg",
        "합성정1g                               | 합성정                    | 1g            | 1000000 | g",
        "합성정500mcg                           | 합성정                    | 500mcg        | 500     | μg",
        "○○정 500/125mg                         | ○○정                     | 500/125mg     | -       | -",
        "아모잘탄정5/50밀리그램                   | 아모잘탄정                 | 5/50밀리그램    | -       | -",
        "합성시럽100mg/5mL                       | 합성시럽                  | 100mg/5mL     | -       | -",
        "후시딘연고2%(퓨시드산나트륨)              | 후시딘연고                 | 2%            | -       | -",
        "인슐린주100단위/mL                       | 인슐린주                  | 100단위/mL     | -       | -",
        "게보린정(아세트아미노펜,카페인무수물)      | 게보린정                  | -             | -       | -",
        "판콜에이내복액                           | 판콜에이내복액              | -             | -       | -",
    })
    void separatesBaseNameAndStrengthFromItemName(String name, String base, String text, String micrograms, String unit) {
        var strength = DrugStrength.parse(name);

        assertThat(strength.baseName()).isEqualTo(base);
        assertThat(strength.text()).isEqualTo(text);
        if (micrograms == null) assertThat(strength.micrograms()).isNull();
        else assertThat(strength.micrograms()).isEqualByComparingTo(micrograms);
        assertThat(strength.unit()).isEqualTo(unit);
    }

    @Test
    void emptyNameHasNoStrengthAndNeverThrows() {
        assertThat(DrugStrength.parse(null)).isEqualTo(new DrugStrength(null, null, null, null));
        assertThat(DrugStrength.parse("  ")).isEqualTo(new DrugStrength(null, null, null, null));
        assertThat(DrugStrength.parse("10mg").baseName()).isEqualTo("10mg");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "합성정10mg       | 합성정10밀리그램         | MATCH",
        "합성정0.5mg      | 합성정500마이크로그램     | MATCH",
        "합성정10mg       | 합성정20mg             | DIFFERENT",
        "합성정500/125mg  | 합성정500/125밀리그램    | MATCH",
        "합성정500/125mg  | 합성정500/250mg        | DIFFERENT",
        "합성정10mg       | 합성시럽10mg/5mL        | DIFFERENT",
        "합성정          | 합성정10mg             | UNKNOWN",
        "합성정10mg       | 합성정                 | UNKNOWN",
    })
    void comparesStrengthByMicrogramsOrCanonicalText(String wanted, String candidate, StrengthMatch expected) {
        assertThat(DrugStrength.parse(wanted).matchOf(DrugStrength.parse(candidate))).isEqualTo(expected);
    }

    @Test
    void cachedDrugStoresStrengthWithoutChangingItemName() {
        var drug = Drug.create("202000001", "알프람정 10mg", "제약", Drug.ETC, "정신신경용제", null, null, null,
                "원형", "하양", null, null, "정제", "알프라졸람", LocalDateTime.of(2026, 11, 2, 15, 0));

        assertThat(drug.getName()).isEqualTo("알프람정 10mg");
        assertThat(drug.strength()).isEqualTo(new DrugStrength("알프람정", "10mg", new BigDecimal("1E+4"), "mg"));
        var response = DrugInfoResponse.from(drug);
        assertThat(response.name()).isEqualTo("알프람정 10mg");
        assertThat(response.baseName()).isEqualTo("알프람정");
        assertThat(response.strengthText()).isEqualTo("10mg");
    }

    @Test
    void searchResponseKeepsExistingFieldsAndAddsStrength() {
        var item = DrugSearchResponse.of(" 1 ", " 가스디알정50밀리그램(디메크로틴산마그네슘) ", "일동", null, false);

        assertThat(item.name()).isEqualTo("가스디알정50밀리그램(디메크로틴산마그네슘)");
        assertThat(item.baseName()).isEqualTo("가스디알정");
        assertThat(item.strengthText()).isEqualTo("50밀리그램");
        assertThat(item.strengthMatch()).isEqualTo(StrengthMatch.UNKNOWN);
        assertThat(item.withRegistered(true).strengthText()).isEqualTo("50밀리그램");
    }

    // ---- V3-REG-001 이름 검색 ----

    private final PublicDataClient client = mock(PublicDataClient.class);
    private final DrugSearchService search = new DrugSearchService(client, mock(MyDrugRepository.class),
            mock(ProfileAccessChecker.class), mock(PillCatalog.class));
    private final JsonMapper json = new JsonMapper();

    @Test
    void strengthQuerySearchesBaseNameAndPutsMatchingStrengthFirst() {
        var queries = recordQueries(Map.of("알프람정", List.of("알프람정0.25밀리그램(알프라졸람)",
                "알프람정0.5밀리그램(알프라졸람)", "알프람정10밀리그램(알프라졸람)")));

        var page = search.search("알프람정10mg", PageRequest.of(0, 10));

        assertThat(queries).containsExactly("알프람정");
        assertThat(page.getContent()).extracting(DrugSearchResponse::name).containsExactly(
                "알프람정10밀리그램(알프라졸람)", "알프람정0.25밀리그램(알프라졸람)", "알프람정0.5밀리그램(알프라졸람)");
        assertThat(page.getContent()).extracting(DrugSearchResponse::strengthMatch)
                .containsExactly(StrengthMatch.MATCH, StrengthMatch.DIFFERENT, StrengthMatch.DIFFERENT);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void emptyBaseNameResultRetriesTheOriginalKeywordOnce() {
        var queries = recordQueries(Map.of("합성정10mg", List.of("합성정10mg")));

        var page = search.search("합성정10mg", PageRequest.of(0, 10));

        assertThat(queries).containsExactly("합성정", "합성정10mg");
        assertThat(page.getContent()).singleElement()
                .satisfies(item -> assertThat(item.strengthMatch()).isEqualTo(StrengthMatch.MATCH));
    }

    @Test
    void shortBaseNameAndPlainNamesKeepTheExistingSearch() {
        var queries = recordQueries(Map.of("정10mg", List.of(), "타이레놀", List.of("타이레놀정500밀리그람")));

        search.search("정10mg", PageRequest.of(0, 10));
        var plain = search.search("타이레놀", PageRequest.of(0, 10));

        assertThat(queries).containsExactly("정10mg", "타이레놀");
        assertThat(plain.getContent()).singleElement()
                .satisfies(item -> assertThat(item.strengthMatch()).isEqualTo(StrengthMatch.UNKNOWN));
    }

    @Test
    void publicDataFailureIsNotHiddenAsAnEmptyResult() {
        when(client.get(anyString(), anyMap()))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_FAILED, "공공데이터 장애"));

        assertThatThrownBy(() -> search.search("알프람정10mg", PageRequest.of(0, 10)))
                .isInstanceOf(BusinessException.class);
    }

    private List<String> recordQueries(Map<String, List<String>> results) {
        var queries = new ArrayList<String>();
        when(client.get(anyString(), anyMap())).thenAnswer(call -> {
            Map<String, ?> params = call.getArgument(1);
            String query = (String) params.get("item_name");
            queries.add(query);
            return rows(results.getOrDefault(query, List.of()));
        });
        return queries;
    }

    private tools.jackson.databind.JsonNode rows(List<String> names) {
        var items = new ArrayList<Map<String, String>>();
        for (int i = 0; i < names.size(); i++) items.add(Map.of("ITEM_SEQ", "90000" + i, "ITEM_NAME", names.get(i)));
        return json.valueToTree(Map.of("items", items, "totalCount", names.size()));
    }
}

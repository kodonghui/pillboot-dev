package org.semiproject.pillbadeumserver.drug.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 이름이 꼭 맞는 품목이 없을 때 비슷한 이름 후보(최대 3개)를 찾는지 가상 이름으로 확인한다. */
class SimilarDrugNamesTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final PublicDataClient client = mock(PublicDataClient.class);

    private JsonNode row(String code, String name) {
        return json.valueToTree(Map.of("ITEM_SEQ", code, "ITEM_NAME", name));
    }

    @Test
    void queriesStartFromTheSecondLetterBecauseTheFirstLetterIsOftenMisread() {
        assertThat(SimilarDrugNames.queries(DrugNameSimilarity.key("유코스텐스캡슐20g"))).containsExactly("코스텐", "스텐스", "유코스");
        assertThat(SimilarDrugNames.queries(DrugNameSimilarity.key("룩펠정"))).containsExactly("룩펠");
    }

    @Test
    void theReadyPillListIsCheckedFirstWithoutCallingThePublicApi() {
        var catalog = List.of(row("199900031", "가상뮤코스텐캡슐200밀리그램(가상성분)"), row("199900032", "가상혈압정"),
                row("199900033", "가상뮤코스텐캡슐100밀리그램"), row("199900034", "가상유코스텐캡슐"));
        var found = new SimilarDrugNames(client, () -> catalog).find("가상유코스텐스캡슐", Duration.ofSeconds(3));
        assertThat(found).extracting(node -> node.path("ITEM_SEQ").asString())
                .containsExactlyInAnyOrder("199900031", "199900033", "199900034");
        verifyNoInteractions(client);
    }

    @Test
    void whenThePillListHasTooFewTheMiddleOfTheNameIsSearched() {
        when(client.get(eq(DrugSyncService.PERMIT_INFO_PATH), argThat(params -> "코스텐".equals(params.get("item_name"))),
                any(Duration.class))).thenReturn(json.valueToTree(Map.of("items", List.of(
                Map.of("ITEM_SEQ", "199900041", "ITEM_NAME", "뮤코스텐캡슐"), Map.of("ITEM_SEQ", "199900042", "ITEM_NAME", "코스텐다른정")))));
        when(client.get(eq(DrugSyncService.PERMIT_INFO_PATH), argThat(params -> !"코스텐".equals(params.get("item_name"))),
                any(Duration.class))).thenReturn(json.valueToTree(Map.of("items", List.of())));
        var found = new SimilarDrugNames(client, List::of).find("유코스텐스캡슐", Duration.ofSeconds(3));
        assertThat(found).extracting(node -> node.path("ITEM_SEQ").asString()).containsExactly("199900041");
    }
}

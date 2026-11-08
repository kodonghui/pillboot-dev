package org.semiproject.pillbadeumserver.drug.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import tools.jackson.databind.json.JsonMapper;

/** 9자리 보험코드 찾기를 가상 코드·가짜 공공데이터로 확인한다. 코드가 실제로 든 행만 쓰는지가 핵심이다. */
class InsuranceCodeLookupTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final PublicDataClient client = mock(PublicDataClient.class);

    @Test
    void theFirstNineDigitCodeIsReadEvenWithSpacesOrHyphensAndLongerNumbersAreNotCodes() {
        assertThat(InsuranceCodeLookup.extract("640000001 가상스텐캡슐 200mg")).isEqualTo("640000001");
        assertThat(InsuranceCodeLookup.extract("6400-00001 가상스텐캡슐")).isEqualTo("640000001");
        assertThat(InsuranceCodeLookup.extract("가상스텐캡슐 200mg")).isNull();
        assertThat(InsuranceCodeLookup.extract("6400000011")).isNull();
        assertThat(InsuranceCodeLookup.withoutCode("640000001 가상스텐캡슐  200mg")).isEqualTo("가상스텐캡슐 200mg");
    }

    @Test
    void onlyRowsWhoseEdiCodeFieldHoldsTheCodeAreKept() {
        when(client.get(eq(DrugSyncService.PERMIT_INFO_PATH), argThat(params -> "640000001".equals(params.get("edi_code"))),
                any(Duration.class))).thenReturn(json.valueToTree(Map.of("items", List.of(
                Map.of("ITEM_SEQ", "199900021", "ITEM_NAME", "가상스텐캡슐200밀리그램", "EDI_CODE", "640000001,640000002"),
                Map.of("ITEM_SEQ", "199900099", "ITEM_NAME", "다른가상정", "EDI_CODE", "650000009")))));
        var rows = new InsuranceCodeLookup(client).find("640000001", Duration.ofSeconds(3));
        assertThat(rows).singleElement().satisfies(row -> assertThat(row.path("ITEM_SEQ").asString()).isEqualTo("199900021"));
        assertThat(InsuranceCodeLookup.codes("640000001, 640000002 / 6400")).containsExactly("640000001", "640000002");
    }

    @Test
    void malformedCodesAreNotLookedUpAndOutagesAreNotHidden() {
        assertThat(new InsuranceCodeLookup(client).find("12345", Duration.ofSeconds(3))).isEmpty();
        verifyNoInteractions(client);
        when(client.get(eq(DrugSyncService.PERMIT_INFO_PATH), any(), any(Duration.class)))
                .thenThrow(ExternalApiException.failed("공공데이터포털", "장애", null));
        assertThatThrownBy(() -> new InsuranceCodeLookup(client).find("640000001", Duration.ofSeconds(3)))
                .isInstanceOf(ExternalApiException.class);
    }
}

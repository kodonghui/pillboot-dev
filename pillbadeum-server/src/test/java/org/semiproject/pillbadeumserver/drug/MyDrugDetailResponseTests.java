package org.semiproject.pillbadeumserver.drug;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugDetailResponse;

import static org.assertj.core.api.Assertions.assertThat;

/* 설명. 직접 입력한 약(drug 없음 · 일정 없음)도 상세 응답으로 바꿀 수 있어야 한다. (계획 문서 D14) */
class MyDrugDetailResponseTests {

    @Test
    void manuallyNamedMedicationDetailHasCustomNameAndNoDrugInfo() {
        var response = MyDrugDetailResponse.from(MyDrug.createCustom(17L, "혈압약"));

        assertThat(response.name()).isEqualTo("혈압약");
        assertThat(response.drug()).isNull();
        assertThat(response.source()).isEqualTo(DrugSource.MANUAL);
        assertThat(response.sourceName()).isEqualTo("직접 입력");
        assertThat(response.startDate()).isNull();
        assertThat(response.endDate()).isNull();
        assertThat(response.doseTimes()).isEmpty();
    }
}

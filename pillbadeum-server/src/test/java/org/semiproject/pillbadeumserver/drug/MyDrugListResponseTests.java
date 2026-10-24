package org.semiproject.pillbadeumserver.drug;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugListResponse;

import static org.assertj.core.api.Assertions.assertThat;

class MyDrugListResponseTests {
    @Test
    void aManualMedicationNeverReceivesAnOfficialPictureWithoutAnItemCode() {
        assertThat(MyDrugListResponse.from(MyDrug.createCustom(17L, "직접입력약"), "https://nedrug.mfds.go.kr/unused.jpg").imageUrl()).isNull();
    }
    @Test
    void manuallyNamedMedicationCanBeShownInTeammatesDrugListWithoutACatalogLink() {
        var response = MyDrugListResponse.from(MyDrug.createCustom(17L, "혈압약"));
        assertThat(response.itemSeq()).isNull();
        assertThat(response.name()).isEqualTo("혈압약");
        assertThat(response.maker()).isNull();
        assertThat(response.source()).isEqualTo(DrugSource.MANUAL);
        assertThat(response.doseTimes()).isEmpty();
    }
}

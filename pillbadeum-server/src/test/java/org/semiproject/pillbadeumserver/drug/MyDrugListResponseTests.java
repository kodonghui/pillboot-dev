package org.semiproject.pillbadeumserver.drug;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugListResponse;

import static org.assertj.core.api.Assertions.assertThat;

class MyDrugListResponseTests {
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

package org.semiproject.pillbadeumserver.drug.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;

/* 설명. 약목록 여러 개 삭제 요청 (POST /api/profiles/{profileId}/drugs/bulk-delete, V3-DRUG-003)
 *  ----------------------------------------------------------------------------------
 *  요청 예) { "myDrugIds": [35, 36] }
 *  - 형식만 검사한다. 빈 목록 · 51개 이상 · 빈 ID · 같은 ID 두 번이면 400 이고 아무것도 지우지 않는다.
 *  - 고른 약이 이 프로필에 모두 있는지는 MyDrugService.deleteMyDrugs 가 지우면서 확인한다.
 * */
public record MyDrugBulkDeleteRequest(

        @NotNull(message = "지울 약을 골라주세요.")
        @Size(min = 1, max = MAX_DRUGS, message = "한 번에 1~" + MAX_DRUGS + "개까지 지울 수 있습니다.")
        List<@NotNull(message = "지울 약 정보가 비어 있습니다.") Long> myDrugIds
) {

    public static final int MAX_DRUGS = 50;

    @JsonIgnore
    @AssertTrue(message = "같은 약을 두 번 고를 수 없습니다.")
    public boolean isDistinct() {
        return myDrugIds == null || new HashSet<>(myDrugIds).size() == myDrugIds.size();
    }
}

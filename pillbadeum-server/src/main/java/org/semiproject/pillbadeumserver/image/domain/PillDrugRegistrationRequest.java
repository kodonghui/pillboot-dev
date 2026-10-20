package org.semiproject.pillbadeumserver.image.domain;

import org.semiproject.pillbadeumserver.drug.domain.DrugSource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 검색 결과에서 선택한 약과 복용 정보를 내 약 목록에 등록하기 위한 요청 */
public record PillDrugRegistrationRequest(
        String itemSeq,
        DrugSource source,
        Integer dosePerTime,
        String timing,
        LocalDate startDate,
        LocalDate endDate,
        List<LocalTime> doseTimes
) {
}

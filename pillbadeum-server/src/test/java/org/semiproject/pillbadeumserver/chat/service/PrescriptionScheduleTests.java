package org.semiproject.pillbadeumserver.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleRequest;

/** 확정 때 '처방대로 일정'으로 넣는 값. 가상 처방 값만 쓴다. */
class PrescriptionScheduleTests {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    private static ChatProposalResponse.Prescription rx(String dose, Integer daily, Integer days) {
        return new ChatProposalResponse.Prescription(dose, daily, days, false);
    }

    // QA 2026-11-08: 시럽 '1회 20mL'는 1회 개수에 넣을 수 없어 1회 1개와 복용 때 칸으로 넣는다(약 여러 개 일정의 1회 양과 같음).
    @Test
    void wholeDosesBecomeTheCountAndSyrupAmountsGoToTheTiming() {
        assertThat(ChatProposalStore.prescriptionSchedule(rx("1", 3, 5), TODAY))
                .isEqualTo(new ScheduleRequest(TODAY, TODAY.plusDays(4), MyDrug.DEFAULT_DOSE_TIMES, 1, null));
        assertThat(ChatProposalStore.prescriptionSchedule(rx("20mL", 2, 3), TODAY))
                .isEqualTo(new ScheduleRequest(TODAY, TODAY.plusDays(2), MyDrug.DEFAULT_DOSE_TIMES.subList(0, 2), 1, "1회 20mL"));
        assertThat(ChatProposalStore.prescriptionSchedule(rx("0.5", 3, 5), TODAY)).as("반 알").isNull();
        assertThat(ChatProposalStore.prescriptionSchedule(rx("20", 3, 5), TODAY)).as("단위 없는 20은 1회 20개라 규칙 밖").isNull();
        assertThat(ChatProposalStore.prescriptionSchedule(null, TODAY)).isNull();
    }
}

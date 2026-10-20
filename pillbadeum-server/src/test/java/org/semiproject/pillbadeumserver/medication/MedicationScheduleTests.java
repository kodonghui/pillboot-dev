package org.semiproject.pillbadeumserver.medication;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.drug.domain.Drug;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleRequest;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 일정 저장이 MyDrug 규칙을 그대로 따르는지 DB 없이 확인한다. */
class MedicationScheduleTests {
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 10, 31);

    @Test
    void 일정_요청은_MyDrug에_전체_교체로_반영된다() {
        MyDrug myDrug = myDrug("식후 30분");

        new ScheduleRequest(START, END, times(LocalTime.of(21, 0), LocalTime.of(8, 30, 15)), 2, null)
                .applyTo(myDrug);

        assertThat(myDrug.getStartDate()).isEqualTo(START);
        assertThat(myDrug.getEndDate()).isEqualTo(END);
        assertThat(myDrug.getDoseTimes()).containsExactly(LocalTime.of(8, 30), LocalTime.of(21, 0));
        assertThat(myDrug.getDosePerTime()).isEqualTo(2);
        assertThat(myDrug.getTiming()).isNull();
    }

    @Test
    void 규칙에_어긋나면_MyDrug가_거절하고_기존_값을_지킨다() {
        MyDrug myDrug = myDrug("식전");

        assertThrows(BusinessException.class, () ->
                new ScheduleRequest(END, START, times(LocalTime.of(9, 0)), 1, null).applyTo(myDrug));
        assertThrows(BusinessException.class, () ->
                new ScheduleRequest(START, END, times(LocalTime.of(9, 0), LocalTime.of(9, 0, 30)), 1, null)
                        .applyTo(myDrug));

        assertThat(myDrug.getDoseTimes()).isEqualTo(MyDrug.DEFAULT_DOSE_TIMES);
        assertThat(myDrug.getTiming()).isEqualTo("식전");
    }

    @Test
    void 조회_결과는_저장된_시각과_기간을_그대로_돌려준다() {
        MedicationTarget target = new MedicationTarget(1L, 2L, "엄마", "가족", "타이레놀", 1, null,
                START, END, "07:00,11:00,17:00", LocalDateTime.of(2026, 10, 1, 9, 0));

        assertThat(ScheduleResponse.from(target, true).doseTimes()).containsExactly("07:00", "11:00", "17:00");
        assertThat(target.includes(START)).isTrue();
        assertThat(target.includes(END)).isTrue();
        assertThat(target.includes(END.plusDays(1))).isFalse();
    }

    @Test
    void 일정_없는_약은_날짜에_포함되지_않고_조회_응답도_안전하게_만든다() {
        MedicationTarget target = new MedicationTarget(1L, 2L, "엄마", "가족", "타이레놀", 1, "식전",
                null, null, null, LocalDateTime.of(2026, 10, 1, 9, 0));

        ScheduleResponse response = ScheduleResponse.from(target, false);
        assertThat(response.hasSchedule()).isFalse();
        assertThat(response.startDate()).isNull();
        assertThat(response.endDate()).isNull();
        assertThat(response.doseTimes()).isEmpty();
        assertThat(target.includes(START)).isFalse();
    }

    @Test
    void 일정_삭제_후_복용정보만_수정해도_일정은_없는_상태로_유지된다() {
        MyDrug myDrug = myDrug("식전");
        myDrug.clearSchedule();
        myDrug.changeDoseInfo(2, "식후", null, null, null);

        assertThat(myDrug.hasSchedule()).isFalse();
        assertThat(myDrug.getDoseTimes()).isEmpty();
        assertThat(myDrug.getDosePerTime()).isEqualTo(2);
        assertThat(myDrug.getTiming()).isEqualTo("식후");
        assertThrows(BusinessException.class, () -> myDrug.changeDoseInfo(null, null, START, null, null));
        assertThat(myDrug.getStartDate()).isNull();
    }

    @Test
    void 기간_없이_약을_등록하거나_기간과_기본시각으로_등록할_수_있다() {
        Drug drug = myDrug(null).getDrug();
        MyDrug unscheduled = MyDrug.create(2L, drug, DrugSource.SEARCH, 1, null, null, null, null);
        assertThat(unscheduled.hasSchedule()).isFalse();
        assertThat(unscheduled.getDoseTimes()).isEmpty();

        MyDrug scheduled = MyDrug.create(2L, drug, DrugSource.SEARCH, 1, null, START, END, null);
        assertThat(scheduled.hasSchedule()).isTrue();
        assertThat(scheduled.getDoseTimes()).isEqualTo(MyDrug.DEFAULT_DOSE_TIMES);
        assertThrows(BusinessException.class, () ->
                MyDrug.create(2L, drug, DrugSource.SEARCH, 1, null, START, null, null));
    }

    private static MyDrug myDrug(String timing) {
        Drug drug = Drug.create("200000001", "테스트정", "테스트제약", Drug.OTC, "해열진통제", null, null, null,
                "원형", "하양", null, null, "정제", "필름코팅", LocalDateTime.of(2026, 10, 1, 0, 0));
        return MyDrug.create(2L, drug, DrugSource.SEARCH, 1, timing, START, END, times(MyDrug.DEFAULT_DOSE_TIMES.toArray(LocalTime[]::new)));
    }

    /** null 시각 등 잘못된 입력도 담아 검증할 수 있는 목록. */
    private static List<LocalTime> times(LocalTime... values) {
        return Arrays.asList(values);
    }
}

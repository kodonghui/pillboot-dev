package org.semiproject.pillbadeumserver.medication.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class MedicationDtos {
    private MedicationDtos() {}

    /** 일정 전체 교체 요청. 입력 형식만 먼저 거르고 기간·시각 규칙은 MyDrug가 검사한다. */
    public record ScheduleRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate,
                                  @NotNull @Size(min = 1, max = MyDrug.MAX_DOSE_TIMES) List<@NotNull LocalTime> doseTimes,
                                  @NotNull @Min(1) @Max(10) Integer dosePerTime,
                                  @Size(max = 20) String timing) {
        /** 전체 교체라 timing을 비워 보내면 기존 값도 지운다. (MyDrug는 null을 '그대로 둠'으로 본다) */
        public void applyTo(MyDrug myDrug) {
            // HTTP 외부에서 호출해도 PUT의 필수값·개수 제한을 지킨다.
            if (startDate == null || endDate == null || doseTimes == null || dosePerTime == null
                    || dosePerTime < 1 || dosePerTime > 10 || timing != null && timing.length() > 20) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "기간·복용 시간·1회 개수(1~10)·복용 때(20자 이내)를 확인해주세요.");
            }
            myDrug.changeDoseInfo(dosePerTime, timing == null ? "" : timing, startDate, endDate, doseTimes);
        }
    }

    public record ScheduleResponse(long myDrugId, long profileId, String profileName, String drugName,
                                   LocalDate startDate, LocalDate endDate, List<String> doseTimes,
                                   Integer dosePerTime, String timing, boolean pushEnabled, boolean hasSchedule) {
        public static ScheduleResponse from(MedicationTarget target, boolean pushEnabled) {
            return new ScheduleResponse(target.myDrugId(), target.profileId(), target.profileName(), target.drugName(),
                    target.startDate(), target.endDate(),
                    target.doseTimes().stream().map(t -> t.format(DateTimeFormatter.ofPattern("HH:mm"))).toList(),
                    target.confirmedDosePerTime(), target.timing(), pushEnabled, target.hasSchedule());
        }

        /** JPA로 바꾼 값을 바로 응답한다. JDBC 재조회에 아직 flush되지 않은 값이 섞이는 일을 피한다. */
        public static ScheduleResponse from(MyDrug myDrug, MedicationTarget target, boolean pushEnabled) {
            return new ScheduleResponse(myDrug.getMyDrugId(), myDrug.getProfileId(), target.profileName(), target.drugName(),
                    myDrug.getStartDate(), myDrug.getEndDate(),
                    myDrug.getDoseTimes().stream().map(t -> t.format(DateTimeFormatter.ofPattern("HH:mm"))).toList(),
                    myDrug.getConfirmedDosePerTime(), myDrug.getTiming(), pushEnabled, myDrug.hasSchedule());
        }
    }

    public record DrugChoices(List<ScheduleResponse> drugs) {}
    /** 조회 범위와 겹치는 약별 일정. 각 일정의 원래 기간을 유지하고 날짜별 행은 만들지 않는다. */
    public record CalendarResponse(LocalDate from, LocalDate to, List<ScheduleResponse> schedules) {}
    public record DoseItem(long myDrugId, long profileId, String profileName, String drugName,
                           String time, int dosePerTime, String timing) {}
    public record DoseResponse(LocalDate date, String time, List<DoseItem> doses) {}
    public record ProfileChoice(long profileId, String profileName, String relationship) {}
}

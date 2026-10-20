package org.semiproject.pillbadeumserver.medication.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 다른 담당자의 Profile/Drug 엔티티를 중복 정의하지 않는 일정 전용 조회 결과.
 * 일정 없는 약은 기간·복용 시각이 모두 NULL이다. 검사 규칙은 MyDrug에 있다.
 */
public record MedicationTarget(long myDrugId, long profileId, String profileName, String relationship,
                               String drugName, int dosePerTime, String timing, LocalDate startDate,
                               LocalDate endDate, String storedDoseTimes, LocalDateTime updatedAt, boolean doseConfirmed) {
    public MedicationTarget(long myDrugId, long profileId, String profileName, String relationship,
                            String drugName, int dosePerTime, String timing, LocalDate startDate,
                            LocalDate endDate, String storedDoseTimes, LocalDateTime updatedAt) {
        this(myDrugId, profileId, profileName, relationship, drugName, dosePerTime, timing, startDate, endDate, storedDoseTimes, updatedAt, true);
    }

    public Integer confirmedDosePerTime() { return doseConfirmed ? dosePerTime : null; }
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public boolean hasSchedule() {
        return startDate != null && endDate != null && storedDoseTimes != null;
    }

    public List<LocalTime> doseTimes() {
        if (!hasSchedule()) return List.of();
        return Arrays.stream(storedDoseTimes.split(",")).map(String::strip)
                .map(t -> LocalTime.parse(t, TIME_FORMAT)).toList();
    }

    public boolean includes(LocalDate date) {
        return hasSchedule() && !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}

package org.semiproject.pillbadeumserver.medication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class MedicationLiveDtos {
    private MedicationLiveDtos() {}
    public enum Operation { SAVE, DELETE, STOP }
    public record CompletedOperation(Operation action, long profileId, long myDrugId) {}

    public record StartRequest(@NotBlank @Size(max = 100000) String sdp,
                               @NotNull @Positive Long selectedProfileId,
                               @Positive Long selectedMyDrugId,
                               @NotNull LocalDate selectedDate) {}

    public record StartResponse(String sessionId, String sdp, Instant expiresAt) {}

    /** 사용자가 말한 항목만 들어간다. 매 호출은 누적 대화를 다시 해석하므로 정정·취소도 반영한다. */
    public record Plan(String intent, Long myDrugId, String startDate, String endDate, Integer durationDays,
                       List<String> doseTimes, Integer dailyCount, Integer dosePerTime, String timing,
                       boolean useDefaults, String defaultEvidence, List<String> dosePeriods,
                       String profileHint, List<String> drugHints, String actionEvidence,
                       Long profileId, String customName) {
        public Plan(String intent, Long myDrugId, String startDate, String endDate, Integer durationDays,
                    List<String> doseTimes, Integer dailyCount, Integer dosePerTime, String timing,
                    boolean useDefaults, String defaultEvidence, List<String> dosePeriods,
                    String profileHint, List<String> drugHints, String actionEvidence) {
            this(intent, myDrugId, startDate, endDate, durationDays, doseTimes, dailyCount, dosePerTime,
                    timing, useDefaults, defaultEvidence, dosePeriods, profileHint, drugHints, actionEvidence, null, null);
        }
        public Plan(String intent, Long myDrugId, String startDate, String endDate, Integer durationDays,
                    List<String> doseTimes, Integer dailyCount, Integer dosePerTime, String timing,
                    boolean useDefaults, String defaultEvidence) {
            this(intent, myDrugId, startDate, endDate, durationDays, doseTimes, dailyCount, dosePerTime,
                    timing, useDefaults, defaultEvidence, null, null, null, null, null, null);
        }
        public Plan(String intent, Long myDrugId, String startDate, String endDate, Integer durationDays,
                    List<String> doseTimes, Integer dailyCount, Integer dosePerTime, String timing,
                    boolean useDefaults, String defaultEvidence, List<String> dosePeriods) {
            this(intent, myDrugId, startDate, endDate, durationDays, doseTimes, dailyCount, dosePerTime,
                    timing, useDefaults, defaultEvidence, dosePeriods, null, null, null, null, null);
        }
    }

    public record Progress(String sessionId, String status, String message, List<String> missingFields,
                           List<String> appliedDefaults, ScheduleResponse savedSchedule, Instant expiresAt,
                           CompletedOperation completedOperation) {
        public Progress(String sessionId, String status, String message, List<String> missingFields,
                        List<String> appliedDefaults, ScheduleResponse savedSchedule, Instant expiresAt) {
            this(sessionId, status, message, missingFields, appliedDefaults, savedSchedule, expiresAt,
                    savedSchedule == null ? null : new CompletedOperation(Operation.SAVE, savedSchedule.profileId(), savedSchedule.myDrugId()));
        }
    }

    public record Outcome(String message, List<String> missingFields, List<String> appliedDefaults,
                          ScheduleResponse savedSchedule, CompletedOperation completedOperation) {
        public Outcome(String message, List<String> missingFields, List<String> appliedDefaults, ScheduleResponse savedSchedule) {
            this(message, missingFields, appliedDefaults, savedSchedule,
                    savedSchedule == null ? null : new CompletedOperation(Operation.SAVE, savedSchedule.profileId(), savedSchedule.myDrugId()));
        }
    }
}

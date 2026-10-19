package org.semiproject.pillbadeumserver.medication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;

import java.time.LocalDate;
import java.util.List;

public final class MedicationVoiceDtos {
    private MedicationVoiceDtos() {}

    /** 브라우저 STT의 최종 문장과 현재 화면 선택값. 원본 오디오는 서버에 보내지 않는다. */
    public record CommandRequest(@NotBlank @Size(max = 1000) String text,
                                 @NotNull @Positive Long selectedProfileId,
                                 @NotNull @Positive Long selectedMyDrugId,
                                 @NotNull LocalDate selectedDate) {}

    /** AI는 언급된 값만 추출한다. 기본값·권한·업무 검사는 서버가 처리한다. */
    public record ParsedCommand(String intent, Long myDrugId, String startDate, String endDate,
                                Integer durationDays, List<String> doseTimes, Integer dailyCount,
                                Integer dosePerTime, String timing) {}

    public enum AudioStatus { READY, UNAVAILABLE }

    public record CommandResponse(ScheduleResponse schedule, String message, List<String> appliedDefaults,
                                   AudioStatus audioStatus, String audioBase64, String audioContentType,
                                   boolean aiGeneratedVoice) {}
}

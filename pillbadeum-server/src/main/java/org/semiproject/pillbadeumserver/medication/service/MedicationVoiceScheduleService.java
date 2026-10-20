package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleRequest;
import org.semiproject.pillbadeumserver.medication.dto.MedicationVoiceDtos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 외부 AI 호출 중 DB 트랜잭션·행 잠금을 유지하지 않는다. 저장은 기존 일정 서비스에 위임한다. */
@Service
public class MedicationVoiceScheduleService {
    private static final Logger log = LoggerFactory.getLogger(MedicationVoiceScheduleService.class);
    private final MedicationRepository reads;
    private final MedicationVoiceInterpreter interpreter;
    private final MedicationScheduleService schedules;
    private final MedicationSpeechClient speech;
    private final Clock clock;

    @Autowired
    public MedicationVoiceScheduleService(MedicationRepository reads, MedicationVoiceInterpreter interpreter,
                                          MedicationScheduleService schedules, MedicationSpeechClient speech) {
        this(reads, interpreter, schedules, speech, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    MedicationVoiceScheduleService(MedicationRepository reads, MedicationVoiceInterpreter interpreter,
                                   MedicationScheduleService schedules, MedicationSpeechClient speech, Clock clock) {
        this.reads = reads;
        this.interpreter = interpreter;
        this.schedules = schedules;
        this.speech = speech;
        this.clock = clock;
    }

    public CommandResponse execute(long memberId, CommandRequest request) {
        if (request == null || request.text() == null || request.text().isBlank() || request.text().length() > 1000
                || request.selectedProfileId() == null || request.selectedMyDrugId() == null || request.selectedDate() == null
                || request.selectedProfileId() <= 0 || request.selectedMyDrugId() <= 0) throw invalid("현재 선택한 약·날짜와 명령 문장을 확인해주세요.");
        var candidates = reads.allDrugs(memberId);
        // 호출자가 고른 약도 반드시 본인 프로필에 속해야 한다. AI 호출 전에 검사한다.
        candidates.stream().filter(target -> target.profileId() == request.selectedProfileId()
                        && target.myDrugId() == request.selectedMyDrugId()).findFirst()
                .orElseThrow(() -> new NotFoundException("조회할 수 있는 내 약이 없습니다."));
        var parsed = interpreter.parse(request, LocalDate.now(clock), candidates);
        if (parsed == null || !"CREATE_SCHEDULE".equals(parsed.intent())) {
            throw invalid("등록할 프로필·약을 찾을 수 없거나 지원하지 않는 명령입니다.");
        }
        long drugId = parsed.myDrugId() == null ? request.selectedMyDrugId() : parsed.myDrugId();
        var target = candidates.stream().filter(choice -> choice.myDrugId() == drugId).findFirst()
                .orElseThrow(() -> new NotFoundException("조회할 수 있는 내 약이 없습니다."));
        var defaults = new ArrayList<String>();
        if (parsed.myDrugId() == null) defaults.add("myDrugId");
        ScheduleRequest scheduleRequest = toSchedule(request, parsed, target.dosePerTime(), target.timing(), defaults);

        // 별도 빈의 트랜잭션에서 소유권 재검사·검증·저장 후 커밋한다. 음성 실패로 저장을 되돌리지 않는다.
        var saved = schedules.save(memberId, target.profileId(), target.myDrugId(), scheduleRequest);
        String message = saved.profileName() + "님의 약 일정을 저장했습니다.";
        try {
            byte[] audio = speech.speak(message);
            if (audio == null || audio.length == 0) throw new IllegalStateException("빈 음성 응답");
            return new CommandResponse(saved, message, List.copyOf(defaults), AudioStatus.READY,
                    Base64.getEncoder().encodeToString(audio), "audio/mpeg", true);
        } catch (RuntimeException e) {
            log.info("[일정 완료 음성 생략] type={}", e.getClass().getSimpleName());
            return new CommandResponse(saved, message, List.copyOf(defaults), AudioStatus.UNAVAILABLE, null, null, false);
        }
    }

    private ScheduleRequest toSchedule(CommandRequest request, ParsedCommand parsed, int existingDose, String existingTiming,
                                        List<String> defaults) {
        try {
            LocalDate start = parsed.startDate() == null ? request.selectedDate() : LocalDate.parse(parsed.startDate());
            if (parsed.startDate() == null) defaults.add("startDate");
            if (parsed.durationDays() != null && parsed.durationDays() < 1) throw invalid("기간은 하루 이상이어야 합니다.");
            LocalDate calculatedEnd = parsed.durationDays() == null ? start : start.plusDays(parsed.durationDays() - 1L);
            LocalDate end = parsed.endDate() == null ? calculatedEnd : LocalDate.parse(parsed.endDate());
            if (parsed.endDate() == null && parsed.durationDays() == null) defaults.add("endDate");
            if (parsed.endDate() != null && parsed.durationDays() != null && !end.equals(calculatedEnd)) {
                throw invalid("종료일과 복용 기간이 서로 맞지 않습니다.");
            }
            if (start.isAfter(end)) throw invalid("시작일은 종료일보다 늦을 수 없습니다.");
            if (parsed.dailyCount() != null && (parsed.dailyCount() < 1 || parsed.dailyCount() > 3)) {
                throw invalid("복용 시각은 하루 1~3개여야 합니다.");
            }
            List<LocalTime> times;
            if (parsed.doseTimes() == null) {
                int count = parsed.dailyCount() == null ? 3 : parsed.dailyCount();
                times = MyDrug.DEFAULT_DOSE_TIMES.subList(0, count);
                defaults.add("doseTimes");
            } else {
                if (parsed.doseTimes().isEmpty() || parsed.doseTimes().size() > 3
                        || parsed.dailyCount() != null && parsed.dailyCount() != parsed.doseTimes().size()) {
                    throw invalid("복용 횟수와 시각을 확인해주세요.");
                }
                times = parsed.doseTimes().stream().map(value -> {
                    if (value == null || !value.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) throw invalid("복용 시각을 확인해주세요.");
                    return LocalTime.parse(value);
                }).toList();
                if (times.stream().distinct().count() != times.size()) throw invalid("복용 시각은 중복될 수 없습니다.");
            }
            int dose = parsed.dosePerTime() == null ? existingDose : parsed.dosePerTime();
            String timing = parsed.timing() == null ? existingTiming : parsed.timing();
            if (parsed.dosePerTime() == null) defaults.add("dosePerTime");
            if (parsed.timing() == null) defaults.add("timing");
            if (dose < 1 || dose > 10 || timing != null && timing.length() > 20) throw invalid("1회 개수·복용 때를 확인해주세요.");
            return new ScheduleRequest(start, end, times, dose, timing);
        } catch (DateTimeException e) {
            throw invalid("날짜와 기간을 확인해주세요.");
        }
    }

    private BusinessException invalid(String detail) {
        return new BusinessException(ErrorCode.INVALID_INPUT, detail);
    }
}

package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleRequest;
import org.semiproject.pillbadeumserver.medication.dto.MedicationLiveDtos.*;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 필수 정보와 기본값 위임 의사를 서버에서 검사한다. prepare 단계에서는 저장하지 않는다. */
@Service
public class MedicationLiveWorkflow {
    public record Decision(long profileId, long myDrugId, ScheduleRequest request, Outcome outcome) {}
    private final MedicationRepository reads;
    private final MedicationLivePlanner planner;
    private final MedicationScheduleService schedules;

    public MedicationLiveWorkflow(MedicationRepository reads, MedicationLivePlanner planner, MedicationScheduleService schedules) {
        this.reads = reads;
        this.planner = planner;
        this.schedules = schedules;
    }

    public void requireReady() { planner.requireReady(); }

    public Decision prepare(long memberId, StartRequest selection, LocalDate today, List<Map<String, Object>> transcript) {
        var candidates = reads.allDrugs(memberId);
        var plan = planner.plan(selection, today, candidates, transcript);
        if ("CANCEL".equals(plan.intent())) return question("이번 요청으로는 추가 저장하지 않을게요. 이미 저장된 일정은 그대로예요.", List.of());
        if ("TARGET_NOT_FOUND".equals(plan.intent())) return question("어느 프로필의 어떤 약을 등록할까요? 등록된 약 중에서 알려주세요.", List.of("target"));
        if (!"SAVE".equals(plan.intent())) return question("선택한 약의 복용 일정을 어떻게 등록할까요?", List.of("request"));
        long drugId = plan.myDrugId() == null ? selection.selectedMyDrugId() : plan.myDrugId();
        var target = candidates.stream().filter(choice -> choice.myDrugId() == drugId).findFirst();
        if (target.isEmpty()) return question("어느 프로필의 어떤 약을 등록할까요? 등록된 약 중에서 알려주세요.", List.of("target"));

        boolean allowDefaults = defaultsAllowed(plan, transcript);
        var missing = new ArrayList<String>();
        var defaults = new ArrayList<String>();
        try {
            LocalDate start = plan.startDate() == null ? selection.selectedDate() : LocalDate.parse(plan.startDate());
            if (plan.durationDays() != null && plan.durationDays() < 1) throw invalid();
            LocalDate end = plan.endDate() == null ? null : LocalDate.parse(plan.endDate());
            if (plan.durationDays() != null) {
                LocalDate calculated = start.plusDays(plan.durationDays() - 1L);
                if (end != null && !end.equals(calculated)) throw invalid();
                end = calculated;
            }
            if (end == null) {
                if (allowDefaults) { end = start; defaults.add("endDate"); }
                else missing.add("endDate");
            }
            if (end != null && start.isAfter(end)) throw invalid();
            if (plan.dailyCount() != null && (plan.dailyCount() < 1 || plan.dailyCount() > 3)) throw invalid();
            List<LocalTime> times = null;
            if (plan.doseTimes() != null) {
                if (plan.doseTimes().isEmpty() || plan.doseTimes().size() > 3
                        || plan.dailyCount() != null && plan.dailyCount() != plan.doseTimes().size()) throw invalid();
                times = plan.doseTimes().stream().map(value -> {
                    if (value == null || !value.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) throw invalid();
                    return LocalTime.parse(value);
                }).sorted().toList();
                if (times.stream().distinct().count() != times.size()) throw invalid();
            } else if (allowDefaults) {
                times = MyDrug.DEFAULT_DOSE_TIMES.subList(0, plan.dailyCount() == null ? 3 : plan.dailyCount());
                defaults.add("doseTimes");
            } else missing.add("doseTimes");
            Integer dose = plan.dosePerTime();
            if (dose == null) {
                if (allowDefaults) { dose = target.get().dosePerTime(); defaults.add("dosePerTime"); }
                else missing.add("dosePerTime");
            }
            if (dose != null && (dose < 1 || dose > 10)) throw invalid();
            String timing = plan.timing();
            if (timing == null && allowDefaults) { timing = target.get().timing(); defaults.add("timing"); }
            if (timing != null && timing.length() > 20) throw invalid();
            if (!missing.isEmpty()) {
                String prompt = switch (missing.getFirst()) {
                    case "endDate" -> "선택한 날짜부터 며칠 동안 등록할까요? 종료일로 알려주셔도 돼요.";
                    case "doseTimes" -> "몇 시에 복용하는 일정으로 등록할까요? 구체적인 시각을 알려주세요.";
                    default -> "한 번에 몇 개씩 복용하는 일정인가요?";
                };
                String lastUser = lastTurn(transcript, "user");
                if (plan.useDefaults() || lastUser.matches("(?s).*(몰라|모르겠).*")) prompt = "빠진 항목을 기본값으로 채워 저장할까요?";
                return question(prompt, List.copyOf(missing));
            }
            return new Decision(target.get().profileId(), drugId, new ScheduleRequest(start, end, times, dose, timing),
                    new Outcome("저장 항목을 확인했습니다.", List.of(), List.copyOf(defaults), null));
        } catch (DateTimeException e) {
            throw invalid();
        }
    }

    public Outcome save(long memberId, Decision decision) {
        var saved = schedules.save(memberId, decision.profileId(), decision.myDrugId(), decision.request());
        String message = saved.profileName() + "님의 약 일정을 " + saved.startDate() + "부터 " + saved.endDate() + "까지 저장했습니다.";
        if (!decision.outcome().appliedDefaults().isEmpty()) message += " 빠진 항목에는 기본값을 적용했습니다.";
        return new Outcome(message, List.of(), decision.outcome().appliedDefaults(), saved);
    }

    private Decision question(String message, List<String> missing) {
        return new Decision(0, 0, null, new Outcome(message, missing, List.of(), null));
    }

    /** AI 플래그만으로 기본값을 허용하지 않는다. 실제 사용자 원문·직전 질문의 동의도 확인한다. */
    private boolean defaultsAllowed(Plan plan, List<Map<String, Object>> transcript) {
        if (!plan.useDefaults() || plan.defaultEvidence() == null || plan.defaultEvidence().isBlank()) return false;
        String evidence = normalize(plan.defaultEvidence());
        String lastUser = normalize(lastTurn(transcript, "user"));
        if (lastUser.matches(".*(기본|알아서).*(쓰지마|하지마|말고|말아|싫어).*")) return false;
        StringBuilder allUser = new StringBuilder();
        for (var part : transcript) if ("user".equals(part.get("role"))) allUser.append(part.get("content"));
        if (!normalize(allUser.toString()).contains(evidence)) return false;
        if (evidence.matches(".*(알아서|기본값?|기본설정|그냥).*(저장|등록|해줘|해요|해).*")) return true;
        String priorAssistant = normalize(priorAssistant(transcript));
        return lastUser.matches("(네|예|응|좋아|그래|그렇게해줘|그렇게해|오케이|ㅇㅋ|맞아)")
                && evidence.equals(lastUser) && priorAssistant.contains("기본")
                && (priorAssistant.contains("저장") || priorAssistant.contains("등록"));
    }

    private String priorAssistant(List<Map<String, Object>> transcript) {
        int index = transcript.size() - 1;
        while (index >= 0 && !"user".equals(transcript.get(index).get("role"))) index--;
        while (index >= 0 && "user".equals(transcript.get(index).get("role"))) index--;
        var text = new StringBuilder();
        while (index >= 0 && "assistant".equals(transcript.get(index).get("role"))) {
            text.insert(0, transcript.get(index--).get("content"));
        }
        return text.toString();
    }

    private String lastTurn(List<Map<String, Object>> transcript, String role) {
        var result = new StringBuilder();
        boolean collecting = false;
        for (int i = transcript.size() - 1; i >= 0; i--) {
            var part = transcript.get(i);
            if (role.equals(part.get("role"))) { result.insert(0, part.get("content")); collecting = true; }
            else if (collecting) break;
        }
        return result.toString();
    }

    private String normalize(String value) { return value.replaceAll("[\\s\\p{Punct}]", ""); }
    private BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_INPUT, "기간·복용 시각·1회 개수를 다시 알려주세요."); }
}

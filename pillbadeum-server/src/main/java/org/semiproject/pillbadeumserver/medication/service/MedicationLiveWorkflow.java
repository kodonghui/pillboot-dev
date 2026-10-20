package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleRequest;
import org.semiproject.pillbadeumserver.medication.dto.MedicationLiveDtos.*;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 필수 정보와 기본값 위임 의사를 서버에서 검사한다. prepare 단계에서는 저장하지 않는다. */
@Service
public class MedicationLiveWorkflow {
    public record Proposal(long profileId, long myDrugId, ScheduleRequest request, List<String> defaults, String customName) {
        public Proposal(long profileId, long myDrugId, ScheduleRequest request, List<String> defaults) {
            this(profileId, myDrugId, request, defaults, null);
        }
    }
    // myDrugId=0은 저장 전 초안 내부에서만 사용한다. 완료 응답에는 DB가 발급한 ID만 반환한다.
    public record Decision(long profileId, long myDrugId, ScheduleRequest request, Outcome outcome, Proposal proposal, Operation operation, String customName) {
        public Decision(long profileId, long myDrugId, ScheduleRequest request, Outcome outcome, Proposal proposal, Operation operation) {
            this(profileId, myDrugId, request, outcome, proposal, operation, null);
        }
        public Decision(long profileId, long myDrugId, ScheduleRequest request, Outcome outcome) {
            this(profileId, myDrugId, request, outcome, null, Operation.SAVE);
        }
        public Decision(long profileId, long myDrugId, ScheduleRequest request, Outcome outcome, Proposal proposal) {
            this(profileId, myDrugId, request, outcome, proposal, Operation.SAVE);
        }
        public boolean executable() { return request != null || operation == Operation.DELETE || operation == Operation.STOP; }
    }
    private final MedicationLiveTargetResolver targets = new MedicationLiveTargetResolver();
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
        var profiles = reads.profiles(memberId);
        var plan = planner.plan(selection, today, candidates, profiles, transcript);
        if ("CANCEL".equals(plan.intent())) return question("이번 요청은 취소할게요. 기존 일정은 그대로예요.", List.of());
        String lastUser = normalize(lastTurn(transcript, "user"));
        if (lastUser.matches("(아니|아니요|싫어|싫어요|안돼|안돼요)")) {
            return question("그럼 바꿀 항목과 값만 말씀해주세요.", List.of("correction"));
        }
        if ("DELETE".equals(plan.intent()) || "STOP".equals(plan.intent())) {
            var resolution = targets.resolve(plan, selection, candidates, transcript);
            if (resolution.target() == null) return question(resolution.question(), List.of("target"));
            var target = resolution.target();
            return new Decision(target.profileId(), target.myDrugId(), null,
                    new Outcome("요청한 일정을 처리할게요.", List.of(), List.of(), null), null, Operation.valueOf(plan.intent()));
        }
        if ("TARGET_NOT_FOUND".equals(plan.intent())) return question("어느 프로필의 어떤 약을 등록할까요?", List.of("target"));
        if (!"SAVE".equals(plan.intent())) return question("약 이름과 기간을 말씀해주시면 나머지는 기본 설정으로 제안해드릴게요.", List.of("request"));
        String customName = plan.customName() == null ? null : plan.customName().strip();
        long profileId = plan.profileId() == null ? selection.selectedProfileId() : plan.profileId();
        Long selectedDrugId = plan.myDrugId() != null ? plan.myDrugId()
                : customName == null && profileId == selection.selectedProfileId() ? selection.selectedMyDrugId() : null;
        MedicationTarget target;
        if (selectedDrugId != null) {
            if (customName != null) throw invalid();
            target = candidates.stream().filter(choice -> choice.myDrugId() == selectedDrugId).findFirst().orElse(null);
            if (target == null || plan.profileId() != null && target.profileId() != profileId) {
                return question("어느 프로필의 어떤 약을 등록할까요?", List.of("target"));
            }
        } else {
            var profile = profiles.stream().filter(p -> p.profileId() == profileId).findFirst();
            if (profile.isEmpty()) return question("등록할 프로필을 다시 알려주세요.", List.of("target"));
            if (customName == null || customName.isBlank()) return question("어떤 약 일정인가요? 혈압약처럼 말씀해주셔도 돼요.", List.of("target"));
            if (customName.length() > 100 || !userText(transcript).contains(normalize(customName))) {
                return question("등록할 약 이름을 다시 알려주세요.", List.of("target"));
            }
            target = new MedicationTarget(0, profileId, profile.get().profileName(), profile.get().relationship(),
                    customName, 1, "식후 30분", null, null, null, null);
        }
        long drugId = target.myDrugId();

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
                end = start; defaults.add("endDate"); missing.add("endDate");
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
            } else {
                times = proposedTimes(plan);
                defaults.add("doseTimes"); missing.add("doseTimes");
            }
            Integer dose = plan.dosePerTime();
            if (dose == null) {
                dose = target.dosePerTime(); defaults.add("dosePerTime"); missing.add("dosePerTime");
            }
            if (dose != null && (dose < 1 || dose > 10)) throw invalid();
            String timing = plan.timing();
            if (timing == null && allowDefaults) { timing = target.timing(); defaults.add("timing"); }
            if (timing != null && timing.length() > 20) throw invalid();
            var request = new ScheduleRequest(start, end, times, dose, timing);
            if (!missing.isEmpty() && rejectsDefaults(lastUser)) {
                return question("그럼 바꿀 항목과 값만 말씀해주세요.", List.of("correction"));
            }
            if (!missing.isEmpty() && !allowDefaults) {
                if (timing == null && target.timing() != null) {
                    timing = target.timing();
                    if (timing.length() > 20) throw invalid();
                    defaults.add("timing");
                    request = new ScheduleRequest(start, end, times, dose, timing);
                }
                // 동의 전에는 저장 가능한 request를 반환하지 않고 제안 초안만 보관한다.
                var proposal = new Proposal(target.profileId(), drugId, request, List.copyOf(defaults), customName);
                return new Decision(0, 0, null, new Outcome(proposalMessage(target, request, today, defaults),
                        List.copyOf(missing), List.of(), null), proposal);
            }
            return new Decision(target.profileId(), drugId, request,
                    new Outcome("저장 항목을 확인했습니다.", List.of(), List.copyOf(defaults), null), null, Operation.SAVE, customName);
        } catch (DateTimeException e) {
            throw invalid();
        }
    }

    public Outcome execute(long memberId, Decision decision) {
        var completed = new CompletedOperation(decision.operation(), decision.profileId(), decision.myDrugId());
        return switch (decision.operation()) {
            case DELETE -> {
                schedules.delete(memberId, decision.profileId(), decision.myDrugId());
                yield new Outcome("일정을 삭제했어요.", List.of(), List.of(), null, completed);
            }
            case STOP -> {
                var stopped = schedules.stop(memberId, decision.profileId(), decision.myDrugId());
                yield new Outcome("오늘까지만 복용하도록 변경했어요.", List.of(), List.of(), stopped, completed);
            }
            case SAVE -> {
                var saved = decision.myDrugId() == 0
                        ? schedules.createCustom(memberId, decision.profileId(), decision.customName(), decision.request())
                        : schedules.save(memberId, decision.profileId(), decision.myDrugId(), decision.request());
                yield new Outcome("등록했어요.", List.of(), decision.outcome().appliedDefaults(), saved,
                        new CompletedOperation(Operation.SAVE, saved.profileId(), saved.myDrugId()));
            }
        };
    }

    public Decision confirm(Proposal proposal) {
        return new Decision(proposal.profileId(), proposal.myDrugId(), proposal.request(),
                new Outcome("제안한 일정으로 저장할게요.", List.of(), proposal.defaults(), null), null, Operation.SAVE, proposal.customName());
    }

    private String userText(List<Map<String, Object>> transcript) {
        return normalize(transcript.stream().filter(part -> "user".equals(part.get("role")))
                .map(part -> (String) part.get("content")).collect(java.util.stream.Collectors.joining()));
    }

    static boolean isConfirmation(String text) {
        return text.replaceAll("[\\s\\p{Punct}]", "").matches(
                "(네|예|응|좋아|좋아요|그래|그래요|그렇게해줘|그렇게해|그대로해줘|오케이|ㅇㅋ|맞아|맞아요|저장해|저장해줘|등록해|등록해줘|네좋아요|네그렇게해줘|응저장해|응그렇게해줘)");
    }

    private List<LocalTime> proposedTimes(Plan plan) {
        if (plan.dosePeriods() == null) {
            return List.copyOf(MyDrug.DEFAULT_DOSE_TIMES.subList(0, plan.dailyCount() == null ? 3 : plan.dailyCount()));
        }
        if (plan.dosePeriods().isEmpty() || plan.dosePeriods().size() > 3
                || plan.dailyCount() != null && plan.dailyCount() != plan.dosePeriods().size()) throw invalid();
        var times = plan.dosePeriods().stream().map(period -> switch (period == null ? "" : period) {
            case "MORNING" -> MyDrug.DEFAULT_DOSE_TIMES.get(0);
            case "NOON" -> MyDrug.DEFAULT_DOSE_TIMES.get(1);
            case "EVENING" -> MyDrug.DEFAULT_DOSE_TIMES.get(2);
            default -> throw invalid();
        }).sorted().toList();
        if (times.stream().distinct().count() != times.size()) throw invalid();
        return times;
    }

    private String proposalMessage(MedicationTarget target, ScheduleRequest request, LocalDate today, List<String> defaults) {
        String from = request.startDate().equals(today) ? "오늘" : request.startDate().getMonthValue() + "월 " + request.startDate().getDayOfMonth() + "일";
        long days = ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1;
        boolean wholeHours = request.doseTimes().stream().allMatch(time -> time.getMinute() == 0);
        String times = request.doseTimes().stream().map(time -> wholeHours ? Integer.toString(time.getHour()) : spokenTime(time))
                .collect(java.util.stream.Collectors.joining("·")) + (wholeHours ? "시" : "");
        String message = "네, " + from + "부터 " + days + "일 동안 " + target.drugName() + " 등록 도와드릴게요. "
                + (defaults.contains("doseTimes") ? "기본 " : "")
                + "하루 " + request.doseTimes().size() + "번, " + times + ", 1회 " + request.dosePerTime() + "개"
                + (request.timing() == null ? "" : ", " + request.timing());
        return message + ". 적용할까요?";
    }

    private String spokenTime(LocalTime time) {
        return time.getHour() + "시"
                + (time.getMinute() == 0 ? "" : " " + time.getMinute() + "분");
    }

    private Decision question(String message, List<String> missing) {
        return new Decision(0, 0, null, new Outcome(message, missing, List.of(), null));
    }

    /** 명시적 기본값 위임만 검증한다. 짧은 동의는 세션에 보관한 제안에만 적용한다. */
    private boolean defaultsAllowed(Plan plan, List<Map<String, Object>> transcript) {
        if (!plan.useDefaults() || plan.defaultEvidence() == null || plan.defaultEvidence().isBlank()) return false;
        String evidence = normalize(plan.defaultEvidence());
        String lastUser = normalize(lastTurn(transcript, "user"));
        if (rejectsDefaults(lastUser)) return false;
        StringBuilder allUser = new StringBuilder();
        for (var part : transcript) if ("user".equals(part.get("role"))) allUser.append(part.get("content"));
        if (!normalize(allUser.toString()).contains(evidence)) return false;
        return evidence.matches(".*(알아서|기본값?|기본설정|그냥).*(저장|등록|해줘|해요|해).*");
    }

    private boolean rejectsDefaults(String text) {
        return text.matches(".*(기본|알아서).*(쓰지마|하지마|말고|말아|싫어).*");
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

package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleRequest;
import org.semiproject.pillbadeumserver.medication.dto.MedicationLiveDtos.*;
import org.semiproject.pillbadeumserver.medication.service.MedicationScheduleService.ScheduleChange;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 필수 정보와 기본값 위임 의사를 서버에서 검사한다. prepare 단계에서는 저장하지 않는다. */
@Service
public class MedicationLiveWorkflow {
    private static final long USER_FRAGMENT_GAP_MILLIS = 1000;
    public record Proposal(long profileId, long myDrugId, ScheduleRequest request, List<String> defaults, String customName,
                           List<ScheduleChange> batch) {
        public Proposal { batch = List.copyOf(batch); }
        public Proposal(long profileId, long myDrugId, ScheduleRequest request, List<String> defaults, String customName) {
            this(profileId, myDrugId, request, defaults, customName, List.of());
        }
        public Proposal(long profileId, long myDrugId, ScheduleRequest request, List<String> defaults) {
            this(profileId, myDrugId, request, defaults, null);
        }
    }
    // myDrugId=0은 저장 전 초안 내부에서만 사용한다. 완료 응답에는 DB가 발급한 ID만 반환한다.
    public record Decision(long profileId, long myDrugId, ScheduleRequest request, Outcome outcome, Proposal proposal, Operation operation,
                           String customName, List<ScheduleChange> batch) {
        public Decision { batch = List.copyOf(batch); }
        public Decision(long profileId, long myDrugId, ScheduleRequest request, Outcome outcome, Proposal proposal, Operation operation, String customName) {
            this(profileId, myDrugId, request, outcome, proposal, operation, customName, List.of());
        }
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
        return prepare(memberId, selection, today, transcript, null);
    }

    public Decision prepare(long memberId, StartRequest selection, LocalDate today, List<Map<String, Object>> transcript, Proposal previous) {
        var candidates = reads.allDrugs(memberId);
        String lastUser = normalize(lastTurn(transcript, "user"));
        boolean listQuestion = isMedicationListQuestion(lastUser);
        if (listQuestion) {
            String names = candidates.stream().filter(target -> target.profileId() == selection.selectedProfileId())
                    .map(MedicationTarget::drugName).collect(java.util.stream.Collectors.joining(", "));
            String list = names.isEmpty() ? "선택한 프로필에 등록된 약이 없어요."
                    : "선택한 프로필에 등록된 약은 " + names + "예요.";
            return question(list, List.of());
        }
        var selectedDrugs = candidates.stream().filter(target -> target.profileId() == selection.selectedProfileId())
                .sorted(Comparator.comparingLong(MedicationTarget::myDrugId)).toList();
        boolean explicitBatch = isBulkScheduleRequest(lastUser);
        if (explicitBatch && selectedDrugs.isEmpty()) return question("선택한 프로필에 등록된 약이 없어요. 약을 먼저 등록해주세요.", List.of("target"));
        boolean asksForPair = lastUser.matches(".*(둘다|두개다|두개모두|2개다|두약모두|두약다).*")
                && !lastUser.matches(".*(전부|모든약|약[은는을를]?(전체|다)).*");
        if (explicitBatch && asksForPair && selectedDrugs.size() != 2) {
            return question("선택한 프로필에는 약이 " + selectedDrugs.size() + "개 있어요. 모두 등록하려면 내 약 전부라고 말씀해주세요.", List.of("target"));
        }
        var profiles = reads.profiles(memberId);
        var plan = planner.plan(selection, today, candidates, profiles, transcript);
        if ("CANCEL".equals(plan.intent())) return question("이번 요청은 취소할게요. 기존 일정은 그대로예요.", List.of());
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
        boolean narrowing = previous != null && !previous.batch().isEmpty() && narrowsToOne(lastUser, plan, selectedDrugs);
        if (narrowing && !singleScopeSpecified(lastUser, plan, selection, selectedDrugs)) {
            return question("어떤 약 하나를 등록할까요? 약 이름을 말씀해주세요.", List.of("target"));
        }
        boolean continuingBatch = previous != null && !previous.batch().isEmpty() && !narrowing;
        if (explicitBatch || continuingBatch) {
            return prepareBatch(plan, selection, today, transcript, lastUser, selectedDrugs, continuingBatch ? previous : null);
        }
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
        return prepareTarget(plan, selection, today, transcript, lastUser, target, customName, false);
    }

    private Decision prepareTarget(Plan plan, StartRequest selection, LocalDate today, List<Map<String, Object>> transcript,
                                   String lastUser, MedicationTarget target, String customName, boolean requireConfirmation) {
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
            if (requireConfirmation || !missing.isEmpty() && !allowDefaults) {
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
                if (!decision.batch().isEmpty()) {
                    var saved = schedules.saveBatch(memberId, decision.profileId(), decision.batch());
                    var first = saved.getFirst();
                    String names = saved.stream().map(response -> response.drugName()).collect(java.util.stream.Collectors.joining(", "));
                    yield new Outcome(names + " 총 " + saved.size() + "개 약의 일정을 함께 저장했어요.", List.of(), decision.outcome().appliedDefaults(), first,
                            new CompletedOperation(Operation.SAVE, first.profileId(), first.myDrugId()));
                }
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
                new Outcome("제안한 일정으로 저장할게요.", List.of(), proposal.defaults(), null), null, Operation.SAVE, proposal.customName(), proposal.batch());
    }

    private Decision prepareBatch(Plan plan, StartRequest selection, LocalDate today, List<Map<String, Object>> transcript,
                                  String lastUser, List<MedicationTarget> selectedDrugs, Proposal previous) {
        if (selectedDrugs.isEmpty() || plan.profileId() != null && plan.profileId().longValue() != selection.selectedProfileId()
                || plan.customName() != null && !plan.customName().isBlank()) {
            return question("선택한 프로필의 등록된 약만 함께 등록할 수 있어요. 프로필과 약을 다시 확인해주세요.", List.of("target"));
        }
        String hint = plan.profileHint() == null ? "" : normalize(plan.profileHint());
        var selected = selectedDrugs.getFirst();
        if (!hint.isEmpty() && (!userText(transcript).contains(hint)
                || !normalize(selected.profileName()).contains(hint)
                && !("SELF".equalsIgnoreCase(selected.relationship()) && List.of("나", "내", "본인", "저", "제").contains(hint)))) {
            return question("함께 등록할 약의 프로필을 먼저 선택해주세요.", List.of("target"));
        }
        if (previous != null && (previous.profileId() != selection.selectedProfileId()
                || !previous.batch().stream().map(ScheduleChange::myDrugId).sorted().toList()
                .equals(selectedDrugs.stream().map(MedicationTarget::myDrugId).toList()))) {
            return question("제안한 뒤 약 목록이 바뀌었어요. 함께 등록할 약을 다시 확인해주세요.", List.of("target"));
        }
        String evidence = plan.actionEvidence() == null ? "" : normalize(plan.actionEvidence());
        if (previous == null && (!isBulkScheduleRequest(evidence) || !userText(transcript).contains(evidence))) {
            return question("선택한 프로필의 약 전부를 함께 등록할지 말씀해주세요.", List.of("target"));
        }
        var proposals = new ArrayList<Proposal>();
        var missing = new ArrayList<String>();
        for (var target : selectedDrugs) {
            var itemPlan = previous == null ? plan : carryBatchValues(plan, previous.batch().stream()
                    .filter(change -> change.myDrugId() == target.myDrugId()).findFirst().orElseThrow().request());
            var prepared = prepareTarget(itemPlan, selection, today, transcript, lastUser, target, null, true);
            if (prepared.proposal() == null) return prepared;
            proposals.add(prepared.proposal()); missing.addAll(prepared.outcome().missingFields());
        }
        var changes = proposals.stream().map(proposal -> new ScheduleChange(proposal.myDrugId(), proposal.request())).toList();
        var defaults = java.util.stream.Stream.concat(previous == null ? java.util.stream.Stream.<String>empty() : previous.defaults().stream(),
                proposals.stream().flatMap(proposal -> proposal.defaults().stream())).distinct().toList();
        var first = proposals.getFirst();
        var proposal = new Proposal(selection.selectedProfileId(), first.myDrugId(), first.request(), defaults, null, changes);
        return new Decision(0, 0, null, new Outcome(batchProposalMessage(selectedDrugs, changes, today),
                missing.stream().distinct().toList(), List.of(), null), proposal);
    }

    private Plan carryBatchValues(Plan plan, ScheduleRequest previous) {
        String start = plan.startDate() == null ? previous.startDate().toString() : plan.startDate();
        Integer days = plan.durationDays();
        if (days == null && plan.endDate() == null) days = Math.toIntExact(ChronoUnit.DAYS.between(previous.startDate(), previous.endDate()) + 1);
        var times = plan.doseTimes();
        if (times == null && plan.dosePeriods() == null
                && (plan.dailyCount() == null || plan.dailyCount() == previous.doseTimes().size())) {
            times = previous.doseTimes().stream().map(LocalTime::toString).toList();
        }
        return new Plan(plan.intent(), plan.myDrugId(), start, plan.endDate(), days, times, plan.dailyCount(),
                plan.dosePerTime() == null ? previous.dosePerTime() : plan.dosePerTime(),
                plan.timing() == null ? previous.timing() : plan.timing(), plan.useDefaults(), plan.defaultEvidence(),
                plan.dosePeriods(), plan.profileHint(), plan.drugHints(), plan.actionEvidence(), plan.profileId(), plan.customName());
    }

    private boolean singleScopeSpecified(String text, Plan plan, StartRequest selection, List<MedicationTarget> selectedDrugs) {
        if (text.matches(".*(이약|선택한약)만.*") && selection.selectedMyDrugId() != null) {
            return plan.myDrugId() == null || plan.myDrugId().longValue() == selection.selectedMyDrugId();
        }
        for (var target : selectedDrugs) {
            long id = plan.myDrugId() != null ? plan.myDrugId() : selection.selectedMyDrugId() == null ? 0 : selection.selectedMyDrugId();
            if (target.myDrugId() != id) continue;
            if (text.contains(normalize(target.drugName()))) return true;
            if (plan.drugHints() != null && plan.drugHints().stream().anyMatch(hint -> hint != null && !normalize(hint).isEmpty()
                    && text.contains(normalize(hint)) && normalize(target.drugName()).contains(normalize(hint))
                    && selectedDrugs.stream().filter(item -> normalize(item.drugName()).contains(normalize(hint))).count() == 1)) return true;
        }
        return false;
    }

    private boolean narrowsToOne(String text, Plan plan, List<MedicationTarget> selectedDrugs) {
        if (text.matches(".*(둘다|두개다|두개모두|2개다|두약모두|전부|모든약|약전체|약모두)(는|은|을|를|가)?(말고|아니라|아니고).*")
                || text.matches(".*(이약|선택한약|한약|하나)만.*")) return true;
        if (plan.drugHints() == null || plan.drugHints().size() != 1) return false;
        String hint = plan.drugHints().getFirst() == null ? "" : normalize(plan.drugHints().getFirst());
        return !hint.isEmpty() && text.contains(hint + "만")
                && selectedDrugs.stream().filter(target -> normalize(target.drugName()).contains(hint)).count() == 1;
    }

    private String batchProposalMessage(List<MedicationTarget> targets, List<ScheduleChange> changes, LocalDate today) {
        var request = changes.getFirst().request();
        String from = request.startDate().equals(today) ? "오늘" : request.startDate().getMonthValue() + "월 " + request.startDate().getDayOfMonth() + "일";
        long days = ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1;
        String times = request.doseTimes().stream().map(this::spokenTime).collect(java.util.stream.Collectors.joining("·"));
        var details = new ArrayList<String>();
        for (int i = 0; i < changes.size(); i++) {
            var item = changes.get(i).request();
            details.add(targets.get(i).drugName() + " 1회 " + item.dosePerTime() + "개"
                    + (item.timing() == null ? "" : ", " + item.timing()));
        }
        return from + "부터 " + days + "일 동안 매일 " + times + "로 약 " + targets.size() + "개의 일정을 함께 등록할게요. "
                + String.join(". ", details) + ". 적용할까요?";
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

    private boolean isMedicationListQuestion(String text) {
        return text.contains("약") && text.matches(".*(뭐|무슨|어떤|목록).*")
                && (text.contains("목록") || text.matches(".*(먹는약|복용(중인|하는)약|등록(된|한)약|내약|약.*있[어나지니]).*"))
                && !text.matches(".*(일정|등록해|등록하자|추가해|저장|변경|바꿔|수정|삭제|지워|그만|중단|취소|부터|추천|먹어야).*");
    }

    private boolean isBulkScheduleRequest(String text) {
        if (text.matches(".*(취소|하지마|하지말|삭제|지워|그만|중단).*")) return false;
        // "둘 다 말고 A"는 한 약으로 정정한 요청이므로 기존 해석 경로에 맡긴다.
        String request = text.replaceAll("(둘다|두개다|두개모두|2개다|두약모두|두약다|전부|모든약|약전체|약모두)(는|은|을|를|가)?(말고|아니라|아니고)", "");
        return request.matches(".*(둘다|두개다|두개모두|2개다|두약모두|두약다|모든약|약[은는을를]?(전부|모두|전체|다)).*") || request.startsWith("전부");
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
        boolean collecting = false, interrupted = false;
        long nextStart = -1, nextEnd = -1;
        for (int i = transcript.size() - 1; i >= 0; i--) {
            var part = transcript.get(i);
            if (!role.equals(part.get("role"))) {
                if (collecting) interrupted = true;
                continue;
            }
            long start = part.get("start_ms") instanceof Number value ? value.longValue() : -1;
            long end = part.get("end_ms") instanceof Number value ? value.longValue() : -1;
            boolean timed = start >= 0 && end > start;
            if (collecting) {
                boolean ordered = timed && nextStart >= 0 && start <= nextStart && end <= nextEnd
                        && (start < nextStart || end < nextEnd);
                if (ordered) {
                    if (nextStart - end > USER_FRAGMENT_GAP_MILLIS) break;
                } else if (interrupted) break;
            }
            // 시간으로 연결을 확인할 때만 AI의 끼어들기를 넘는다. 시각이 없거나 반복되면 기존 role 경계를 지킨다.
            result.insert(0, part.get("content"));
            collecting = true; interrupted = false;
            nextStart = timed ? start : -1; nextEnd = timed ? end : -1;
        }
        return result.toString();
    }

    private String normalize(String value) { return value.replaceAll("[\\s\\p{Punct}]", ""); }
    private BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_INPUT, "기간·복용 시각·1회 개수를 다시 알려주세요."); }
}

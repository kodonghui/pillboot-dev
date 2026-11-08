package org.semiproject.pillbadeumserver.chat.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse.ScheduleValues;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.image.domain.PrescriptionDose;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationVoiceDtos.CommandRequest;
import org.semiproject.pillbadeumserver.medication.dto.MedicationVoiceDtos.ParsedCommand;
import org.semiproject.pillbadeumserver.medication.service.MedicationVoiceInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * V3-CHAT-007 일정 제안 만들기. 저장된 일정 요청 질문에 '지금 → 바뀐 뒤' 카드나 안내를 답변으로 저장한다.
 * 대상 약의 일정은 바꾸지 않는다(확정은 ChatProposalService). 답변 글은 모델이 만들지 않은 고정 문장이다.
 */
@Service
public class ScheduleProposalService {
    private static final Logger log = LoggerFactory.getLogger(ScheduleProposalService.class);
    static final String FOUND = "일정을 바꾸는 카드를 만들었어요. 카드에서 지금 일정과 바뀐 뒤 일정을 확인하고 확정해 주세요. "
            + "확정하기 전에는 일정이 그대로이고, 카드는 30분 동안 쓸 수 있어요.";
    static final String ASK_DRUG = "어느 약의 일정을 바꿀지 약목록에 있는 이름으로 말해 주세요. 예) 타이레놀 아침 8시로 바꿔 줘";
    static final String UNREADABLE = "바꿀 일정을 읽지 못했어요. 복용 시각·1회 개수·기간 중 바꿀 값을 말해 주세요. "
            + "예) 아침 8시 한 알로 바꿔 줘. 일정을 그만두거나 지우는 일은 복용 달력에서 할 수 있어요.";
    static final String SAME = "말씀하신 일정이 지금 일정과 같아요. 바꿀 복용 시각·1회 개수·기간을 말해 주세요.";
    static final String READ_FAILED = "지금은 일정을 읽을 수 없어요. 잠시 뒤 다시 말해 주세요. 일정은 바꾸지 않았어요.";
    static final String UNSAVABLE = "말씀하신 일정은 저장할 수 없어요. 복용 시각·1회 개수·기간을 다시 말해 주세요.";
    static final String REJECTED_TAIL = " 일정은 바꾸지 않았어요.";
    static final String NO_DRUGS = "약목록에 약이 없어요. 처방전 사진을 보내거나 ‘타이레놀 500 등록해 줘’처럼 약을 먼저 넣어 주세요.";
    static final String TOO_MANY = "약목록의 약이 10개가 넘어 한 번에 바꿀 수 없어요. 바꿀 약 이름을 10개까지 말해 주세요.";
    /** 카톡 다리가 이 줄로 여러 약 일정 카드를 알아본다(카톡 다리). 문구를 바꾸면 다리도 함께 바꾼다. */
    static final String SAME_SKIPPED = "이미 같은 일정이라 뺀 약:";
    // 의도 가르기(10초)를 뺀 질문 한도 안에서 해석기 한 번이 쓸 수 있는 시간
    private static final Duration READ_LIMIT = Duration.ofSeconds(20);

    private final MedicationRepository medications;
    private final MedicationVoiceInterpreter interpreter;
    private final ScheduleSetInterpreter setInterpreter;
    private final QuestionStore questions;
    private final ChatProposalStore proposals;
    private final AsyncTaskExecutor executor;
    private final Clock clock;

    @Autowired
    public ScheduleProposalService(MedicationRepository medications, MedicationVoiceInterpreter interpreter,
                                   ScheduleSetInterpreter setInterpreter, QuestionStore questions, ChatProposalStore proposals,
                                   @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor) {
        this(medications, interpreter, setInterpreter, questions, proposals, executor, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    ScheduleProposalService(MedicationRepository medications, MedicationVoiceInterpreter interpreter,
                            ScheduleSetInterpreter setInterpreter, QuestionStore questions,
                            ChatProposalStore proposals, AsyncTaskExecutor executor, Clock clock) {
        this.medications = medications;
        this.interpreter = interpreter;
        this.setInterpreter = setInterpreter;
        this.questions = questions;
        this.proposals = proposals;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * @param text 질문 글. 해석기에 보내고 로그에는 남기지 않는다
     * @param targetName 의도 가르기(V3-CHAT-001)가 고른 약목록 이름. 정하지 못했으면 null
     * @param retry 다시 보내기면 저장 직전에 다시 보내기 자격을 확인한다(기존 답변과 같음)
     * @return 일정 카드가 붙은 답변, 또는 카드 없이 안내만 있는 답변. 저장하지 못하면 같은 질문 ID로 다시 보낼 수 있다.
     */
    public QuestionResponse.Answer propose(Long sessionId, Long memberId, Long profileId, QuestionResponse.Question question,
                                           String text, String targetName, ChatDeadline deadline, boolean retry) {
        return propose(sessionId, memberId, profileId, question, text, targetName == null ? List.of() : List.of(targetName),
                false, deadline, retry);
    }

    /**
     * 2026-11-08 카톡 기록('나 먹는 약들 3일간 등록해줘 오늘부터' → '어느 약의 일정을 바꿀지' 반복): 대상이 약 여러 개(이름
     * 여러 개나 약목록 전부)면 카드 하나에 약마다 '지금 → 바뀐 뒤'를 담는다. 대상이 하나면 기존 한 약 흐름과 같다.
     * @param targetNames 의도 가르기가 고른 약목록 이름들. 하나라도 약목록에서 하나로 정하지 못하면 어느 약인지 묻는다
     * @param allDrugs 약목록의 모든 약(최대 10개)
     */
    public QuestionResponse.Answer propose(Long sessionId, Long memberId, Long profileId, QuestionResponse.Question question,
                                           String text, List<String> targetNames, boolean allDrugs, ChatDeadline deadline,
                                           boolean retry) {
        try {
            deadline.check();
            var drugs = medications.drugs(memberId, profileId);
            if (allDrugs && drugs.isEmpty()) return notice(sessionId, memberId, profileId, question, NO_DRUGS, deadline, retry, "no_drugs");
            var targets = allDrugs ? drugs : targets(drugs, targetNames);
            if (targets.size() > ChatProposalStore.MAX_SCHEDULE_ROWS)
                return notice(sessionId, memberId, profileId, question, TOO_MANY, deadline, retry, "too_many");
            if (targets.size() > 1) return proposeMany(sessionId, memberId, profileId, question, text, targets, deadline, retry);
            var target = targets.isEmpty() ? null : targets.getFirst();
            if (target == null) return notice(sessionId, memberId, profileId, question, askDrug(drugs, text), deadline, retry, "ask_drug");
            LocalDate today = LocalDate.now(clock);
            ParsedCommand parsed;
            try {
                parsed = deadline.within(READ_LIMIT).await(executor.submit(() -> interpreter.parse(
                        new CommandRequest(text, profileId, target.myDrugId(), today), today, List.of(blind(target)))));
            } catch (ExternalApiException unavailable) {
                // 질문 전체 한도가 지났으면 안내 대신 실패로 끝내 다시 보내기를 할 수 있게 한다.
                deadline.check();
                return notice(sessionId, memberId, profileId, question, READ_FAILED, deadline, retry, "read_failed");
            }
            if ("TARGET_NOT_FOUND".equals(parsed.intent())
                    || parsed.myDrugId() != null && parsed.myDrugId() != target.myDrugId())
                return notice(sessionId, memberId, profileId, question, ASK_DRUG, deadline, retry, "ask_drug");
            if (!"CREATE_SCHEDULE".equals(parsed.intent()))
                return notice(sessionId, memberId, profileId, question, UNREADABLE, deadline, retry, "unreadable");
            var current = ScheduleDraft.stored(target.startDate(), target.endDate(), target.storedDoseTimes(),
                    target.confirmedDosePerTime(), target.timing());
            var proposed = ScheduleDraft.proposed(parsed, current, target.dosePerTime(), today);
            if (proposed.equals(current)) return notice(sessionId, memberId, profileId, question, SAME, deadline, retry, "same");
            // 확정 때와 같은 저장 규칙(MyDrug)으로 미리 검사한다. 저장하지 않는 검증용 객체다.
            try {
                ScheduleDraft.request(proposed).applyTo(MyDrug.createCustom(profileId, "일정 입력 검증"));
            } catch (BusinessException invalid) {
                throw new ScheduleDraft.Rejected(UNSAVABLE);
            }
            deadline.check();
            log.info("[일정 제안] result=card");
            var rx = proposals.prescriptions(profileId, List.of(target.myDrugId())).get(target.myDrugId());
            return proposals.saveScheduleSetAnswer(sessionId, memberId, profileId, question, FOUND, List.of(
                    new ChatProposalStore.ScheduleRow(target.myDrugId(), current, proposed, note(current, proposed, rx, null))),
                    deadline, retry);
        } catch (ScheduleDraft.Rejected rejected) {
            return notice(sessionId, memberId, profileId, question, rejected.getMessage() + REJECTED_TAIL, deadline, retry,
                    "rejected");
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.FORBIDDEN || exception.getErrorCode() == ErrorCode.NOT_FOUND
                    || exception.getErrorCode() == ErrorCode.DUPLICATE_REQUEST
                    || exception.getErrorCode() == ErrorCode.RETRY_NOT_ALLOWED) throw exception;
            throw new AnswerFailedException(question.messageId(), exception);
        } catch (RuntimeException exception) {
            throw new AnswerFailedException(question.messageId(), exception);
        }
    }

    /**
     * 약 여러 개의 일정 제안. 해석기 한 번으로 모든 약에 함께 쓰는 값과 약마다 다른 1회 양·복용 때를 읽고, 약마다 한 약 제안과
     * 같은 규칙(ScheduleDraft·저장 규칙)으로 검사한다. 한 약이라도 규칙 밖이면 카드 없이 그 약 이름과 안내로 답한다.
     * 이미 같은 일정인 약은 카드에서 빼고 답에 이름을 적는다.
     */
    private QuestionResponse.Answer proposeMany(Long sessionId, Long memberId, Long profileId, QuestionResponse.Question question,
                                                String text, List<MedicationTarget> targets, ChatDeadline deadline, boolean retry) {
        LocalDate today = LocalDate.now(clock);
        ScheduleSetInterpreter.Result parsed;
        try {
            var blind = targets.stream().map(ScheduleProposalService::blind).toList();
            parsed = deadline.within(READ_LIMIT).await(executor.submit(() -> setInterpreter.parse(text, today, blind)));
        } catch (ExternalApiException unavailable) {
            deadline.check();
            return notice(sessionId, memberId, profileId, question, READ_FAILED, deadline, retry, "read_failed");
        }
        if (!"CREATE_SCHEDULE".equals(parsed.intent()) || parsed.commands().size() != targets.size())
            return notice(sessionId, memberId, profileId, question, UNREADABLE, deadline, retry, "unreadable");
        var prescriptions = proposals.prescriptions(profileId, targets.stream().map(MedicationTarget::myDrugId).toList());
        var rows = new ArrayList<ChatProposalStore.ScheduleRow>();
        var same = new ArrayList<String>();
        for (int i = 0; i < targets.size(); i++) {
            var target = targets.get(i);
            var command = parsed.commands().get(i);
            var current = ScheduleDraft.stored(target.startDate(), target.endDate(), target.storedDoseTimes(),
                    target.confirmedDosePerTime(), target.timing());
            ScheduleValues proposed;
            String amountNote;
            try {
                proposed = ScheduleDraft.proposed(command.command(), current, target.dosePerTime(), today);
                var withAmount = withAmount(proposed, command.amount());
                proposed = withAmount.values();
                amountNote = withAmount.note();
                ScheduleDraft.request(proposed).applyTo(MyDrug.createCustom(profileId, "일정 입력 검증"));
            } catch (ScheduleDraft.Rejected rejected) {
                throw new ScheduleDraft.Rejected(RegistrationProposalService.quote(target.drugName()) + ": " + rejected.getMessage());
            } catch (BusinessException invalid) {
                throw new ScheduleDraft.Rejected(RegistrationProposalService.quote(target.drugName()) + ": " + UNSAVABLE);
            }
            if (proposed.equals(current)) {
                same.add(target.drugName());
                continue;
            }
            rows.add(new ChatProposalStore.ScheduleRow(target.myDrugId(), current, proposed,
                    note(current, proposed, prescriptions.get(target.myDrugId()), amountNote)));
        }
        if (rows.isEmpty()) return notice(sessionId, memberId, profileId, question, SAME, deadline, retry, "same");
        deadline.check();
        log.info("[일정 제안] result=card drugs={} same={}", rows.size(), same.size());
        return proposals.saveScheduleSetAnswer(sessionId, memberId, profileId, question, foundMany(rows.size(), same), rows,
                deadline, retry);
    }

    static String foundMany(int count, List<String> same) {
        String text = "약 " + count + "개의 일정을 바꾸는 카드를 만들었어요. 카드에서 약마다 지금 일정과 바뀐 뒤 일정을 확인하고 "
                + "한 번에 확정해 주세요. 확정하기 전에는 일정이 그대로이고, 카드는 30분 동안 쓸 수 있어요.";
        if (same.isEmpty()) return text;
        return text + "\n" + SAME_SKIPPED + " " + String.join(", ", same.stream().map(RegistrationProposalService::quote).toList()) + ".";
    }

    record Amounted(ScheduleValues values, String note) {}

    /**
     * 시럽처럼 mL로 말한 1회 양은 일정의 1회 개수(1~10개)에 넣을 수 없어 복용 때 칸(20자)에 '식후 30분 · 1회 20mL'처럼 적는다.
     * 칸을 넘으면 적지 않고 확정 전에 볼 점에 남긴다.
     */
    static Amounted withAmount(ScheduleValues proposed, String amount) {
        if (amount == null) return new Amounted(proposed, null);
        String timing = proposed.timing();
        if (timing != null && timing.contains(amount)) return new Amounted(proposed, null);
        String combined = timing == null ? "1회 " + amount : timing + " · 1회 " + amount;
        if (combined.length() > ScheduleDraft.MAX_TIMING)
            return new Amounted(proposed, "1회 " + amount + "는 복용 때 칸(20자)에 다 들어가지 않아 적지 못했어요.");
        return new Amounted(new ScheduleValues(proposed.startDate(), proposed.endDate(), proposed.doseTimes(),
                proposed.dosePerTime(), combined), "1회 " + amount + "는 복용 때 칸에 적었어요.");
    }

    /**
     * 확정 전에 볼 점: 하루 횟수가 바뀌는지, 처방전 사진으로 등록할 때 읽은 값과 다른지(1회 양·하루 횟수·일수), 1회 양을 어디에
     * 적었는지. 없으면 null이다. 처방과 다른 일정은 막지 않고 알린다(바꾸는 판단은 회원·의료진).
     */
    static String note(ScheduleValues current, ScheduleValues proposed, PrescriptionDose rx, String amountNote) {
        var parts = new ArrayList<String>();
        if (current.doseTimes() != null && current.doseTimes().size() != proposed.doseTimes().size())
            parts.add("하루 " + current.doseTimes().size() + "번에서 " + proposed.doseTimes().size() + "번으로 바뀌어요.");
        if (rx != null) {
            var differs = new ArrayList<String>();
            Integer dose = rx.wholeDose();
            if (rx.dosePerTime() != null && (dose == null || !dose.equals(proposed.dosePerTime())))
                differs.add("1회 " + rx.dosePerTime() + "개");
            if (rx.dailyCount() != null && rx.dailyCount() != proposed.doseTimes().size()) differs.add("하루 " + rx.dailyCount() + "번");
            long days = ChronoUnit.DAYS.between(proposed.startDate(), proposed.endDate()) + 1;
            if (rx.totalDays() != null && rx.totalDays() != days) differs.add(rx.totalDays() + "일분");
            if (!differs.isEmpty())
                parts.add("처방전 값: " + String.join("·", differs) + ". 처방과 다르게 먹으려면 의사·약사와 먼저 상의해 주세요.");
        }
        if (amountNote != null) parts.add(amountNote);
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /** 이름마다 약목록 약 하나. 이름이 없으면 약목록에 약이 하나뿐일 때 그 약. 하나라도 정하지 못하면 빈 목록(어느 약인지 묻기). */
    private static List<MedicationTarget> targets(List<MedicationTarget> drugs, List<String> names) {
        if (names.isEmpty()) return drugs.size() == 1 ? List.of(drugs.getFirst()) : List.of();
        var found = new ArrayList<MedicationTarget>();
        for (String name : names) {
            var target = target(drugs, name);
            if (target == null) return List.of();
            if (found.stream().noneMatch(t -> t.myDrugId() == target.myDrugId())) found.add(target);
        }
        return found;
    }

    /**
     * 이 프로필 약목록에서 이름이 같은 약 하나. 같은 이름이 둘 이상이면 null(어느 약인지 묻기).
     * 이름을 정하지 못했어도 약목록에 약이 하나뿐이면 그 약이다('저녁에 두 알'. 확정 전에는 바뀌지 않음, QA 2026-11-04 2차).
     */
    private static MedicationTarget target(List<MedicationTarget> drugs, String targetName) {
        if (targetName == null) return drugs.size() == 1 ? drugs.getFirst() : null;
        var matches = drugs.stream().filter(drug -> targetName.equals(drug.drugName())).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    /**
     * 어느 약인지 묻는 안내. 줄여 말한 이름('노바스크')에 맞는 약목록 약이 이름이 다른 둘 이상이면 그 약들을 보이고
     * 함량까지 말해 달라고 한다(QA 2026-11-04 2차: 노바스크 5·10을 고르게 하지 않고 '타이레놀' 예만 보임). 아니면 ASK_DRUG.
     */
    static String askDrug(List<MedicationTarget> drugs, String text) {
        String said = text == null ? "" : text.replaceAll("\\s+", "");
        var names = drugs.stream().map(MedicationTarget::drugName)
                .filter(name -> { String base = base(name); return base.length() >= 2 && said.contains(base); })
                .distinct().limit(5).toList();
        if (names.size() < 2) return ASK_DRUG;
        var out = new StringBuilder("약목록에 맞는 약이 여럿이에요. 어느 약인지 함량까지 넣어 다시 말해 주세요.");
        names.forEach(name -> out.append("\n· ").append(name));
        return out.append("\n예) ").append(names.getLast()).append(" 아침 8시로 바꿔 줘").toString();
    }

    /** 줄여 부르는 이름: 괄호·띄어쓰기를 빼고 첫 숫자 앞까지, 끝의 제형(정·캡슐 등)을 뺀다('노바스크정 5mg' → '노바스크'). */
    static String base(String name) {
        String plain = name.replaceAll("\\([^)]*\\)", "").replaceAll("\\s+", "");
        int digit = 0;
        while (digit < plain.length() && !Character.isDigit(plain.charAt(digit))) digit++;
        return plain.substring(0, digit).replaceAll("(?:연질캡슐|캡슐|정제|시럽|정|산|액)$", "");
    }

    /** 해석기에는 이미 고른 약 하나만 보낸다. 대상은 서버가 정했으므로 프로필 이름은 보내지 않는다(8.4). */
    private static MedicationTarget blind(MedicationTarget target) {
        return new MedicationTarget(target.myDrugId(), target.profileId(), "", target.relationship(), target.drugName(),
                target.dosePerTime(), target.timing(), target.startDate(), target.endDate(), target.storedDoseTimes(),
                target.updatedAt(), target.doseConfirmed());
    }

    private QuestionResponse.Answer notice(Long sessionId, Long memberId, Long profileId, QuestionResponse.Question question,
                                           String content, ChatDeadline deadline, boolean retry, String result) {
        log.info("[일정 제안] result={}", result);
        return questions.saveNotice(sessionId, memberId, profileId, question, content, deadline, retry);
    }
}

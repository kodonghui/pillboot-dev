package org.semiproject.pillbadeumserver.chat.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
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
            + "예) 아침 8시 한 알로 바꿔 줘. 일정을 그만두거나 지우는 일은 캘린더에서 할 수 있어요.";
    static final String SAME = "말씀하신 일정이 지금 일정과 같아요. 바꿀 복용 시각·1회 개수·기간을 말해 주세요.";
    static final String READ_FAILED = "지금은 일정을 읽을 수 없어요. 잠시 뒤 다시 말해 주세요. 일정은 바꾸지 않았어요.";
    static final String UNSAVABLE = "말씀하신 일정은 저장할 수 없어요. 복용 시각·1회 개수·기간을 다시 말해 주세요.";
    static final String REJECTED_TAIL = " 일정은 바꾸지 않았어요.";
    // 의도 가르기(10초)를 뺀 질문 한도 안에서 해석기 한 번이 쓸 수 있는 시간
    private static final Duration READ_LIMIT = Duration.ofSeconds(20);

    private final MedicationRepository medications;
    private final MedicationVoiceInterpreter interpreter;
    private final QuestionStore questions;
    private final ChatProposalStore proposals;
    private final AsyncTaskExecutor executor;
    private final Clock clock;

    @Autowired
    public ScheduleProposalService(MedicationRepository medications, MedicationVoiceInterpreter interpreter,
                                   QuestionStore questions, ChatProposalStore proposals,
                                   @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor) {
        this(medications, interpreter, questions, proposals, executor, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    ScheduleProposalService(MedicationRepository medications, MedicationVoiceInterpreter interpreter, QuestionStore questions,
                            ChatProposalStore proposals, AsyncTaskExecutor executor, Clock clock) {
        this.medications = medications;
        this.interpreter = interpreter;
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
        try {
            deadline.check();
            var target = target(memberId, profileId, targetName);
            if (target == null) return notice(sessionId, memberId, profileId, question, ASK_DRUG, deadline, retry, "ask_drug");
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
                    target.dosePerTime(), target.timing());
            var proposed = ScheduleDraft.proposed(parsed, current, today);
            if (proposed.equals(current)) return notice(sessionId, memberId, profileId, question, SAME, deadline, retry, "same");
            // 확정 때와 같은 저장 규칙(MyDrug)으로 미리 검사한다. 저장하지 않는 검증용 객체다.
            try {
                ScheduleDraft.request(proposed).applyTo(MyDrug.createCustom(profileId, "일정 입력 검증"));
            } catch (BusinessException invalid) {
                throw new ScheduleDraft.Rejected(UNSAVABLE);
            }
            deadline.check();
            log.info("[일정 제안] result=card");
            return proposals.saveScheduleAnswer(sessionId, memberId, profileId, question, FOUND, target.myDrugId(),
                    current, proposed, deadline, retry);
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

    /** 이 프로필 약목록에서 이름이 같은 약 하나. 이름이 없거나 같은 이름이 둘 이상이면 null(어느 약인지 묻기). */
    private MedicationTarget target(Long memberId, Long profileId, String targetName) {
        if (targetName == null) return null;
        var matches = medications.drugs(memberId, profileId).stream()
                .filter(drug -> targetName.equals(drug.drugName())).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
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

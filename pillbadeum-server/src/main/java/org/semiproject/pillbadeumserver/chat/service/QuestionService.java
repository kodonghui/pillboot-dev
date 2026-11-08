package org.semiproject.pillbadeumserver.chat.service;

import java.util.Set;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** CHAT-004·005·007: 권한·입력·진행 표식 → 분류 → PASS 질문 저장 → 답변 생성·저장 순서로 조정한다. */
@Service
public class QuestionService {
    private final QuestionStore store;
    private final QuestionClassifier classifier;
    private final ChatAnswerService answers;
    private final QuestionIntentReader intents;
    private final RegistrationProposalService registrations;
    private final ScheduleProposalService schedules;
    private final PhotoProposalService photos;
    private final ChatDrugLookup lookup;
    private final Set<Long> inProgress = ConcurrentHashMap.newKeySet();

    public QuestionService(QuestionStore store, QuestionClassifier classifier, ChatAnswerService answers,
                           QuestionIntentReader intents, RegistrationProposalService registrations,
                           ScheduleProposalService schedules, PhotoProposalService photos, ChatDrugLookup lookup) {
        this.store = store;
        this.classifier = classifier;
        this.answers = answers;
        this.intents = intents;
        this.registrations = registrations;
        this.schedules = schedules;
        this.photos = photos;
        this.lookup = lookup;
    }

    /**
     * 질문 앞뒤 공백을 제거하고 1–1,000자를 검사한 뒤 분류한다. 회원 번호는 인증 정보에서만 얻는다.
     * 외부 호출 중 DB 트랜잭션을 유지하지 않으며 성공·차단·예외 모두 finally에서 진행 표식을 반납한다.
     * @return 답변 저장까지 끝난 ANSWERED 또는 저장 없는 BLOCKED. 답변 임베딩은 커밋 뒤 제출한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public QuestionResponse submit(Long sessionId, String content) {
        return startSubmit(sessionId, content).run(ChatProgress.NONE);
    }

    /** 마지막 미응답 질문만 복구한다. 새 질문과 같은 표식을 공유하고 분류·질문 저장·질문 임베딩은 반복하지 않는다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public QuestionResponse retry(Long sessionId, Long messageId) {
        return startRetry(sessionId, messageId).run(ChatProgress.NONE);
    }

    /**
     * V3-CHAT-008: 권한·입력 검사와 진행 표식은 호출(요청) 스레드에서 끝내고, 나머지 처리를 돌려준다.
     * 스트림 질문은 여기서 난 오류를 기존과 같은 HTTP 오류로 답하고, 돌려받은 처리를 다른 스레드에서 실행한다.
     * 돌려받은 쪽은 run 또는 abandon 중 하나를 꼭 불러 진행 표식을 반납해야 한다.
     */
    public Turn startSubmit(Long sessionId, String content) {
        Long memberId = SecurityUtil.getCurrentMemberId();
        // 90초 응답 한도에 전달 여유를 남긴 외부 작업 예산이다. 취소 후 늦은 답변 쓰기를 막는다.
        var deadline = ChatDeadline.after(Duration.ofSeconds(85));
        Long profileId = store.checkAccess(sessionId, memberId);
        String question = content == null ? "" : content.strip();
        if (question.isBlank() || question.length() > 1000) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "앞뒤 공백을 제거한 질문은 1자 이상 1,000자 이하여야 합니다.");
        }
        lock(sessionId);
        return new Turn(sessionId, deadline, reported -> {
            reported.check();
            reported.stage(ChatStage.UNDERSTANDING);
            var context = store.classificationContext(sessionId, memberId, profileId);
            var plan = classifier.plan(question, context);
            // 2026-11-08 QA: 응급·자해 안내 뒤 30분 안의 이어 말('괜찮겠지?'·'119 안 부르면 안돼?')은 이어 말 안내로 막는다
            if (!plan.category().isPassed()) return plan.afterEmergency()
                    ? QuestionResponse.blocked(plan.category(), plan.category().followUpMessage())
                    : QuestionResponse.blocked(plan.category());
            // V3-CHAT-001: 통과한 질문만 의도를 가른다. 등록 요청은 등록 제안 카드(V3-CHAT-002)로,
            // 일정 요청은 일정 제안 카드(V3-CHAT-007)로 답한다.
            String request = request(sessionId, question, null);
            // 2026-11-08: 바로 앞 대화와 함께 가른다('약목록 전부', '아니 전부 넣어달라고'를 앞 요청에 잇기).
            var intent = intents.read(request, context.myDrugNames(), context.recentTurns(), reported);
            reported.check();
            var saved = store.save(sessionId, memberId, profileId, question);
            if (intent.kind() == QuestionIntent.Kind.REGISTER_DRUG)
                return QuestionResponse.answered(saved, registrations.propose(sessionId, memberId, profileId, saved,
                        intent.drugMentions(), reported, false));
            if (intent.kind() == QuestionIntent.Kind.SCHEDULE_CHANGE)
                return QuestionResponse.answered(saved, schedule(sessionId, memberId, profileId, saved, request, intent,
                        reported, false));
            return QuestionResponse.answered(saved, answers.complete(sessionId, memberId, profileId, saved, reported, plan));
        });
    }

    /**
     * V3-CHAT-006: 상담방 사진 한 장으로 등록 제안을 만든다. 질문과 같은 진행 표식을 써서 답하는 중에는 받지 않는다.
     * 판독(최대 85초) 뒤 저장할 여유를 두어 한도를 88초로 둔다. 사진은 남기지 않으므로 다시 보내기는 없다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public QuestionResponse submitPhoto(Long sessionId, MultipartFile image) {
        Long memberId = SecurityUtil.getCurrentMemberId();
        var deadline = ChatDeadline.after(Duration.ofSeconds(88));
        Long profileId = store.checkAccess(sessionId, memberId);
        lock(sessionId);
        try {
            return photos.propose(sessionId, memberId, profileId, image, deadline);
        } finally {
            inProgress.remove(sessionId);
        }
    }

    /** V3-CHAT-008: retry의 앞부분(권한·진행 표식)만 호출 스레드에서 한다. 나머지는 startSubmit과 같다. */
    public Turn startRetry(Long sessionId, Long messageId) {
        Long memberId = SecurityUtil.getCurrentMemberId();
        var deadline = ChatDeadline.after(Duration.ofSeconds(85));
        Long profileId = store.checkAccess(sessionId, memberId);
        lock(sessionId);
        return new Turn(sessionId, deadline, reported -> {
            reported.check();
            reported.stage(ChatStage.UNDERSTANDING);
            var question = store.retryQuestion(sessionId, memberId, profileId, messageId);
            // 등록·일정 요청이었던 질문은 다시 보내도 제안 카드로 답한다(V3-CHAT-002·007).
            // 처음 보낼 때처럼 'N번'은 저장된 질문 앞 턴의 후보 목록으로 바꿔 가른다
            String request = request(sessionId, store.questionContent(sessionId, question), question.turnNo());
            var intent = intents.read(request, store.classificationContext(sessionId, memberId, profileId).myDrugNames(),
                    store.recentTurns(sessionId, question.turnNo()), reported);
            reported.check();
            if (intent.kind() == QuestionIntent.Kind.REGISTER_DRUG)
                return QuestionResponse.answered(question, registrations.propose(sessionId, memberId, profileId, question,
                        intent.drugMentions(), reported, true));
            if (intent.kind() == QuestionIntent.Kind.SCHEDULE_CHANGE)
                return QuestionResponse.answered(question, schedule(sessionId, memberId, profileId, question, request, intent,
                        reported, true));
            return QuestionResponse.answered(question, answers.retry(sessionId, memberId, profileId, question, reported));
        });
    }

    /**
     * 의도 가르기·등록·일정 제안에 보낼 글. 직전 답이 검색 후보 목록이고 질문에 'N번'이 있으면 그 후보 이름으로 바꾼다
     * (ChatDrugLookup.withChoiceNames). 저장하는 질문과 보통 답변은 보낸 글 그대로다. 'N번'이 없으면 이전 답을 읽지 않는다.
     * beforeTurn 은 다시 보내는 질문의 턴(그 앞 턴의 답을 봄). 새 질문은 null(마지막 턴의 답).
     */
    private String request(Long sessionId, String question, Integer beforeTurn) {
        if (question == null || !question.matches("(?s).*[1-5]\\s*번.*")) return question;
        String named = lookup.withChoiceNames(question, store.answerBefore(sessionId, beforeTurn));
        return named == null ? question : named;
    }

    /**
     * 일정 요청을 일정 제안으로. 약 이름 없이 '저녁 약은 7시 말고 9시로'처럼 한 때에 먹는 약들을 가리키면 보낸 글 그대로 그 때에
     * 먹는 약들로 보낸다(2026-11-08 QA 시나리오 01 다시 돌림: 의도 읽기가 대상을 정하지 못해 어느 약이냐고 되물음, 두 번 중 두 번).
     */
    private QuestionResponse.Answer schedule(Long sessionId, Long memberId, Long profileId, QuestionResponse.Question question,
                                             String request, QuestionIntent intent, ChatDeadline deadline, boolean retry) {
        boolean slotGroup = intent.targetMyDrugNames().isEmpty() && ScheduleDraft.slotGroup(request) != null;
        String text = slotGroup || intent.request() == null ? request : intent.request();
        return schedules.propose(sessionId, memberId, profileId, question, text, intent.targetMyDrugNames(),
                slotGroup || intent.allMyDrugs(), deadline, retry);
    }

    private void lock(Long sessionId) {
        if (!inProgress.add(sessionId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST, "이전 질문에 답하는 중이에요.");
        }
    }

    /** 검사를 마치고 진행 표식을 잡은 질문 하나. 실행이 성공·차단·예외로 끝나거나 버려지면 표식을 반납한다. */
    public final class Turn {
        private final Long sessionId;
        private final ChatDeadline deadline;
        private final Work work;

        private Turn(Long sessionId, ChatDeadline deadline, Work work) {
            this.sessionId = sessionId;
            this.deadline = deadline;
            this.work = work;
        }

        /** 분류·저장·답변을 실행한다. 단계에 들어설 때마다 progress에 알린다. */
        public QuestionResponse run(ChatProgress progress) {
            try {
                return work.run(deadline.reporting(progress));
            } finally {
                inProgress.remove(sessionId);
            }
        }

        /** 실행하지 못하게 됐을 때(작업 제출 실패 등) 진행 표식만 반납한다. */
        public void abandon() { inProgress.remove(sessionId); }
    }

    @FunctionalInterface
    private interface Work {
        QuestionResponse run(ChatDeadline deadline);
    }

    /** CHAT-003: 단일 JVM의 진행 상태만 읽는다. 실제 retry 자격은 API에서 DB와 함께 다시 확인한다. */
    public boolean isInProgress(Long sessionId) { return inProgress.contains(sessionId); }
}

package org.semiproject.pillbadeumserver.chat.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.semiproject.pillbadeumserver.drug.domain.MyDrugRepository;
import org.semiproject.pillbadeumserver.chat.domain.ChatMessage;
import org.semiproject.pillbadeumserver.chat.domain.ChatMessageRepository;
import org.semiproject.pillbadeumserver.chat.domain.ChatSessionRepository;
import org.semiproject.pillbadeumserver.chat.domain.ChatSummaryRepository;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.exception.ForbiddenException;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CHAT-004·007: 외부 호출과 분리한 짧은 권한 조회·질문/답변 저장 트랜잭션을 담당한다. */
@Service
public class QuestionStore {
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final MyDrugRepository medications;
    private final ChatSummaryRepository summaries;

    public QuestionStore(ChatSessionRepository sessions, ChatMessageRepository messages, MyDrugRepository medications, ChatSummaryRepository summaries) {
        this.sessions = sessions;
        this.messages = messages;
        this.medications = medications;
        this.summaries = summaries;
    }

    /** 대화방·프로필의 소유 회원을 모두 확인한다. 반환한 프로필 번호는 저장 직전에 재확인한다. */
    @Transactional(readOnly = true)
    public Long checkAccess(Long sessionId, Long memberId) {
        var session = sessions.findSessionOwner(sessionId)
                .orElseThrow(() -> new NotFoundException("대화방을 찾을 수 없습니다."));
        checkOwner(session.getMemberId(), memberId);
        if (session.getProfileId() == null) throw new NotFoundException("프로필을 찾을 수 없습니다.");
        Long owner = sessions.findProfileOwner(session.getProfileId())
                .orElseThrow(() -> new NotFoundException("프로필을 찾을 수 없습니다."));
        checkOwner(owner, memberId);
        return session.getProfileId();
    }

    /**
     * PASS 질문만 전달받아 질문·첫 사본·제목·활동 시각을 한 번에 커밋한다.
     * 프로필과 방의 잠금 뒤에 메시지를 조회하므로 동시 저장 후의 최신 턴을 기준으로 계산한다.
     * 외부 호출은 없으며 실패하면 질문과 사본 변경이 함께 롤백된다.
     */
    @Transactional
    public QuestionResponse.Question save(Long sessionId, Long memberId, Long profileId, String content) {
        // CHAT-002와 같은 프로필 → 방 순서이다. 잠금 전 일반 조회로 오래된 MySQL 읽기 시점을 만들지 않는다.
        Long owner = sessions.lockProfileOwner(profileId)
                .orElseThrow(() -> new NotFoundException("프로필을 찾을 수 없습니다."));
        checkOwner(owner, memberId);
        var session = sessions.lockSession(sessionId)
                .orElseThrow(() -> new NotFoundException("대화방을 찾을 수 없습니다."));
        checkOwner(session.getMemberId(), memberId);
        if (!Objects.equals(session.getProfileId(), profileId)) {
            throw new NotFoundException("대화방의 프로필을 찾을 수 없습니다.");
        }

        int turnNo = Math.addExact(messages.findLastTurnNo(sessionId), 1);
        if (!messages.existsBySessionIdAndRole(sessionId, "user")) {
            var profile = sessions.findProfileSnapshot(profileId, memberId)
                    .orElseThrow(() -> new NotFoundException("프로필을 찾을 수 없습니다."));
            session.confirmFirstQuestion(profile.getBirthYear(), profile.getGender(),
                    profile.getPregnantFlag() == 1, content);
        }
        LocalDateTime now = LocalDateTime.now(TimeZoneConfig.ZONE).truncatedTo(ChronoUnit.SECONDS);
        session.recordActivity(now);
        // 모델의 전체 입력 토큰은 개별 질문의 토큰 수가 아니므로 token_count에 복사하지 않는다.
        var message = messages.save(ChatMessage.question(sessionId, turnNo, content, now));
        return new QuestionResponse.Question(message.getMessageId(), message.getTurnNo());
    }

    /** CHAT-004 retry: 현재 방의 마지막 미응답 USER만 선택한다. 질문·턴·사본·활동 시각은 변경하지 않는다. */
    @Transactional(readOnly = true)
    public QuestionResponse.Question retryQuestion(Long sessionId, Long memberId, Long profileId, Long messageId) {
        if (!Objects.equals(checkAccess(sessionId, memberId), profileId)) throw new NotFoundException("대화방의 프로필을 찾을 수 없습니다.");
        var message = messages.findByMessageIdAndSessionId(messageId, sessionId)
                .orElseThrow(() -> new NotFoundException("메시지를 찾을 수 없습니다."));
        if (!"user".equals(message.getRole())) throw new BusinessException(ErrorCode.RETRY_NOT_ALLOWED, ErrorCode.RETRY_NOT_ALLOWED.getDescription());
        checkRetryAllowed(sessionId, messageId);
        return new QuestionResponse.Question(messageId, message.getTurnNo());
    }

    private void checkRetryAllowed(Long sessionId, Long messageId) {
        var latest = messages.findLatestQuestionState(sessionId);
        if (latest.isEmpty() || !Objects.equals(latest.get().getMessageId(), messageId) || latest.get().getAnswered() != 0) {
            throw new BusinessException(ErrorCode.RETRY_NOT_ALLOWED, ErrorCode.RETRY_NOT_ALLOWED.getDescription());
        }
    }

    /** 소유권 확인 후 확정 사본·현재 내 약·완성된 이전 턴을 값 객체로 복사하고 DB 연결을 반납한다. */
    @Transactional(readOnly = true)
    public AnswerInput loadAnswerInput(Long sessionId, Long memberId, Long profileId, QuestionResponse.Question question) {
        if (!Objects.equals(checkAccess(sessionId, memberId), profileId)) {
            throw new NotFoundException("대화방의 프로필을 찾을 수 없습니다.");
        }
        var session = sessions.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("대화방을 찾을 수 없습니다."));
        var savedQuestion = messages.findByMessageIdAndSessionIdAndRole(question.messageId(), sessionId, "user")
                .orElseThrow(() -> new NotFoundException("질문을 찾을 수 없습니다."));
        // 품목코드 없는 직접 입력 약은 조회하지 않되, 확인 못 한 등록 약으로 끝까지 보존한다.
        var registered = medications.findAllWithDrugByProfileId(profileId);
        var unidentified = registered.stream().filter(m -> m.getDrug() == null)
                .map(m -> m.getCustomName()).toList();
        var drugs = registered.stream().filter(m -> m.getDrug() != null).map(m ->
                new AnswerInput.Medication(m.getDrug().getItemSeq(), m.getDrug().getName(), m.getDrug().getMaker(),
                        m.getDrug().getEtcOtc(), m.getConfirmedDosePerTime(), m.getTiming(), m.getStartDate(), m.getEndDate(),
                        m.getDoseTimes(), m.getUpdatedAt().toLocalDate())).toList();
        var turns = messages.findRecentCompleteTurns(sessionId, savedQuestion.getTurnNo()).stream().map(turn ->
                new AnswerInput.Turn(turn.getTurnNo(), turn.getQuestion(), turn.getAnswer(),
                        turn.getQuestionTokens(), turn.getAnswerTokens())).toList();
        var recentSummaries = summaries.findTop3BySessionIdAndToTurnLessThanOrderByToTurnDesc(sessionId, savedQuestion.getTurnNo())
                .stream().map(s -> new AnswerInput.Summary(s.getSummaryId(), s.getFromTurn(), s.getToTurn(), s.getContent())).toList();
        return new AnswerInput(session.getSnapBirthYear(), session.getSnapGender(), session.getSnapPregnant(),
                savedQuestion.getContent(), drugs, turns, !messages.existsBySessionIdAndRole(sessionId, "assistant"),
                recentSummaries, java.util.List.of(), summaries.countBySessionIdAndToTurnLessThan(sessionId, savedQuestion.getTurnNo()), unidentified);
    }

    /** 같은 질문의 턴에 답변·활동 시각·보조 ID를 함께 저장한다. 외부 대기는 이 트랜잭션 전에 끝낸다. */
    @Transactional(timeout = 5)
    public QuestionResponse.Answer saveAnswer(Long sessionId, Long memberId, Long profileId,
                                               QuestionResponse.Question question, GeneratedAnswer generated,
                                               ChatDeadline deadline) {
        return persistAnswer(sessionId, memberId, profileId, question, generated, deadline, false);
    }

    /** 새 질문과 같은 잠금 순서로 저장 직전 retry 자격을 재확인한다. 앞 턴에 답변을 끼워 넣지 않는다. */
    @Transactional(timeout = 5)
    public QuestionResponse.Answer saveRetryAnswer(Long sessionId, Long memberId, Long profileId,
                                                  QuestionResponse.Question question, GeneratedAnswer generated,
                                                  ChatDeadline deadline) {
        return persistAnswer(sessionId, memberId, profileId, question, generated, deadline, true);
    }

    private QuestionResponse.Answer persistAnswer(Long sessionId, Long memberId, Long profileId,
                                                   QuestionResponse.Question question, GeneratedAnswer generated,
                                                   ChatDeadline deadline, boolean retry) {
        Long owner = sessions.lockProfileOwner(profileId)
                .orElseThrow(() -> new NotFoundException("프로필을 찾을 수 없습니다."));
        checkOwner(owner, memberId);
        var session = sessions.lockSession(sessionId)
                .orElseThrow(() -> new NotFoundException("대화방을 찾을 수 없습니다."));
        checkOwner(session.getMemberId(), memberId);
        if (!Objects.equals(session.getProfileId(), profileId)) throw new NotFoundException("대화방의 프로필을 찾을 수 없습니다.");
        var savedQuestion = messages.findByMessageIdAndSessionIdAndRole(question.messageId(), sessionId, "user")
                .orElseThrow(() -> new NotFoundException("질문을 찾을 수 없습니다."));
        if (retry) checkRetryAllowed(sessionId, question.messageId());
        if (savedQuestion.getTurnNo() != question.turnNo()
                || messages.existsBySessionIdAndTurnNoAndRole(sessionId, question.turnNo(), "assistant")) {
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST, "이미 답변한 질문입니다.");
        }
        // 취소·시간 초과된 외부 작업의 결과를 뒤늦게 저장하지 않는다.
        deadline.check();
        LocalDateTime now = LocalDateTime.now(TimeZoneConfig.ZONE).truncatedTo(ChronoUnit.SECONDS);
        var answer = messages.save(ChatMessage.answer(sessionId, question.turnNo(), generated.content(), now, generated.durNotice()));
        session.recordAnswer(now, generated.responseId());
        return new QuestionResponse.Answer(answer.getMessageId(), answer.getTurnNo(), answer.getContent(),
                generated.durNotice(), answer.getCreatedAt().atZone(TimeZoneConfig.ZONE).toOffsetDateTime());
    }

    private void checkOwner(Long owner, Long memberId) {
        if (!Objects.equals(owner, memberId)) throw new ForbiddenException();
    }
}

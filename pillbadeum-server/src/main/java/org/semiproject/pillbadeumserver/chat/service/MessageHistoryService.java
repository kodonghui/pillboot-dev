package org.semiproject.pillbadeumserver.chat.service;

import java.util.List;
import org.semiproject.pillbadeumserver.chat.domain.ChatMessageRepository;
import org.semiproject.pillbadeumserver.chat.domain.ChatSessionRepository;
import org.semiproject.pillbadeumserver.chat.dto.MessageHistoryResponse;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CHAT-003: 본인 방·프로필을 확인한 뒤 질문과 답변을 같은 턴 페이지에 담아 읽는다. 쓰기·외부 호출은 없다. */
@Service
public class MessageHistoryService {
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final QuestionService questions;

    public MessageHistoryService(ChatSessionRepository sessions, ChatMessageRepository messages, QuestionService questions) {
        this.sessions = sessions;
        this.messages = messages;
        this.questions = questions;
    }

    /**
     * @param sessionId 경로의 방 번호. 소유 회원 번호는 토큰에서만 얻는다.
     * @param beforeTurn 이 턴보다 앞의 페이지. null이면 최신 페이지이다.
     * @param limit 한 페이지의 턴 수(1~30). 메시지 행 수가 아니므로 질문·답변을 나누지 않는다.
     * @return 오래된 턴부터 정렬한 원문 이력과 방 전체의 마지막 미답변 질문 ID
     */
    @Transactional(readOnly = true)
    public MessageHistoryResponse read(Long sessionId, Integer beforeTurn, int limit) {
        boolean wasInProgress = questions.isInProgress(sessionId);
        Long memberId = SecurityUtil.getCurrentMemberId();
        var owner = sessions.findSessionOwner(sessionId)
                .orElseThrow(() -> new NotFoundException("대화방을 찾을 수 없습니다."));
        SecurityUtil.checkOwner(owner.getMemberId());
        Long profileOwner = sessions.findProfileOwner(owner.getProfileId())
                .orElseThrow(() -> new NotFoundException("프로필을 찾을 수 없습니다."));
        SecurityUtil.checkOwner(profileOwner);
        var header = sessions.findHistoryHeader(sessionId, memberId)
                .orElseThrow(() -> new NotFoundException("대화방을 찾을 수 없습니다."));

        // 한 턴 더 읽어 hasMore를 계산하고, 실제 반환할 턴에 속한 모든 질문·답변을 함께 가져온다.
        var turns = messages.findHistoryTurns(sessionId, beforeTurn, PageRequest.of(0, limit + 1));
        boolean hasMore = turns.size() > limit;
        var selected = turns.subList(0, Math.min(limit, turns.size()));
        List<MessageHistoryResponse.Message> history = selected.isEmpty() ? List.of()
                : messages.findHistoryMessages(sessionId, selected).stream().map(MessageHistoryResponse.Message::from).toList();

        // 이전 페이지를 보더라도 재시도 표시는 방 전체의 마지막 질문을 기준으로 한다.
        Long retryId = messages.findLatestQuestionState(sessionId)
                .filter(question -> question.getAnswered() == 0 && !wasInProgress && !questions.isInProgress(sessionId))
                .map(ChatMessageRepository.QuestionState::getMessageId).orElse(null);
        return new MessageHistoryResponse(sessionId, header.getProfileId(), header.getProfileName(), header.getTitle(),
                header.getDrugCount(), retryId, history, hasMore);
    }
}

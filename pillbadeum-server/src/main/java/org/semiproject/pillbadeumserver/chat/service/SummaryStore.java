package org.semiproject.pillbadeumserver.chat.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;
import org.semiproject.pillbadeumserver.chat.domain.*;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CHAT-009: 요약 입력 읽기와 저장만 짧게 처리한다. 외부 모델 호출은 이 서비스 밖에서 실행한다. */
@Service
public class SummaryStore {
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final ChatSummaryRepository summaries;

    public SummaryStore(ChatSessionRepository sessions, ChatMessageRepository messages, ChatSummaryRepository summaries) {
        this.sessions = sessions; this.messages = messages; this.summaries = summaries;
    }

    /** 가장 오래된 미완성 고정 구간을 선택한다. 중간 미응답·누락은 null로 명시하고 가짜 답변을 만들지 않는다. */
    @Transactional(readOnly = true)
    public Window nextWindow(long sessionId) {
        if (!sessions.existsById(sessionId)) return null;
        int lastAnswer = messages.findLastAnsweredTurnNo(sessionId);
        var done = new HashSet<>(summaries.findCompletedStarts(sessionId));
        for (long start = 1; start + 9 <= lastAnswer; start += 10) {
            int from = (int) start, to = (int) start + 9;
            if (done.contains(from)) continue;
            var questions = new HashMap<Integer, String>(); var answers = new HashMap<Integer, String>();
            for (var m : messages.findHistoryMessages(sessionId, IntStream.rangeClosed(from, to).boxed().toList())) {
                ("user".equals(m.getRole()) ? questions : answers).put(m.getTurnNo(), m.getContent());
            }
            var turns = IntStream.rangeClosed(from, to).mapToObj(i ->
                    new Turn(i, questions.get(i), questions.containsKey(i) ? answers.get(i) : null)).toList();
            String previous = summaries.findFirstBySessionIdAndToTurnLessThanOrderByToTurnDesc(sessionId, from)
                    .map(ChatSummary::getContent).orElse(null);
            return new Window(sessionId, from, to, previous, turns);
        }
        return null;
    }

    /** 입력 선택 뒤 대기한 작업이 이미 끝난 구간을 다시 유료 생성하지 않도록 새 읽기 시점에서 확인한다. */
    @Transactional(readOnly = true)
    public boolean isStored(long sessionId, int fromTurn) {
        return summaries.existsBySessionIdAndFromTurnAndToTurn(sessionId, fromTurn, fromTurn + 9);
    }

    /** CHAT-006: 검색 payload가 아닌 같은 방의 MySQL 본문을 읽는다. 없는 ID·다른 방·미래·최근 중복은 제외한다. */
    @Transactional(readOnly = true)
    public List<AnswerInput.Summary> selectedEarlier(long sessionId, int beforeTurn, List<Long> ids, List<Long> recentIds) {
        if (ids.isEmpty()) return List.of();
        return summaries.findSelectedEarlier(sessionId, beforeTurn, ids).stream()
                .filter(s -> !recentIds.contains(s.getSummaryId())).limit(3)
                .map(s -> new AnswerInput.Summary(s.getSummaryId(), s.getFromTurn(), s.getToTurn(), s.getContent())).toList();
    }

    /** 같은 방을 잠근 뒤 구간 중복·유효한 답변 경계를 재확인한다. UNIQUE·활동 시각 갱신에 의존하지 않는다. */
    @Transactional(timeout = 5)
    public ChatSummary save(Window window, String content, ChatDeadline deadline) {
        sessions.lockSession(window.sessionId()).orElseThrow(() -> new NotFoundException("대화방을 찾을 수 없습니다."));
        if (summaries.existsBySessionIdAndFromTurnAndToTurn(window.sessionId(), window.fromTurn(), window.toTurn())) return null;
        deadline.check();
        if (content == null || content.isBlank() || content.codePointCount(0, content.length()) > 500
                || window.fromTurn() < 1 || window.fromTurn() % 10 != 1 || window.toTurn() != window.fromTurn() + 9
                || messages.findLastAnsweredTurnNo(window.sessionId()) < window.toTurn()) {
            throw new IllegalArgumentException("요약 구간을 확인할 수 없습니다.");
        }
        return summaries.save(ChatSummary.of(window.sessionId(), window.fromTurn(), window.toTurn(), content,
                LocalDateTime.now(TimeZoneConfig.ZONE).truncatedTo(ChronoUnit.SECONDS)));
    }

    public record Turn(int turnNo, String question, String answer) {}
    public record Window(long sessionId, int fromTurn, int toTurn, String previousSummary, List<Turn> turns) {}
}

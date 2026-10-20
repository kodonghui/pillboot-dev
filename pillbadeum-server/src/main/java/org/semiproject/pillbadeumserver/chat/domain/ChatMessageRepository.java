package org.semiproject.pillbadeumserver.chat.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

/** CHAT-003·004·006·007: 턴 이력·첫 USER·다음 턴·완성 대화를 조회하고 질문/답변을 저장한다. */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    boolean existsBySessionIdAndRole(Long sessionId, String role);

    /** 답변 없는 질문도 이미 사용한 턴이다. 대화방 행을 잠근 저장 트랜잭션 안에서만 호출한다. */
    @Query("select coalesce(max(m.turnNo), 0) from ChatMessage m where m.sessionId = :sessionId")
    int findLastTurnNo(@Param("sessionId") Long sessionId);

    /** 요약은 해당 구간 끝 이후의 답변 커밋을 기준으로 시작한다. 미응답 USER만으로 구간을 완료하지 않는다. */
    @Query("select coalesce(max(m.turnNo), 0) from ChatMessage m where m.sessionId=:sessionId and m.role='assistant'")
    int findLastAnsweredTurnNo(@Param("sessionId") Long sessionId);

    Optional<ChatMessage> findByMessageIdAndSessionIdAndRole(Long messageId, Long sessionId, String role);
    Optional<ChatMessage> findByMessageIdAndSessionId(Long messageId, Long sessionId);
    boolean existsBySessionIdAndTurnNoAndRole(Long sessionId, int turnNo, String role);

    /** CHAT-003: 행 수가 아닌 서로 다른 턴 번호를 최신순으로 조회한다. Pageable은 limit+1만 제한한다. */
    @Query("""
            select distinct m.turnNo from ChatMessage m
            where m.sessionId = :sessionId and m.role in ('user', 'assistant')
              and (:beforeTurn is null or m.turnNo < :beforeTurn)
            order by m.turnNo desc
            """)
    List<Integer> findHistoryTurns(@Param("sessionId") Long sessionId, @Param("beforeTurn") Integer beforeTurn, Pageable page);

    /** 선택한 턴의 원문을 모두 보존한다. 동시각은 messageId로 정렬하고 다른 방·내부 역할은 제외한다. */
    @Query("""
            select m from ChatMessage m where m.sessionId = :sessionId and m.turnNo in :turns
              and m.role in ('user', 'assistant')
            order by m.turnNo asc, m.createdAt asc, m.messageId asc
            """)
    List<ChatMessage> findHistoryMessages(@Param("sessionId") Long sessionId, @Param("turns") List<Integer> turns);

    /** 마지막 USER만 판단한다. 예전 미답변 턴·현재 페이지·다른 방의 답변으로 재시도 대상을 바꾸지 않는다. */
    @Query(value = """
            SELECT q.message_id AS messageId,
                   CASE WHEN EXISTS (SELECT 1 FROM chat_message a
                     WHERE a.session_id=q.session_id AND a.trun_no=q.trun_no AND a.role='assistant')
                   THEN 1 ELSE 0 END AS answered
            FROM chat_message q WHERE q.session_id=:sessionId AND q.role='user'
            ORDER BY q.trun_no DESC, q.created_at DESC, q.message_id DESC LIMIT 1
            """, nativeQuery = true)
    Optional<QuestionState> findLatestQuestionState(@Param("sessionId") Long sessionId);

    interface QuestionState {
        Long getMessageId();
        int getAnswered();
    }

    /** 같은 방의 완성된 이전 6턴만 읽는다. 레거시 중복은 각 역할의 마지막 행 한 개로 고정한다. */
    @Query(value = """
            SELECT q.trun_no AS turnNo, q.content AS question, a.content AS answer,
                   q.token_count AS questionTokens, a.token_count AS answerTokens
            FROM chat_message q JOIN chat_message a
              ON a.session_id=q.session_id AND a.trun_no=q.trun_no AND a.role='assistant'
            WHERE q.session_id=:sessionId AND q.role='user' AND q.trun_no<:beforeTurn
              AND q.message_id=(SELECT MAX(u.message_id) FROM chat_message u
                WHERE u.session_id=q.session_id AND u.trun_no=q.trun_no AND u.role='user')
              AND a.message_id=(SELECT MAX(r.message_id) FROM chat_message r
                WHERE r.session_id=q.session_id AND r.trun_no=q.trun_no AND r.role='assistant')
            ORDER BY q.trun_no DESC LIMIT 6
            """, nativeQuery = true)
    List<CompleteTurn> findRecentCompleteTurns(@Param("sessionId") Long sessionId, @Param("beforeTurn") int beforeTurn);

    interface CompleteTurn {
        int getTurnNo();
        String getQuestion();
        String getAnswer();
        Integer getQuestionTokens();
        Integer getAnswerTokens();
    }
}

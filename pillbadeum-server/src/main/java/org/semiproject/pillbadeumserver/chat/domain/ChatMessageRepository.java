package org.semiproject.pillbadeumserver.chat.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** CHAT-004·006·007: 같은 방의 첫 USER·다음 턴·완성 대화를 조회하고 질문/답변을 저장한다. */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    boolean existsBySessionIdAndRole(Long sessionId, String role);

    /** 답변 없는 질문도 이미 사용한 턴이다. 대화방 행을 잠근 저장 트랜잭션 안에서만 호출한다. */
    @Query("select coalesce(max(m.turnNo), 0) from ChatMessage m where m.sessionId = :sessionId")
    int findLastTurnNo(@Param("sessionId") Long sessionId);

    Optional<ChatMessage> findByMessageIdAndSessionIdAndRole(Long messageId, Long sessionId, String role);
    boolean existsBySessionIdAndTurnNoAndRole(Long sessionId, int turnNo, String role);

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

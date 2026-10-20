package org.semiproject.pillbadeumserver.chat.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** CHAT-009: 실제 SQL에 없는 복합 UNIQUE를 가정하지 않고 방 잠금 뒤 구간 존재를 재확인한다. */
public interface ChatSummaryRepository extends JpaRepository<ChatSummary, Long> {
    boolean existsBySessionIdAndFromTurn(Long sessionId, int fromTurn);

    @Query("select s.fromTurn from ChatSummary s where s.sessionId=:sessionId and s.toTurn=s.fromTurn+9")
    List<Integer> findCompletedStarts(@Param("sessionId") Long sessionId);

    Optional<ChatSummary> findFirstBySessionIdAndToTurnLessThanOrderByToTurnDesc(Long sessionId, int fromTurn);
    List<ChatSummary> findTop3BySessionIdAndToTurnLessThanOrderByToTurnDesc(Long sessionId, int beforeTurn);
    long countBySessionIdAndToTurnLessThan(Long sessionId, int beforeTurn);

    @Query("select s from ChatSummary s where s.sessionId=:sessionId and s.summaryId in :ids and s.toTurn<:beforeTurn order by s.fromTurn asc")
    List<ChatSummary> findSelectedEarlier(@Param("sessionId") Long sessionId, @Param("beforeTurn") int beforeTurn,
                                         @Param("ids") List<Long> ids);
}

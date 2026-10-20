package org.semiproject.pillbadeumserver.chat.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PROF-004: DB 삭제와 함께 정리 요청을 커밋하고 Qdrant 장애·서버 재시작 뒤에도 재시도함. */
@Service
public class VectorCleanupService {
  private static final Logger log = LoggerFactory.getLogger(VectorCleanupService.class);
  private final JdbcTemplate jdbc;
  private final MessageVectorStore vectors;
  private final TransactionTemplate independent;

  public VectorCleanupService(
      JdbcTemplate jdbc, MessageVectorStore vectors, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.vectors = vectors;
    independent = new TransactionTemplate(manager);
    independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** 삭제할 방 ID만 보존함. 원문·회원 정보는 복제하지 않으며 롤백 시 요청도 함께 롤백됨. */
  public void profileDeleting(long profileId) {
    var ids =
        jdbc.queryForList(
            "SELECT session_id FROM chat_session WHERE profile_id=?", Long.class, profileId);
    for (long id : ids) enqueue(id);
    if (!ids.isEmpty())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              cleanup(ids);
            }
          });
  }

  public boolean sourceExists(MessageVectorStore.SourceType type, long sessionId, long sourceId) {
    String table = type == MessageVectorStore.SourceType.MESSAGE ? "chat_message" : "chat_summary";
    String key = type == MessageVectorStore.SourceType.MESSAGE ? "message_id" : "summary_id";
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE session_id=? AND " + key + "=?",
            Integer.class,
            sessionId,
            sourceId)
        > 0;
  }

  /** 외부 호출 중 프로필이 사라진 worker도 저장 후 재확인하여 고아 벡터를 정리함. */
  public void sourceDeleted(long sessionId) {
    independent.executeWithoutResult(status -> enqueue(sessionId));
    cleanup(List.of(sessionId));
  }

  private void enqueue(long sessionId) {
    jdbc.update(
        "INSERT IGNORE INTO vector_cleanup(session_id,requested_at) VALUES(?,CURRENT_TIMESTAMP)",
        sessionId);
  }

  @Scheduled(
      fixedDelayString = "${qdrant.cleanup.interval-ms:60000}",
      initialDelayString = "${qdrant.cleanup.interval-ms:60000}")
  public void retryPending() {
    var ids =
        jdbc.queryForList(
            "SELECT session_id FROM vector_cleanup ORDER BY requested_at,session_id LIMIT 50",
            Long.class);
    cleanup(ids);
  }

  public void cleanup(List<Long> ids) {
    if (ids.isEmpty()) return;
    try {
      vectors.deleteSessions(ids);
      // afterCommit 안에서는 기존 커넥션에 의존하지 않고 별도 커밋으로 완료 표시함.
      independent.executeWithoutResult(
          status -> {
            for (long id : ids) jdbc.update("DELETE FROM vector_cleanup WHERE session_id=?", id);
          });
    } catch (RuntimeException error) {
      log.warn(
          "[벡터 정리] result=pending sessionCount={} type={}",
          ids.size(),
          error.getClass().getSimpleName());
    }
  }
}

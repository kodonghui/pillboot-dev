package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

/** CHAT-006: 질문 벡터를 재사용해 오래된 요약만 찾는다. 벡터 장애는 최근 MySQL 맥락으로 폴백한다. */
@Service
public class SummaryContextService {
    private static final Logger log = LoggerFactory.getLogger(SummaryContextService.class);
    private final MessageVectorStore vectors;
    private final SummaryStore store;
    private final OpenAiClient openAi;
    private final AsyncTaskExecutor executor;

    public SummaryContextService(MessageVectorStore vectors, SummaryStore store, OpenAiClient openAi,
                                 @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor) {
        this.vectors = vectors; this.store = store; this.openAi = openAi; this.executor = executor;
    }

    /** retry는 저장된 질문 벡터를 읽을 뿐 재임베딩하지 않는다. 없거나 실패하면 null이다. */
    public float[] savedQuestionVector(long sessionId, long messageId, ChatDeadline requestDeadline) {
        try {
            if (!vectors.isEnabled()) return null;
            var deadline = requestDeadline.within(Duration.ofSeconds(3));
            return deadline.await(executor.submit(() -> vectors.questionVector(sessionId, messageId, openAi.embeddingModel(), deadline)));
        } catch (RuntimeException error) {
            log.warn("[요약 맥락] result=question_vector_unavailable sessionId={}", sessionId);
            return null;
        }
    }

    /** DB 읽기 TX는 Qdrant 호출 전후로 분리한다. 최근 3개 제외·같은 방/모델·최대 3개를 지킨다. */
    public AnswerInput enrich(long sessionId, int questionTurn, AnswerInput input, float[] vector, ChatDeadline requestDeadline) {
        if (vector == null || input.summaryCount() < 4) return input;
        try {
            if (!vectors.isEnabled()) return input;
            var recentIds = input.recentSummaries().stream().map(AnswerInput.Summary::summaryId).toList();
            var deadline = requestDeadline.within(Duration.ofSeconds(3));
            var ids = deadline.await(executor.submit(() ->
                    vectors.similarSummaryIds(sessionId, openAi.embeddingModel(), vector, recentIds, deadline)));
            requestDeadline.check();
            var similar = store.selectedEarlier(sessionId, questionTurn, ids, recentIds);
            return input.withSimilarSummaries(similar);
        } catch (RuntimeException error) {
            log.warn("[요약 맥락] result=similar_unavailable sessionId={}", sessionId);
            return input;
        }
    }
}

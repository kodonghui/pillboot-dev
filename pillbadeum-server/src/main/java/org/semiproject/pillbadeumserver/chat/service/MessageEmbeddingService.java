package org.semiproject.pillbadeumserver.chat.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

/** CHAT-004·007: 커밋된 질문은 요청 안에서, 답변은 커밋 뒤 기존 Boot 실행기에서 임베딩한다. 실패해도 원문을 유지한다. */
@Service
public class MessageEmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(MessageEmbeddingService.class);
    // 기본 모델의 8,192토큰 한도 안에 들도록 UTF-8 바이트 수를 보수적 상한으로 사용한다. 본문을 자르지 않는다.
    private static final int MAX_INPUT_BYTES = 8191;
    private final OpenAiClient openAi;
    private final MessageVectorStore vectors;
    private final AnswerSources sources;
    private final AsyncTaskExecutor executor;

    public MessageEmbeddingService(OpenAiClient openAi, MessageVectorStore vectors, AnswerSources sources,
                                   @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor) {
        this.openAi = openAi;
        this.vectors = vectors;
        this.sources = sources;
        this.executor = executor;
    }

    /** 권한 확인·질문 저장 커밋 뒤 받은 원문을 사용한다. Qdrant off이면 유료 임베딩도 생략한다. */
    public void questionCommitted(long sessionId, long messageId, String content, ChatDeadline deadline) {
        store(sessionId, messageId, content, "question", deadline);
    }

    /** 답변 커밋이 반환된 뒤에만 제출한다. 대기·제출 실패가 이미 저장한 답변의 응답을 실패로 바꾸지 않는다. */
    public void answerCommitted(long sessionId, long messageId, String content) {
        var deadline = ChatDeadline.after(Duration.ofSeconds(13));
        try {
            if (!vectors.isEnabled()) return;
            executor.execute(() -> store(sessionId, messageId, sources.body(content), "answer", deadline));
        } catch (RuntimeException exception) {
            log.warn("[임베딩 저장] stage=answer result=not_submitted sessionId={} sourceId={}", sessionId, messageId);
        }
    }

    private void store(long sessionId, long messageId, String content, String stage, ChatDeadline deadline) {
        long start = System.nanoTime();
        try {
            if (!vectors.isEnabled()) return;
            if (content == null || content.isBlank() || content.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
                log.info("[임베딩 저장] stage={} result=skipped_input sessionId={} sourceId={}", stage, sessionId, messageId);
                return;
            }
            deadline.check();
            // 외부 worker는 벡터만 반환한다. 시간 초과 후 늦게 완료해도 Qdrant 저장을 시작하지 못한다.
            float[] vector = deadline.within(Duration.ofSeconds(10)).await(executor.submit(() -> openAi.embed(content)));
            deadline.check();
            vectors.upsert(sessionId, messageId, openAi.embeddingModel(), vector, deadline);
            log.info("[임베딩 저장] stage={} result=stored sessionId={} sourceId={} elapsedMs={}",
                    stage, sessionId, messageId, (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException exception) {
            // 제공자 예외 메시지에는 본문·키가 포함될 수 있으므로 상태와 내부 ID만 기록한다.
            log.warn("[임베딩 저장] stage={} result=not_confirmed sessionId={} sourceId={} elapsedMs={}",
                    stage, sessionId, messageId, (System.nanoTime() - start) / 1_000_000);
        }
    }
}

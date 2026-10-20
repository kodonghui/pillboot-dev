package org.semiproject.pillbadeumserver.chat.service;

import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CHAT-007: 외부 작업의 완료를 기다린 호출 스레드만 답변을 저장한다. 시간 초과된 작업은 DB를 쓰지 못한다. */
@Service
public class ChatAnswerService {
    private static final Logger log = LoggerFactory.getLogger(ChatAnswerService.class);
    private final QuestionStore store;
    private final AnswerGenerator generator;
    private final AsyncTaskExecutor executor;
    private final DurCacheStore cache;
    private final MessageEmbeddingService embeddings;

    public ChatAnswerService(QuestionStore store, AnswerGenerator generator, DurCacheStore cache, MessageEmbeddingService embeddings,
                              @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor) {
        this.store = store;
        this.generator = generator;
        this.executor = executor;
        this.cache = cache;
        this.embeddings = embeddings;
    }

    /** 저장된 질문을 기준으로 생성·짧은 답변 저장을 연결하고, 후속 실패에는 같은 질문 ID를 보존한다. */
    public QuestionResponse.Answer complete(Long sessionId, Long memberId, Long profileId,
                                             QuestionResponse.Question question, ChatDeadline deadline) {
        try {
            deadline.check();
            var input = store.loadAnswerInput(sessionId, memberId, profileId, question);
            // 질문은 이미 커밋됐다. 임베딩 실패는 기존 최근 대화·답변 흐름으로 진행한다.
            embeddings.questionCommitted(sessionId, question.messageId(), input.question(), deadline);
            deadline.check();
            var generated = deadline.await(executor.submit(() -> generator.generate(input, deadline)));
            deadline.check();
            // 실패한 캐시 교체는 별도 트랜잭션 전체가 롤백된다. 실제 조회 결과로 만든 답변은 유지한다.
            try { cache.refresh(generated.durLookups(), deadline); }
            catch (RuntimeException exception) { log.warn("[DUR 캐시] result=not_updated"); }
            deadline.check();
            var answer = store.saveAnswer(sessionId, memberId, profileId, question, generated, deadline);
            // 프록시가 저장 트랜잭션 커밋을 마친 뒤 반환한 답변만 후처리에 넘긴다.
            embeddings.answerCommitted(sessionId, answer.messageId(), answer.content());
            return answer;
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.FORBIDDEN || exception.getErrorCode() == ErrorCode.NOT_FOUND
                    || exception.getErrorCode() == ErrorCode.DUPLICATE_REQUEST) throw exception;
            throw new AnswerFailedException(question.messageId(), exception);
        } catch (RuntimeException exception) {
            throw new AnswerFailedException(question.messageId(), exception);
        }
    }
}

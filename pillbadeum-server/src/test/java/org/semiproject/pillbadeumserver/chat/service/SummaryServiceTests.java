package org.semiproject.pillbadeumserver.chat.service;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 응답 후 제출 거절이 이미 저장된 답변의 실패로 전파되지 않는 경계를 확인한다. */
class SummaryServiceTests {
    @Test
    void executorRejectionDoesNotLoadOrGenerateAndDoesNotEscapeToTheResponse() {
        var executor=mock(AsyncTaskExecutor.class);var store=mock(SummaryStore.class);var ai=mock(OpenAiClient.class);
        doThrow(new TaskRejectedException("synthetic")).when(executor).execute(any(Runnable.class));
        var json=new JsonMapper();var summaries=new SummaryService(store,ai,new AnswerSources(json),json,executor,mock(MessageEmbeddingService.class));
        assertThatCode(()->summaries.answerCommitted(42)).doesNotThrowAnyException();
        verifyNoInteractions(store,ai);
    }
}

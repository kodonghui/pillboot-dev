package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** off·본문 크기·외부 실패·늦은 worker·답변 비동기 제출의 상태 보존을 검증한다. */
@ExtendWith(OutputCaptureExtension.class)
class MessageEmbeddingServiceTests {
    OpenAiClient openAi;
    MessageVectorStore vectors;
    SimpleAsyncTaskExecutor executor;
    MessageEmbeddingService service;
    VectorCleanupService lifecycle;
    final CountDownLatch release=new CountDownLatch(1);

    @BeforeEach
    void setup(){
        openAi=mock(OpenAiClient.class);vectors=mock(MessageVectorStore.class);
        lifecycle=mock(VectorCleanupService.class);when(lifecycle.sourceExists(any(),anyLong(),anyLong())).thenReturn(true);
        when(vectors.isEnabled()).thenReturn(true);when(openAi.embeddingModel()).thenReturn("fixture-model");
        executor=new SimpleAsyncTaskExecutor();executor.setVirtualThreads(true);
        service=new MessageEmbeddingService(openAi,vectors,new AnswerSources(new JsonMapper()),executor,lifecycle);
    }
    @AfterEach void cleanup(){release.countDown();executor.close();}

    @Test
    void disabledQdrantNeverCallsEmbeddingOrSubmitsAnswerWork(){
        when(vectors.isEnabled()).thenReturn(false);
        service.questionCommitted(42,1,"question",deadline());service.answerCommitted(42,2,"answer");
        verifyNoInteractions(openAi);
        verify(vectors,never()).upsert(any(MessageVectorStore.SourceType.class),anyLong(),anyLong(),anyString(),any(),any());
    }

    @Test
    void oversizedOrEmptyInputsAreSkippedWholeWithoutTruncatingOrCallingProviders(){
        service.questionCommitted(42,1,"가".repeat(2731),deadline());
        service.questionCommitted(42,1," ",deadline());
        verifyNoInteractions(openAi);
    }

    @Test
    void queryEmbeddingRunsOnVirtualThreadAndOnlyCompletedVectorIsStored(){
        var virtual=new AtomicBoolean();
        when(openAi.embed("question")).thenAnswer(inv->{virtual.set(Thread.currentThread().isVirtual());return new float[]{1,2};});
        service.questionCommitted(42,1,"question",deadline());
        assertThat(virtual).isTrue();
        verify(vectors).upsert(eq(MessageVectorStore.SourceType.MESSAGE),eq(42L),eq(1L),eq("fixture-model"),argThat(v -> java.util.Arrays.equals(v,new float[]{1,2})),any());
    }

    @Test
    void providerOrQdrantFailureDoesNotEscapeAndLogsExcludePrivateMessages(CapturedOutput output){
        when(openAi.embed(anyString())).thenThrow(new IllegalStateException("private-question private-key"));
        assertThatCode(()->service.questionCommitted(42,1,"private-question",deadline())).doesNotThrowAnyException();
        doReturn(new float[]{1,2}).when(openAi).embed(anyString());
        doThrow(new IllegalStateException("private-answer private-key")).when(vectors).upsert(any(MessageVectorStore.SourceType.class),anyLong(),anyLong(),anyString(),any(),any());
        assertThatCode(()->service.questionCommitted(42,1,"private-answer",deadline())).doesNotThrowAnyException();
        assertThat(output.getAll()).contains("result=not_confirmed").doesNotContain("private-question","private-answer","private-key");
    }

    @Test
    void timedOutEmbeddingCannotStartLateVectorStorage() throws Exception {
        var finished=new CountDownLatch(1);
        when(openAi.embed(anyString())).thenAnswer(inv->{
            try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
            finished.countDown();return new float[]{1,2};
        });
        service.questionCommitted(42,1,"question",ChatDeadline.after(Duration.ofMillis(80)));
        release.countDown();assertThat(finished.await(1,TimeUnit.SECONDS)).isTrue();
        verify(vectors,never()).upsert(any(MessageVectorStore.SourceType.class),anyLong(),anyLong(),anyString(),any(),any());
    }

    @Test
    void answerDoesNotWaitAndEmbedsBodyWithoutSources() throws Exception {
        var started=new CountDownLatch(1);var stored=new CountDownLatch(1);
        when(openAi.embed("answer-body")).thenAnswer(inv->{started.countDown();release.await(2,TimeUnit.SECONDS);return new float[]{1,2};});
        doAnswer(inv->{stored.countDown();return null;}).when(vectors).upsert(eq(MessageVectorStore.SourceType.MESSAGE),eq(42L),eq(2L),anyString(),any(),any());
        service.answerCommitted(42,2,"answer-body\n@@SOURCES@@\n[{\"private\":true}]");
        assertThat(started.await(1,TimeUnit.SECONDS)).isTrue();assertThat(stored.getCount()).isEqualTo(1);
        release.countDown();assertThat(stored.await(1,TimeUnit.SECONDS)).isTrue();
        verify(openAi).embed("answer-body");
    }

    @Test
    void rejectedSubmissionAndUnavailableProviderDoNotTurnCommittedAnswerIntoFailure(){
        AsyncTaskExecutor rejected=mock(AsyncTaskExecutor.class);
        doThrow(new IllegalStateException("synthetic rejection")).when(rejected).execute(any(Runnable.class));
        var service=new MessageEmbeddingService(openAi,vectors,new AnswerSources(new JsonMapper()),rejected,lifecycle);
        assertThatCode(()->service.answerCommitted(42,2,"answer")).doesNotThrowAnyException();
        when(vectors.isEnabled()).thenThrow(new IllegalStateException("provider closed"));
        assertThatCode(()->service.answerCommitted(42,2,"answer")).doesNotThrowAnyException();
        assertThatCode(()->service.questionCommitted(42,1,"question",deadline())).doesNotThrowAnyException();
        verifyNoInteractions(openAi);
    }
    private ChatDeadline deadline(){return ChatDeadline.after(Duration.ofSeconds(2));}
}

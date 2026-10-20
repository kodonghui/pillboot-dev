package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 검색 선택 조건·가상 스레드·장애 폴백·재전송의 추가 임베딩 0회를 검증한다. */
class SummaryContextServiceTests {
    MessageVectorStore vectors;SummaryStore store;OpenAiClient ai;SimpleAsyncTaskExecutor executor;SummaryContextService service;
    @BeforeEach void setup(){
        vectors=mock(MessageVectorStore.class);store=mock(SummaryStore.class);ai=mock(OpenAiClient.class);
        when(vectors.isEnabled()).thenReturn(true);when(ai.embeddingModel()).thenReturn("fixture-model");
        executor=new SimpleAsyncTaskExecutor();executor.setVirtualThreads(true);service=new SummaryContextService(vectors,store,ai,executor);
    }
    @AfterEach void close(){executor.close();}
    @Test void missingVectorOrLessThanFourSummariesSkipsSearchEntirely(){
        var input=input(6);assertThat(service.enrich(42,61,input,null,deadline())).isSameAs(input);
        var shortInput=input(3);assertThat(service.enrich(42,61,shortInput,new float[]{1},deadline())).isSameAs(shortInput);
        verifyNoInteractions(vectors,store,ai);
    }
    @Test void queryRunsVirtuallyOutsideATransactionAndMysqlSuppliesTheText(){
        when(vectors.similarSummaryIds(eq(42L),eq("fixture-model"),any(),eq(List.of(4L,5L,6L)),any())).thenAnswer(inv->{
            assertThat(Thread.currentThread().isVirtual()).isTrue();assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return List.of(1L);
        });
        var original=new AnswerInput.Summary(1,1,10,"mysql-original");when(store.selectedEarlier(42,61,List.of(1L),List.of(4L,5L,6L))).thenReturn(List.of(original));
        var result=service.enrich(42,61,input(6),new float[]{1},deadline());assertThat(result.similarSummaries()).containsExactly(original);assertThat(result.recentSummaries()).hasSize(3);
        verify(ai,never()).embed(anyString());
    }
    @Test void queryFailureKeepsTheRecentContextAndDoesNotReadUntrustedIds(){
        when(vectors.similarSummaryIds(anyLong(),anyString(),any(),anyList(),any())).thenThrow(new IllegalStateException("synthetic"));
        var input=input(6);assertThat(service.enrich(42,61,input,new float[]{1},deadline())).isSameAs(input);verifyNoInteractions(store);
    }
    @Test void retryReadsAnExistingVectorWithoutGeneratingAnotherEmbedding(){
        when(vectors.questionVector(eq(42L),eq(100L),eq("fixture-model"),any())).thenReturn(new float[]{1});
        assertThat(service.savedQuestionVector(42,100,deadline())).containsExactly(1);
        when(vectors.questionVector(eq(42L),eq(100L),eq("fixture-model"),any())).thenReturn(null);
        assertThat(service.savedQuestionVector(42,100,deadline())).isNull();verify(ai,never()).embed(anyString());
    }
    private AnswerInput input(long count){return new AnswerInput(1990,"MALE",false,"question",List.of(),List.of(),false,
            List.of(new AnswerInput.Summary(4,31,40,"recent4"),new AnswerInput.Summary(5,41,50,"recent5"),new AnswerInput.Summary(6,51,60,"recent6")),List.of(),count);}
    private ChatDeadline deadline(){return ChatDeadline.after(Duration.ofSeconds(2));}
}

package org.semiproject.pillbadeumserver.chat.service;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.JsonWithInt;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 SDK 타입으로 저장 벡터의 식별 검증·검색 필터·시간 예산을 확인한다. */
class MessageVectorQueryTests {
    QdrantClient client;MessageVectorStore store;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        client=mock(QdrantClient.class);var provider=(ObjectProvider<QdrantClient>)mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);store=new MessageVectorStore(provider,"fixture",3);
    }
    @Test void savedQuestionRequiresTheExactPointSourceRoomModelAndVector(){
        var uuid=UUID.nameUUIDFromBytes("MESSAGE:42:7".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var point=Points.RetrievedPoint.newBuilder().setId(io.qdrant.client.PointIdFactory.id(uuid))
                .putAllPayload(payload("MESSAGE",42,7,"model-a"))
                .setVectors(Points.VectorsOutput.newBuilder().setVector(Points.VectorOutput.newBuilder()
                        .setDense(Points.DenseVector.newBuilder().addAllData(List.of(1f,2f,3f))))).build();
        when(client.retrieveAsync(any(Points.GetPoints.class),any(Duration.class))).thenReturn(Futures.immediateFuture(List.of(point)));
        assertThat(store.questionVector(42,7,"model-a",deadline())).containsExactly(1,2,3);
        for(var bad:List.of(payload("SUMMARY",42,7,"model-a"),payload("MESSAGE",43,7,"model-a"),
                payload("MESSAGE",42,8,"model-a"),payload("MESSAGE",42,7,"model-b"))){
            when(client.retrieveAsync(any(Points.GetPoints.class),any(Duration.class)))
                    .thenReturn(Futures.immediateFuture(List.of(point.toBuilder().clearPayload().putAllPayload(bad).build())));
            assertThat(store.questionVector(42,7,"model-a",deadline())).isNull();
        }
        when(client.retrieveAsync(any(Points.GetPoints.class),any(Duration.class))).thenReturn(Futures.immediateFuture(List.of()));
        assertThat(store.questionVector(42,7,"model-a",deadline())).isNull();
    }
    @Test void queryFiltersRoomTypeModelAndRecentIdsAndRejectsInvalidReturnedPayloads(){
        var valid=Points.ScoredPoint.newBuilder().putAllPayload(payload("SUMMARY",42,1,"model-a")).build();
        when(client.queryAsync(any(Points.QueryPoints.class),any(Duration.class))).thenReturn(Futures.immediateFuture(List.of(valid)));
        assertThat(store.similarSummaryIds(42,"model-a",new float[]{1,2,3},List.of(4L,5L,6L),deadline())).containsExactly(1L);
        var capture=ArgumentCaptor.forClass(Points.QueryPoints.class);verify(client).queryAsync(capture.capture(),eq(Duration.ofSeconds(3)));
        var request=capture.getValue();assertThat(request.getLimit()).isEqualTo(3);
        assertThat(request.getFilter().getMustList()).containsExactlyInAnyOrder(io.qdrant.client.ConditionFactory.match("session_id",42L),
                io.qdrant.client.ConditionFactory.matchKeyword("source_type","SUMMARY"),io.qdrant.client.ConditionFactory.matchKeyword("model","model-a"));
        assertThat(request.getFilter().getMustNotList()).containsExactly(io.qdrant.client.ConditionFactory.matchValues("source_id",List.of(4L,5L,6L)));
        for(var bad:List.of(payload("MESSAGE",42,1,"model-a"),payload("SUMMARY",43,1,"model-a"),
                payload("SUMMARY",42,1,"model-b"),payload("SUMMARY",42,4,"model-a"),payload("SUMMARY",42,0,"model-a"))){
            when(client.queryAsync(any(Points.QueryPoints.class),any(Duration.class)))
                    .thenReturn(Futures.immediateFuture(List.of(valid.toBuilder().clearPayload().putAllPayload(bad).build())));
            assertThat(store.similarSummaryIds(42,"model-a",new float[]{1,2,3},List.of(4L,5L,6L),deadline())).isEmpty();
        }
    }
    @Test void queryUsesTheRemainingRequestBudgetAndDoesNotRetry(){
        var pending=SettableFuture.<List<Points.ScoredPoint>>create();
        when(client.queryAsync(any(Points.QueryPoints.class),any(Duration.class))).thenReturn(pending);
        assertThatThrownBy(()->store.similarSummaryIds(42,"model-a",new float[]{1,2,3},List.of(),ChatDeadline.after(Duration.ofMillis(80))))
                .isInstanceOf(org.semiproject.pillbadeumserver.common.exception.BusinessException.class);
        verify(client,times(1)).queryAsync(any(Points.QueryPoints.class),any(Duration.class));assertThat(pending.isCancelled()).isTrue();
    }
    private Map<String,JsonWithInt.Value> payload(String type,long session,long source,String model){
        return Map.of("source_type",io.qdrant.client.ValueFactory.value(type),"session_id",io.qdrant.client.ValueFactory.value(session),
                "source_id",io.qdrant.client.ValueFactory.value(source),"model",io.qdrant.client.ValueFactory.value(model));
    }
    private ChatDeadline deadline(){return ChatDeadline.after(Duration.ofSeconds(2));}
}

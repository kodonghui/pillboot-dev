package org.semiproject.pillbadeumserver.chat.service;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 SDK의 요청·결과 타입으로 UUID/payload·차원·컬렉션·시간 초과 경계를 검증한다. */
class MessageVectorStoreTests {
    QdrantClient client;
    MessageVectorStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        client = mock(QdrantClient.class);
        var provider = (ObjectProvider<QdrantClient>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        store = new MessageVectorStore(provider, "fixture", 3);
        when(client.getCollectionInfoAsync(anyString(), any(Duration.class))).thenReturn(Futures.immediateFuture(info(3, Collections.Distance.Cosine)));
        when(client.upsertAsync(any(Points.UpsertPoints.class), any(Duration.class))).thenReturn(
                Futures.immediateFuture(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Completed).build()));
    }

    @Test
    void stablePointIdsAndOnlyFourPayloadFieldsWithAcknowledgedPersistence() {
        store.upsert(42,1201,"model-a",new float[]{1,2,3},deadline());
        store.upsert(42,1201,"model-a",new float[]{3,2,1},deadline());
        store.upsert(43,1201,"model-a",new float[]{1,2,3},deadline());
        var capture=ArgumentCaptor.forClass(Points.UpsertPoints.class);
        verify(client,times(3)).upsertAsync(capture.capture(),eq(Duration.ofSeconds(3)));
        var a=capture.getAllValues().get(0);var b=capture.getAllValues().get(1);var c=capture.getAllValues().get(2);
        assertThat(a.getWait()).isTrue();
        assertThat(a.getPoints(0).getId()).isEqualTo(b.getPoints(0).getId()).isNotEqualTo(c.getPoints(0).getId());
        var payload=a.getPoints(0).getPayloadMap();
        assertThat(payload).containsOnlyKeys("source_type","source_id","session_id","model");
        assertThat(payload.get("source_type").getStringValue()).isEqualTo("MESSAGE");
        assertThat(payload.get("source_id").getIntegerValue()).isEqualTo(1201);
        assertThat(payload.get("session_id").getIntegerValue()).isEqualTo(42);
        assertThat(payload.get("model").getStringValue()).isEqualTo("model-a");
        verify(client,never()).createCollectionAsync(anyString(),any(Collections.VectorParams.class),any(Duration.class));
    }

    @Test
    void messageAndSummaryWithTheSameNumericIdUseDistinctPointsAndTheSummaryRemainsIdempotent() {
        store.upsert(42,1,"model-a",new float[]{1,2,3},deadline());
        store.upsert(MessageVectorStore.SourceType.SUMMARY,42,1,"model-a",new float[]{1,2,3},deadline());
        store.upsert(MessageVectorStore.SourceType.SUMMARY,42,1,"model-a",new float[]{3,2,1},deadline());
        var capture=ArgumentCaptor.forClass(Points.UpsertPoints.class);
        verify(client,times(3)).upsertAsync(capture.capture(),any(Duration.class));
        var points=capture.getAllValues().stream().map(r->r.getPoints(0)).toList();
        assertThat(points.get(0).getId()).isNotEqualTo(points.get(1).getId());
        assertThat(points.get(1).getId()).isEqualTo(points.get(2).getId());
        assertThat(points.get(1).getPayloadMap().get("source_type").getStringValue()).isEqualTo("SUMMARY");
    }

    @Test
    void missingCollectionIsCreatedWithCosineAndRechecked() {
        when(client.getCollectionInfoAsync(anyString(),any(Duration.class)))
                .thenReturn(Futures.immediateFailedFuture(Status.NOT_FOUND.asRuntimeException()),Futures.immediateFuture(info(3,Collections.Distance.Cosine)));
        when(client.createCollectionAsync(anyString(),any(Collections.VectorParams.class),any(Duration.class)))
                .thenReturn(Futures.immediateFuture(Collections.CollectionOperationResponse.newBuilder().setResult(true).build()));
        store.upsert(42,1201,"model-a",new float[]{1,2,3},deadline());
        var capture=ArgumentCaptor.forClass(Collections.VectorParams.class);
        verify(client).createCollectionAsync(eq(store.collectionName("model-a")),capture.capture(),eq(Duration.ofSeconds(3)));
        assertThat(capture.getValue().getSize()).isEqualTo(3);
        assertThat(capture.getValue().getDistance()).isEqualTo(Collections.Distance.Cosine);
        verify(client,times(2)).getCollectionInfoAsync(anyString(),any(Duration.class));
    }

    @Test
    void simultaneousCreatorDoesNotRecreateOrDeleteTheCollection() {
        when(client.getCollectionInfoAsync(anyString(),any(Duration.class)))
                .thenReturn(Futures.immediateFailedFuture(Status.NOT_FOUND.asRuntimeException()),Futures.immediateFuture(info(3,Collections.Distance.Cosine)));
        when(client.createCollectionAsync(anyString(),any(Collections.VectorParams.class),any(Duration.class)))
                .thenReturn(Futures.immediateFailedFuture(Status.ALREADY_EXISTS.asRuntimeException()));
        assertThatCode(()->store.upsert(42,1201,"model-a",new float[]{1,2,3},deadline())).doesNotThrowAnyException();
        verify(client).upsertAsync(any(Points.UpsertPoints.class),any(Duration.class));
        verify(client,never()).deleteCollectionAsync(anyString());
        verify(client,never()).recreateCollectionAsync(anyString(),any(Collections.VectorParams.class));
    }

    @Test
    void incompatibleExistingVectorShapeIsPreservedWithoutWrites() {
        for(var wrong:List.of(info(2,Collections.Distance.Cosine),info(3,Collections.Distance.Dot),Collections.CollectionInfo.getDefaultInstance())) {
            when(client.getCollectionInfoAsync(anyString(),any(Duration.class))).thenReturn(Futures.immediateFuture(wrong));
            assertThatThrownBy(()->store.upsert(42,1201,"model-a",new float[]{1,2,3},deadline())).isInstanceOf(ExternalApiException.class);
        }
        verify(client,never()).upsertAsync(any(Points.UpsertPoints.class),any(Duration.class));
        verify(client,never()).createCollectionAsync(anyString(),any(Collections.VectorParams.class),any(Duration.class));
    }

    @Test
    void malformedVectorsFailBeforeNetworkAndModelsUseDifferentCollections() {
        for(float[] vector:List.of(new float[]{1,2},new float[]{0,0,0},new float[]{1,Float.NaN,2},new float[]{1,Float.POSITIVE_INFINITY,2})) {
            assertThatThrownBy(()->store.upsert(42,1201,"model-a",vector,deadline())).isInstanceOf(ExternalApiException.class);
        }
        verifyNoInteractions(client);
        assertThat(store.collectionName("model-a")).isNotEqualTo(store.collectionName("model-b"));
    }

    @Test
    void expiredOrUnavailableReadsDoNotLaunchUpsertsOrRetry() {
        var pending=SettableFuture.<Collections.CollectionInfo>create();
        when(client.getCollectionInfoAsync(anyString(),any(Duration.class))).thenReturn(pending);
        assertThatThrownBy(()->store.upsert(42,1201,"model-a",new float[]{1,2,3},ChatDeadline.after(Duration.ofMillis(70))))
                .isInstanceOf(ExternalApiException.class);
        assertThat(pending.isCancelled()).isTrue();
        when(client.getCollectionInfoAsync(anyString(),any(Duration.class))).thenReturn(Futures.immediateFailedFuture(Status.UNAVAILABLE.asRuntimeException()));
        assertThatThrownBy(()->store.upsert(42,1201,"model-a",new float[]{1,2,3},deadline())).isInstanceOf(RuntimeException.class);
        verify(client,times(2)).getCollectionInfoAsync(anyString(),any(Duration.class));
        verify(client,never()).upsertAsync(any(Points.UpsertPoints.class),any(Duration.class));
    }

    @Test
    void acknowledgementWithoutCompletedStatusIsNotConfirmedAsStored() {
        when(client.upsertAsync(any(Points.UpsertPoints.class),any(Duration.class)))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Acknowledged).build()));
        assertThatThrownBy(()->store.upsert(42,1201,"model-a",new float[]{1,2,3},deadline())).isInstanceOf(ExternalApiException.class);
    }

    static Collections.CollectionInfo info(int size,Collections.Distance distance) {
        return Collections.CollectionInfo.newBuilder().setConfig(Collections.CollectionConfig.newBuilder().setParams(
                Collections.CollectionParams.newBuilder().setVectorsConfig(Collections.VectorsConfig.newBuilder().setParams(
                        Collections.VectorParams.newBuilder().setSize(size).setDistance(distance))))).build();
    }
    private ChatDeadline deadline(){return ChatDeadline.after(Duration.ofSeconds(2));}
}

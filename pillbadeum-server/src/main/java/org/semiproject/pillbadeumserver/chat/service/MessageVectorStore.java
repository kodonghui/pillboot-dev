package org.semiproject.pillbadeumserver.chat.service;

import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/** CHAT-004·007: MySQL 메시지 ID를 기준으로 Qdrant MESSAGE 포인트를 멱등 저장한다. 원문은 복제하지 않는다. */
@Component
public class MessageVectorStore {
    private final ObjectProvider<QdrantClient> clients;
    private final String prefix;
    private final int size;

    public MessageVectorStore(ObjectProvider<QdrantClient> clients,
                              @Value("${qdrant.collection-prefix:chat_memory}") String prefix,
                              @Value("${qdrant.vector-size:1536}") int size) {
        if (prefix == null || !prefix.matches("[a-zA-Z0-9_-]{1,64}") || size < 1 || size > 65536) {
            throw new IllegalArgumentException("Qdrant 컬렉션 접두사·벡터 크기를 확인하세요.");
        }
        this.clients = clients;
        this.prefix = prefix;
        this.size = size;
    }

    /** Qdrant off이면 임베딩 API도 호출하지 않도록 조정 계층에서 먼저 확인한다. */
    public boolean isEnabled() { return clients.getIfAvailable() != null; }

    /** 같은 차원의 다른 모델도 섞이지 않도록 모델별 컬렉션을 사용한다. 기존 컬렉션을 삭제·재생성하지 않는다. */
    public String collectionName(String model) {
        return prefix + "_" + UUID.nameUUIDFromBytes(model.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "") + "_" + size;
    }

    /**
     * @param sessionId 권한 확인한 방 번호
     * @param messageId 커밋된 메시지 번호. 같은 방·메시지는 같은 UUID로 덮어써 중복 포인트를 만들지 않는다.
     * @param vector 검증할 모델의 float 벡터. 크기·유한값·0 벡터를 검사한다.
     * @param requestDeadline 요청 전체 또는 답변 후처리의 종료 시점
     */
    public void upsert(long sessionId, long messageId, String model, float[] vector, ChatDeadline requestDeadline) {
        var client = clients.getIfAvailable();
        if (client == null) return;
        if (sessionId < 1 || messageId < 1 || model == null || model.isBlank() || vector == null || vector.length != size) {
            throw invalid();
        }
        boolean nonzero = false;
        for (float number : vector) {
            if (!Float.isFinite(number)) throw invalid();
            nonzero |= number != 0;
        }
        if (!nonzero) throw invalid();

        // 컬렉션 확인·필요 시 생성·저장 전체가 하나의 3초 예산을 공유한다. 자동 재시도는 없다.
        var deadline = requestDeadline.within(Duration.ofSeconds(3));
        String collection = collectionName(model);
        ensureCollection(client, collection, deadline);
        deadline.check();
        UUID pointId = UUID.nameUUIDFromBytes(("MESSAGE:" + sessionId + ":" + messageId).getBytes(StandardCharsets.UTF_8));
        var point = Points.PointStruct.newBuilder().setId(id(pointId)).setVectors(vectors(vector))
                .putPayload("source_type", value("MESSAGE")).putPayload("source_id", value(messageId))
                .putPayload("session_id", value(sessionId)).putPayload("model", value(model)).build();
        var request = Points.UpsertPoints.newBuilder().setCollectionName(collection).setWait(true).addPoints(point).build();
        var result = deadline.await(client.upsertAsync(request, Duration.ofSeconds(3)));
        if (result.getStatus() != Points.UpdateStatus.Completed) throw invalid();
    }

    private void ensureCollection(QdrantClient client, String collection, ChatDeadline deadline) {
        Collections.CollectionInfo info;
        try {
            info = deadline.await(client.getCollectionInfoAsync(collection, Duration.ofSeconds(3)));
        } catch (RuntimeException exception) {
            if (Status.fromThrowable(exception).getCode() != Status.Code.NOT_FOUND) throw exception;
            deadline.check();
            try {
                deadline.await(client.createCollectionAsync(collection, Collections.VectorParams.newBuilder()
                        .setSize(size).setDistance(Collections.Distance.Cosine).build(), Duration.ofSeconds(3)));
            } catch (RuntimeException createFailure) {
                // 다른 요청이 먼저 만들었을 수 있다. 재생성 대신 실제 설정을 다시 읽어 확인한다.
                deadline.check();
            }
            info = deadline.await(client.getCollectionInfoAsync(collection, Duration.ofSeconds(3)));
        }
        var config = info.getConfig().getParams().getVectorsConfig();
        if (!config.hasParams() || config.getParams().getSize() != size
                || config.getParams().getDistance() != Collections.Distance.Cosine) throw invalid();
    }

    private ExternalApiException invalid() {
        return ExternalApiException.failed("Qdrant", "벡터 또는 컬렉션 설정을 확인할 수 없습니다.", null);
    }
}

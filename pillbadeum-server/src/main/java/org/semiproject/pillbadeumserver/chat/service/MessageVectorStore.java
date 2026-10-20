package org.semiproject.pillbadeumserver.chat.service;

import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.matchValues;

/** CHAT-004·007·009: 커밋된 MySQL 메시지/요약 ID를 타입별 Qdrant 포인트로 저장한다. 원문은 복제하지 않는다. */
@Component
public class MessageVectorStore {
    public enum SourceType { MESSAGE, SUMMARY }
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
        upsert(SourceType.MESSAGE, sessionId, messageId, model, vector, requestDeadline);
    }

    /** 타입도 UUID 이름에 포함한다. 숫자가 같은 메시지·요약이 서로 덮어쓰지 않는다. */
    public void upsert(SourceType sourceType, long sessionId, long sourceId, String model, float[] vector, ChatDeadline requestDeadline) {
        var client = clients.getIfAvailable();
        if (client == null) return;
        if (sourceType == null || sessionId < 1 || sourceId < 1 || model == null || model.isBlank()) {
            throw invalid();
        }
        validateVector(vector);

        // 컬렉션 확인·필요 시 생성·저장 전체가 하나의 3초 예산을 공유한다. 자동 재시도는 없다.
        var deadline = requestDeadline.within(Duration.ofSeconds(3));
        String collection = collectionName(model);
        ensureCollection(client, collection, deadline);
        deadline.check();
        UUID pointId = pointId(sourceType, sessionId, sourceId);
        var point = Points.PointStruct.newBuilder().setId(id(pointId)).setVectors(vectors(vector))
                .putPayload("source_type", value(sourceType.name())).putPayload("source_id", value(sourceId))
                .putPayload("session_id", value(sessionId)).putPayload("model", value(model)).build();
        var request = Points.UpsertPoints.newBuilder().setCollectionName(collection).setWait(true).addPoints(point).build();
        var result = deadline.await(client.upsertAsync(request, Duration.ofSeconds(3)));
        if (result.getStatus() != Points.UpdateStatus.Completed) throw invalid();
    }

    /** PROF-004: 같은 앱 접두사의 모든 모델/차원에서 해당 방의 MESSAGE·SUMMARY를 제거함. */
    public void deleteSessions(List<Long> sessionIds) {
        var client = clients.getIfAvailable();
        if (client == null) throw invalid(); // off 동안에도 영속 정리 요청을 완료로 지우지 않음.
        if (sessionIds.isEmpty()) return;
        var deadline = ChatDeadline.after(Duration.ofSeconds(3));
        var names = deadline.await(client.listCollectionsAsync(Duration.ofSeconds(3)));
        var filter = Common.Filter.newBuilder().addMust(matchValues("session_id", sessionIds)).build();
        for (String collection : names) {
            if (!collection.matches(java.util.regex.Pattern.quote(prefix) + "_[a-f0-9]{32}_[0-9]+")) continue;
            deadline.check();
            var request = Points.DeletePoints.newBuilder().setCollectionName(collection).setWait(true)
                    .setPoints(Points.PointsSelector.newBuilder().setFilter(filter)).build();
            var result = deadline.await(client.deleteAsync(request, Duration.ofSeconds(3)));
            if (result.getStatus() != Points.UpdateStatus.Completed) throw invalid();
        }
    }

    /** CHAT-004 retry: 기존 질문 벡터만 조회한다. 타입·방·원문·모델이 맞지 않거나 벡터가 없으면 사용하지 않는다. */
    public float[] questionVector(long sessionId, long messageId, String model, ChatDeadline requestDeadline) {
        var client = clients.getIfAvailable();
        if (client == null) return null;
        if (sessionId < 1 || messageId < 1 || model == null || model.isBlank()) throw invalid();
        var expectedId = id(pointId(SourceType.MESSAGE, sessionId, messageId));
        var request = Points.GetPoints.newBuilder().setCollectionName(collectionName(model)).addIds(expectedId)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true))
                .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(true)).build();
        var deadline = requestDeadline.within(Duration.ofSeconds(3));
        var points = deadline.await(client.retrieveAsync(request, Duration.ofSeconds(3)));
        if (points.size() != 1 || !points.getFirst().getId().equals(expectedId)) return null;
        var point = points.getFirst(); var payload = point.getPayloadMap();
        if (!sourceMatches(payload, SourceType.MESSAGE, sessionId, model)
                || payload.get("source_id").getIntegerValue() != messageId || !point.getVectors().hasVector()) return null;
        var output = point.getVectors().getVector();
        var values = output.hasDense() ? output.getDense().getDataList() : output.getDataList();
        float[] vector = new float[values.size()];
        for (int i = 0; i < vector.length; i++) vector[i] = values.get(i);
        validateVector(vector);
        return vector;
    }

    /** CHAT-006: 같은 방의 SUMMARY만 3초·1회 조회한다. 최근 요약 ID는 필터로 제외하고 본문은 반환하지 않는다. */
    public List<Long> similarSummaryIds(long sessionId, String model, float[] vector, List<Long> recentIds, ChatDeadline requestDeadline) {
        var client = clients.getIfAvailable();
        if (client == null) return List.of();
        if (sessionId < 1 || model == null || model.isBlank() || recentIds == null) throw invalid();
        validateVector(vector);
        var filter = Common.Filter.newBuilder().addMust(match("session_id", sessionId))
                .addMust(matchKeyword("source_type", "SUMMARY")).addMust(matchKeyword("model", model));
        if (!recentIds.isEmpty()) filter.addMustNot(matchValues("source_id", recentIds));
        var request = Points.QueryPoints.newBuilder().setCollectionName(collectionName(model))
                .setQuery(io.qdrant.client.QueryFactory.nearest(vector)).setFilter(filter).setLimit(3)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true))
                .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(false)).build();
        var deadline = requestDeadline.within(Duration.ofSeconds(3));
        var points = deadline.await(client.queryAsync(request, Duration.ofSeconds(3)));
        var ids = new LinkedHashSet<Long>();
        for (var point : points.stream().limit(3).toList()) {
            var payload = point.getPayloadMap();
            if (!sourceMatches(payload, SourceType.SUMMARY, sessionId, model)) continue;
            long sourceId = payload.get("source_id").getIntegerValue();
            if (!recentIds.contains(sourceId)) ids.add(sourceId);
        }
        return List.copyOf(ids);
    }

    private boolean sourceMatches(Map<String, JsonWithInt.Value> payload, SourceType type, long sessionId, String model) {
        var source = payload.get("source_id"); var session = payload.get("session_id");
        var sourceType = payload.get("source_type"); var sourceModel = payload.get("model");
        return source != null && source.hasIntegerValue() && source.getIntegerValue() > 0
                && session != null && session.hasIntegerValue() && session.getIntegerValue() == sessionId
                && sourceType != null && sourceType.hasStringValue() && type.name().equals(sourceType.getStringValue())
                && sourceModel != null && sourceModel.hasStringValue() && model.equals(sourceModel.getStringValue());
    }

    private static UUID pointId(SourceType type, long sessionId, long sourceId) {
        return UUID.nameUUIDFromBytes((type.name() + ":" + sessionId + ":" + sourceId).getBytes(StandardCharsets.UTF_8));
    }

    private void validateVector(float[] vector) {
        if (vector == null || vector.length != size) throw invalid();
        boolean nonzero = false;
        for (float number : vector) { if (!Float.isFinite(number)) throw invalid(); nonzero |= number != 0; }
        if (!nonzero) throw invalid();
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

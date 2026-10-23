package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** CHAT-008: 등록 품목코드의 필요한 DUR 유형을 조회한다. 외부 작업에는 DB 쓰기가 없다. */
@Service
public class DurLookupService {
    private static final Logger log = LoggerFactory.getLogger(DurLookupService.class);
    private static final int PAGE_SIZE = 100;
    private final PublicDataClient client;
    private final AsyncTaskExecutor executor;
    private final int batchSize;

    public DurLookupService(PublicDataClient client,
                            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor,
                            @Value("${public-data.max-concurrent-calls:4}") int concurrency) {
        this.client = client;
        this.executor = executor;
        this.batchSize = Math.max(1, Math.min(10, concurrency));
    }

    /** 전체 등록 목록을 중복 제거한다. 요청 시간 안에서 모든 품목을 조회하고 실패 유형을 미확인으로 남긴다. */
    public List<DurLookup> collect(AnswerInput input, ChatDeadline requestDeadline) {
        LocalDate today = LocalDate.now(TimeZoneConfig.ZONE);
        var unique = new LinkedHashMap<String, AnswerInput.Medication>();
        input.medications().forEach(m -> unique.putIfAbsent(m.itemSeq(), m));
        var types = requiredTypes(input, unique.size(), today);
        return collectItems(List.copyOf(unique.keySet()), types, requestDeadline);
    }

    /** COMMON-005·점검 도구: 환자 사본 없이 명시한 품목·유형을 조회한다. 등록 뒤 전 유형 선조회에도 재사용한다. */
    public List<DurLookup> collectItems(List<String> itemSeqs, Set<DurLookup.Type> types, ChatDeadline requestDeadline) {
        return collectItems(itemSeqs, types, requestDeadline, batchSize);
    }

    /** 등록 후 선조회는 최대2자리만 사용하고 화면 조회가 쓸 자리를 남김. */
    public List<DurLookup> collectForPrefetch(List<String> itemSeqs, Set<DurLookup.Type> types, ChatDeadline requestDeadline) {
        if (batchSize < 2) return List.of();
        return collectItems(itemSeqs, types, requestDeadline, Math.min(2, batchSize - 1));
    }

    private List<DurLookup> collectItems(List<String> itemSeqs, Set<DurLookup.Type> types,
                                        ChatDeadline requestDeadline, int concurrency) {
        LocalDate today = LocalDate.now(TimeZoneConfig.ZONE);
        var unique = itemSeqs.stream().distinct().toList();
        var orderedTypes = types.isEmpty() ? EnumSet.noneOf(DurLookup.Type.class) : EnumSet.copyOf(types);
        var requests = new ArrayList<Request>();
        var results = new ArrayList<DurLookup>();
        for (String code : unique) {
            for (var type : orderedTypes) {
                requests.add(new Request(code, type));
            }
        }
        var deadline = requestDeadline.within(Duration.ofSeconds(30));
        for (int offset = 0; offset < requests.size(); offset += concurrency) {
            var batch = requests.subList(offset, Math.min(requests.size(), offset + concurrency));
            var tasks = new ArrayList<Future<DurLookup>>();
            try {
                for (var request : batch) tasks.add(executor.submit(() -> lookup(request, deadline)));
                for (int i = 0; i < tasks.size(); i++) {
                    DurLookup lookup;
                    try { lookup = deadline.await(tasks.get(i)); }
                    catch (BusinessException exception) { lookup = DurLookup.failed(batch.get(i).code(), batch.get(i).type(), today); }
                    results.add(lookup);
                }
            } finally {
                tasks.stream().filter(task -> !task.isDone()).forEach(task -> task.cancel(true));
            }
        }
        return List.copyOf(results);
    }

    static EnumSet<DurLookup.Type> requiredTypes(AnswerInput input, int drugCount, LocalDate today) {
        var types = EnumSet.of(DurLookup.Type.AGE);
        long years = today.getYear() - input.birthYear();
        if (drugCount > 1) types.add(DurLookup.Type.INTERACTION);
        if (years >= 65 || years < 0 || years > 130) types.add(DurLookup.Type.ELDERLY);
        if (!"MALE".equals(input.gender()) && !Boolean.FALSE.equals(input.pregnant())) types.add(DurLookup.Type.PREGNANCY);
        return types;
    }

    /** 페이지 일부만 읽은 결과를 성공으로 쓰지 않는다. 한 유형의 모든 페이지가 검증돼야 캐시 교체가 가능하다. */
    private DurLookup lookup(Request request, ChatDeadline deadline) {
        var today = LocalDate.now(TimeZoneConfig.ZONE);
        try {
            var rows = new ArrayList<DurLookup.Row>();
            int expectedTotal = -1;
            for (int page = 1; ; page++) {
                deadline.check();
                JsonNode body = client.get(request.type().path(), Map.of("itemSeq", request.code(), "pageNo", page, "numOfRows", PAGE_SIZE), deadline.remaining());
                deadline.check();
                String count = body.path("totalCount").asString("");
                if (!count.matches("\\d{1,9}")) throw invalid();
                int total = Integer.parseInt(count);
                if (expectedTotal != -1 && expectedTotal != total) throw invalid();
                expectedTotal = total;
                if (body.has("pageNo") && body.path("pageNo").asInt(-1) != page) throw invalid();
                var items = PublicDataClient.items(body);
                int expectedSize = Math.min(PAGE_SIZE, Math.max(0, total - (page - 1) * PAGE_SIZE));
                if (items.size() != expectedSize) throw invalid();
                for (var item : items) rows.add(parse(request, item, today));
                if (page * PAGE_SIZE >= total) break;
            }
            // 동일 페이지가 반복되거나 응답의 전체 수와 실내용이 맞지 않으면 완전한 조회로 인정하지 않는다.
            if (rows.stream().distinct().count() != rows.size()) throw invalid();
            var updated = rows.stream().map(DurLookup.Row::updatedAt).min(LocalDate::compareTo).orElse(today);
            return new DurLookup(request.code(), request.type(), true, List.copyOf(rows), updated);
        } catch (BusinessException | NumberFormatException exception) {
            log.warn("[DUR 조회] type={} result=unavailable", request.type());
            return DurLookup.failed(request.code(), request.type(), today);
        }
    }

    private DurLookup.Row parse(Request request, JsonNode item, LocalDate today) {
        if (!request.code().equals(item.path("ITEM_SEQ").asString(""))) throw invalid();
        String label = text(item, "TYPE_NAME");
        if (!label.isEmpty() && !request.type().cacheAliases().contains(label)) throw invalid();
        String content = text(item, "PROHBT_CONTENT");
        String partner = text(item, "MIXTURE_ITEM_SEQ");
        if (content.isBlank()) {
            // 상대 NULL인 빈 행은 병용금기 상대 없음 표현일 뿐 다른 유형의 확인 증거는 아니다.
            if (request.type() != DurLookup.Type.INTERACTION || !partner.isBlank()) throw invalid();
        } else if (request.type() == DurLookup.Type.INTERACTION && partner.isBlank()) throw invalid();
        if (partner.length() > 100) throw invalid();
        LocalDate updated = OfficialDrugEvidence.parseDate(text(item, "CHANGE_DATE"));
        if (updated == null) updated = OfficialDrugEvidence.parseDate(text(item, "NOTIFICATION_DATE"));
        String ingredient = text(item, request.type() == DurLookup.Type.INTERACTION ? "INGR_KOR_NAME" : "INGR_NAME");
        return new DurLookup.Row(partner.isBlank() ? null : partner, ingredient, text(item, "CLASS_NAME"), content,
                updated == null || updated.isAfter(today) ? today : updated);
    }

    private String text(JsonNode item, String field) {
        var node = item.path(field);
        if (!node.isMissingNode() && !node.isNull() && !node.isString()) throw invalid();
        String raw = node.asString("");
        if (raw.length() > 8_000) throw invalid();
        return OfficialDrugEvidence.clean(raw);
    }

    private static ExternalApiException invalid() {
        return ExternalApiException.failed("DUR", "DUR 응답을 확인할 수 없습니다.", null);
    }

    private record Request(String code, DurLookup.Type type) {}
}

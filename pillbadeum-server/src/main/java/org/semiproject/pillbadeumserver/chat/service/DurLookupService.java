package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        // 묶음 끝을 기다리지 않음. 페이지가 많은 품목이 마감을 다 써도 뒤 품목의 첫 페이지가 먼저 차례를 받음.
        var run = new Run(requestDeadline.within(Duration.ofSeconds(30)), new Semaphore(concurrency, true), concurrency);
        var tasks = new ArrayList<Future<DurLookup>>();
        try {
            for (var request : requests) tasks.add(executor.submit(() -> lookup(request, run)));
            for (int i = 0; i < tasks.size(); i++) {
                var task = tasks.get(i);
                DurLookup lookup;
                // 앞 품목을 기다리다 마감이 지나도 이미 끝난 조회의 결과는 버리지 않음.
                try { lookup = task.state() == Future.State.SUCCESS ? task.resultNow() : run.deadline().await(task); }
                catch (BusinessException exception) { lookup = DurLookup.failed(requests.get(i).code(), requests.get(i).type(), today); }
                results.add(lookup);
            }
        } finally {
            tasks.stream().filter(task -> !task.isDone()).forEach(task -> task.cancel(true));
        }
        return List.copyOf(results);
    }

    static EnumSet<DurLookup.Type> requiredTypes(AnswerInput input, int drugCount, LocalDate today) {
        var types = EnumSet.of(DurLookup.Type.AGE);
        long years = today.getYear() - input.birthYear();
        if (drugCount > 1) types.add(DurLookup.Type.INTERACTION);
        if (years >= 65 || years < 0 || years > 130) types.add(DurLookup.Type.ELDERLY);
        if (ChatQuestionPurpose.pregnancy(input.question())
                || (!"MALE".equals(input.gender()) && !Boolean.FALSE.equals(input.pregnant()))) types.add(DurLookup.Type.PREGNANCY);
        return types;
    }

    /** 페이지 일부만 읽은 결과를 성공으로 쓰지 않는다. 한 유형의 모든 페이지가 검증돼야 캐시 교체가 가능하다. */
    private DurLookup lookup(Request request, Run run) {
        var today = LocalDate.now(TimeZoneConfig.ZONE);
        try {
            var first = readPage(request, 1, -1, run, today);
            var pages = new ConcurrentSkipListMap<Integer, List<DurLookup.Row>>();
            pages.put(1, first.rows());
            int lastPage = (first.total() + PAGE_SIZE - 1) / PAGE_SIZE;
            var next = new AtomicInteger(2);
            var helpers = new ArrayList<Future<?>>();
            try {
                // 호출 자리는 Run.slots가 정함. 일꾼 수를 묶어 전체 수가 터무니없이 큰 응답에도 작업을 쌓지 않음.
                for (int i = 1; i < Math.min(run.workers(), lastPage - 1); i++)
                    helpers.add(executor.submit(() -> readPages(request, first.total(), lastPage, next, pages, run, today)));
                readPages(request, first.total(), lastPage, next, pages, run, today);
                for (var helper : helpers) run.deadline().await(helper);
            } finally {
                helpers.stream().filter(helper -> !helper.isDone()).forEach(helper -> helper.cancel(true));
            }
            var rows = pages.values().stream().flatMap(List::stream).toList();
            // 동일 페이지가 반복되거나 응답의 전체 수와 실내용이 맞지 않으면 완전한 조회로 인정하지 않는다.
            if (rows.stream().distinct().count() != rows.size()) throw invalid();
            var updated = rows.stream().map(DurLookup.Row::updatedAt).min(LocalDate::compareTo).orElse(today);
            return new DurLookup(request.code(), request.type(), true, rows, updated);
        } catch (BusinessException | NumberFormatException exception) {
            client.discard(request.type().path(),Map.of("itemSeq",request.code()));
            log.warn("[DUR 조회] type={} result=unavailable", request.type());
            return DurLookup.failed(request.code(), request.type(), today);
        }
    }

    /** 한 페이지가 어긋나면 남은 페이지를 더 부르지 않는다. */
    private void readPages(Request request, int total, int lastPage, AtomicInteger next,
                           Map<Integer, List<DurLookup.Row>> pages, Run run, LocalDate today) {
        try {
            for (int page = next.getAndIncrement(); page <= lastPage; page = next.getAndIncrement())
                pages.put(page, readPage(request, page, total, run, today).rows());
        } catch (RuntimeException exception) {
            next.set(lastPage + 1);
            throw exception;
        }
    }

    private Page readPage(Request request, int page, int expectedTotal, Run run, LocalDate today) {
        JsonNode body = call(request, page, run);
        run.deadline().check();
        String count = body.path("totalCount").asString("");
        if (!count.matches("\\d{1,9}")) throw invalid();
        int total = Integer.parseInt(count);
        if (expectedTotal != -1 && expectedTotal != total) throw invalid();
        if (body.has("pageNo") && body.path("pageNo").asInt(-1) != page) throw invalid();
        var items = PublicDataClient.items(body);
        int expectedSize = Math.min(PAGE_SIZE, Math.max(0, total - (page - 1) * PAGE_SIZE));
        if (items.size() != expectedSize) throw invalid();
        var rows = new ArrayList<DurLookup.Row>();
        for (var item : items) rows.add(parse(request, item, today));
        return new Page(total, rows);
    }

    /** 이 조회에 허락된 자리 안에서만 부른다. 선조회가 화면 조회의 공공데이터 자리를 다 차지하지 않게 함. */
    private JsonNode call(Request request, int page, Run run) {
        try {
            if (!run.slots().tryAcquire(run.deadline().remaining().toNanos(), TimeUnit.NANOSECONDS))
                throw ExternalApiException.timeout("DUR", null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ExternalApiException.timeout("DUR", exception);
        }
        try {
            return client.get(request.type().path(), Map.of("itemSeq", request.code(), "pageNo", page, "numOfRows", PAGE_SIZE), run.deadline().remaining());
        } finally {
            run.slots().release();
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
    private record Page(int total, List<DurLookup.Row> rows) {}
    private record Run(ChatDeadline deadline, Semaphore slots, int workers) {}
}

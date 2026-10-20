package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import org.semiproject.pillbadeumserver.chat.domain.AnswerSource;
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
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;

/** CHAT-007: 등록한 품목코드로만 e약은요·허가정보를 조회한다. 공식 본문을 DB에 저장하지 않는다. */
@Service
public class OfficialDrugEvidence {
    public static final String EASY_PATH = "/1471000/DrbEasyDrugInfoService/getDrbEasyDrugList";
    public static final String PERMIT_PATH = "/1471000/DrugPrdtPrmsnInfoService08/getDrugPrdtPrmsnDtlInq08";
    private static final List<String> EASY_FIELDS = List.of("efcyQesitm", "useMethodQesitm", "atpnWarnQesitm",
            "atpnQesitm", "intrcQesitm", "seQesitm", "depositMethodQesitm");
    private static final List<String> PERMIT_FIELDS = List.of("EE_DOC_DATA", "UD_DOC_DATA", "NB_DOC_DATA",
            "PN_DOC_DATA", "STORAGE_METHOD");
    private static final Logger log = LoggerFactory.getLogger(OfficialDrugEvidence.class);
    private final PublicDataClient publicData;
    private final AsyncTaskExecutor executor;
    private final int batchSize;

    public OfficialDrugEvidence(PublicDataClient publicData,
                                @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor,
                                @Value("${public-data.max-concurrent-calls:4}") int maxConcurrentCalls) {
        this.publicData = publicData;
        this.executor = executor;
        this.batchSize = Math.max(1, Math.min(10, maxConcurrentCalls));
    }

    /** 최대 10품목을 적은 수씩 병렬 조회하고 40초와 요청 잔여 시간 중 작은 한도로 제한한다. */
    public Evidence collect(List<AnswerInput.Medication> medications, ChatDeadline requestDeadline) {
        var deadline = requestDeadline.within(Duration.ofSeconds(40));
        List<Lookup> lookups = new ArrayList<>();
        for (int offset = 0; offset < medications.size(); offset += batchSize) {
            try {
                deadline.check();
            } catch (BusinessException exception) {
                for (var remaining : medications.subList(offset, medications.size())) {
                    lookups.add(failed(remaining, AnswerSource.Type.EASY_DRUG));
                    lookups.add(failed(remaining, AnswerSource.Type.PERMIT));
                }
                break;
            }
            var batch = medications.subList(offset, Math.min(medications.size(), offset + batchSize));
            List<Future<List<Lookup>>> tasks = new ArrayList<>();
            try {
                for (var medication : batch) {
                    tasks.add(executor.submit(() -> List.of(
                            lookup(medication, AnswerSource.Type.EASY_DRUG, deadline),
                            lookup(medication, AnswerSource.Type.PERMIT, deadline))));
                }
                for (int i = 0; i < tasks.size(); i++) {
                    try {
                        lookups.addAll(deadline.await(tasks.get(i)));
                    } catch (BusinessException exception) {
                        lookups.add(failed(batch.get(i), AnswerSource.Type.EASY_DRUG));
                        lookups.add(failed(batch.get(i), AnswerSource.Type.PERMIT));
                    }
                }
            } finally {
                tasks.stream().filter(task -> !task.isDone()).forEach(task -> task.cancel(true));
            }
        }
        return new Evidence(List.copyOf(lookups));
    }

    private Lookup lookup(AnswerInput.Medication medication, AnswerSource.Type type, ChatDeadline deadline) {
        try {
            deadline.check();
            boolean easy = type == AnswerSource.Type.EASY_DRUG;
            var body = publicData.get(easy ? EASY_PATH : PERMIT_PATH, Map.of(
                    easy ? "itemSeq" : "item_seq", medication.itemSeq(), "pageNo", 1, "numOfRows", 1), deadline.remaining());
            deadline.check();
            String total = body.path("totalCount").asString("");
            if (!total.matches("\\d+")) throw invalid();
            var items = PublicDataClient.items(body);
            // 전체 건수와 반환 행 수가 모순되면 공식 근거로 사용하지 않는다.
            long totalCount = Long.parseLong(total);
            if (totalCount < items.size()) throw invalid();
            if (items.isEmpty() && totalCount == 0) {
                return new Lookup(source(medication, type, LocalDate.now(TimeZoneConfig.ZONE), false, false), Map.of(), false);
            }
            // 이름이 비슷한 다른 약을 근거로 쓰지 않고 품목코드가 완전히 일치한 결과만 사용한다.
            JsonNode item = items.stream().filter(value -> medication.itemSeq().equals(
                            value.path(easy ? "itemSeq" : "ITEM_SEQ").asString("")))
                    .findFirst().orElseThrow(OfficialDrugEvidence::invalid);
            Map<String, String> facts = new LinkedHashMap<>();
            for (String field : easy ? EASY_FIELDS : PERMIT_FIELDS) {
                String raw = item.path(field).asString("");
                if (raw.length() > 64_000) throw invalid();
                String text = clean(raw);
                if (!text.isBlank()) facts.put(field, text);
            }
            // 의료 정보를 중간에서 잘라 뜻을 바꾸지 않고 과도하게 큰 자료는 미확인으로 처리한다.
            if (facts.values().stream().mapToInt(String::length).sum() > 32_000) throw invalid();
            LocalDate cancellation = parseDate(item.path("CANCEL_DATE").asString(""));
            String cancellationName = item.path("CANCEL_NAME").asString("");
            boolean cancelled = !easy && ((cancellation != null && !cancellation.isAfter(LocalDate.now(TimeZoneConfig.ZONE)))
                    || cancellationName.contains("취하") || cancellationName.contains("취소"));
            LocalDate updated = parseDate(item.path(easy ? "updateDe" : "CHANGE_DATE").asString(""));
            return new Lookup(source(medication, type, updated == null ? LocalDate.now(TimeZoneConfig.ZONE) : updated,
                    !facts.isEmpty() || cancelled, cancelled), Map.copyOf(facts), false);
        } catch (BusinessException | NumberFormatException exception) {
            log.warn("[답변 근거 조회] source={} result=unavailable", type);
            return failed(medication, type);
        }
    }

    static String clean(String text) {
        return HtmlUtils.htmlUnescape(text)
                .replaceAll("(?s)<!\\[CDATA\\[(.*?)]]>", "$1")
                .replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>", " ")
                .replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
    }

    static LocalDate parseDate(String value) {
        try {
            if (value.matches("\\d{8}")) return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
            if (value.length() >= 10) return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException ignored) {
            // API에 유효한 갱신 날짜가 없으면 명세대로 실제 조회한 날을 사용한다.
        }
        return null;
    }

    private static ExternalApiException invalid() {
        return ExternalApiException.failed("공공데이터포털", "공식 정보 응답을 확인할 수 없습니다.", null);
    }

    private static AnswerSource source(AnswerInput.Medication medication, AnswerSource.Type type,
                                       LocalDate date, boolean ok, boolean cancelled) {
        return new AnswerSource(medication.itemSeq(), medication.name(), type, date, ok, cancelled);
    }

    private static Lookup failed(AnswerInput.Medication medication, AnswerSource.Type type) {
        return new Lookup(source(medication, type, LocalDate.now(TimeZoneConfig.ZONE), false, false), Map.of(), true);
    }

    public record Lookup(AnswerSource source, Map<String, String> facts, boolean failed) {}
    public record Evidence(List<Lookup> lookups) {
        public boolean hasOfficialInformation() { return lookups.stream().anyMatch(lookup -> lookup.source().ok()); }
        public boolean hasFailures() { return lookups.stream().anyMatch(Lookup::failed); }
    }
}

package org.semiproject.pillbadeumserver.common.external;

import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/* 설명. 공공데이터포털(apis.data.go.kr) 의약품 API 를 부르는 공통 클라이언트
 *  ----------------------------------------------------------------------------------
 *  이 클라이언트를 쓰는 기능 (서비스 주소는 각 담당이 공공데이터포털 '활용신청' 화면에서 확인)
 *  - REG-008  약 이름 검색        : 의약품개요정보(e약은요)   예) /1471000/DrbEasyDrugInfoService/getDrbEasyDrugList
 *  - REG-004, REG-007 알약 식별   : 의약품 낱알식별 정보
 *  - DRUG-002, CHAT-007 근거 출처 : 의약품개요정보(e약은요), 허가정보
 *  - CHAT-008 DUR 금기 확인       : DUR 품목정보
 *  ----------------------------------------------------------------------------------
 *  사용 예)
 *      JsonNode body = publicDataClient.get("/1471000/DrbEasyDrugInfoService/getDrbEasyDrugList",
 *              Map.of("itemName", "타이레놀", "pageNo", 1, "numOfRows", 10));
 *      List<JsonNode> items = PublicDataClient.items(body);
 *      for (JsonNode item : items) { item.path("itemName").asString(); ... }
 *  ----------------------------------------------------------------------------------
 *  - serviceKey 와 type=json 은 자동으로 붙는다.
 *  - HTTP 일시 오류만 0.5초 뒤 1회 재시도한다. 인증/파라미터/응답 형식 오류는 재시도하지 않는다.
 *  - 결과 코드(resultCode)가 '00' 이 아니면 503, 시간 초과도 구분된 코드의 503으로 바뀐다.
 *  - DUR 저장/재사용 여부와 synced_at 갱신은 담당 서비스에서 처리한다.
 *  - 이 클라이언트의 호출 제한만으로 여러 작업을 합친 요청 전체 90초가 보장되지는 않는다.
 * */
@Component
public class PublicDataClient {

    private static final Logger log = LoggerFactory.getLogger(PublicDataClient.class);

    public static final String API_NAME = "공공데이터포털";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String serviceKey;
    private final Semaphore permits;
    private final long permitTimeoutMs;

    public PublicDataClient(ObjectMapper objectMapper,
                            @Value("${public-data.base-url}") String baseUrl,
                            // .env 의 PUBLIC_DATA_SERVICE_KEY. 공공데이터포털의 '일반 인증키(Decoding)' 를 넣는다.
                            @Value("${public-data.service-key}") String serviceKey,
                            @Value("${public-data.connect-timeout-ms}") long connectTimeoutMs,
                            @Value("${public-data.read-timeout-ms}") long readTimeoutMs,
                            @Value("${public-data.max-concurrent-calls:4}") int maxConcurrentCalls,
                            @Value("${public-data.permit-timeout-ms:10000}") long permitTimeoutMs) {

        this.objectMapper = objectMapper;
        this.serviceKey = serviceKey;
        if (maxConcurrentCalls < 1 || permitTimeoutMs < 1) {
            throw new IllegalArgumentException("공공데이터 동시 호출 수와 대기 시간은 양수여야 합니다.");
        }
        this.permits = new Semaphore(maxConcurrentCalls, true);
        this.permitTimeoutMs = permitTimeoutMs;
        this.restClient = RestClientFactory.builder(baseUrl,
                Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(readTimeoutMs)).build();
    }

    /* 목차. 1. GET 요청을 보내고 응답의 body 부분을 돌려준다.
     *  @param path   서비스 주소 (baseUrl 뒤 부분)
     *  @param params 요청 파라미터 (값이 null 이면 보내지 않는다)
     * */
    public JsonNode get(String path, Map<String, ?> params) {
        return execute(path, params, permitTimeoutMs, Long.MAX_VALUE);
    }

    /** COMMON-001/006: 병렬 수집기의 내부 대기를 요청의 남은 시간에 포함함. */
    public JsonNode get(String path, Map<String, ?> params, Duration remainingBudget) {
        if (remainingBudget == null || remainingBudget.isNegative() || remainingBudget.isZero())
            throw ExternalApiException.timeout(API_NAME, null);
        long waitMillis = Math.max(1, remainingBudget.toMillis());
        long deadline = System.nanoTime() + remainingBudget.toNanos();
        return execute(path, params, waitMillis, deadline);
    }

    private JsonNode execute(String path, Map<String, ?> params, long waitMillis, long deadline) {

        if (serviceKey == null || serviceKey.isBlank()) {
            throw ExternalApiException.failed(API_NAME, "서비스 키(PUBLIC_DATA_SERVICE_KEY)가 설정되지 않았습니다.", null);
        }

        // 값을 주소에 직접 이어 붙이지 않고 {변수} 로 넘긴다. 그래야 서비스 키의 +, /, = 가 올바르게 인코딩된다.
        StringBuilder uri = new StringBuilder(path).append("?serviceKey={serviceKey}&type={type}");
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("serviceKey", serviceKey);
        variables.put("type", "json");

        params.forEach((name, value) -> {
            if (value != null && !variables.containsKey(name)) {
                uri.append('&').append(name).append("={").append(name).append('}');
                variables.put(name, value);
            }
        });

        String responseBody = ExternalApiCaller.withPermit(API_NAME, permits, waitMillis,
                () -> ExternalApiCaller.callWithRetry(API_NAME, () -> {
                    if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
                        throw ExternalApiException.timeout(API_NAME, null);
                    return restClient.get()
                        .uri(uri.toString(), variables)
                        .retrieve()
                        .body(String.class);
                }));

        return extractBody(responseBody);
    }

    /* 목차. 2. body 의 items 를 목록으로 꺼낸다.
     *  API 마다 items 가 배열이기도 하고 { "item": [...] } 또는 { "item": {...} } 이기도 해서 한곳에서 맞춘다.
     * */
    public static List<JsonNode> items(JsonNode body) {

        JsonNode items = body.path("items");
        if (items.has("item")) {
            items = items.path("item");
        }

        List<JsonNode> result = new ArrayList<>();
        if (items.isArray()) {
            for (JsonNode item : items) {
                result.add(item);
            }
        } else if (items.isObject()) {
            result.add(items);
        }
        return result;
    }

    /* 목차. 3. 전체 결과 수 (페이지 계산용) */
    public static int totalCount(JsonNode body) {
        return body.path("totalCount").asInt(0);
    }

    /* 설명. 응답 모양이 두 가지라서 둘 다 받는다.
     *  1) { "header": {...}, "body": {...} }
     *  2) { "response": { "header": {...}, "body": {...} } }
     *  서비스 키가 틀리면 type=json 을 보내도 XML 로 오류를 돌려주므로, JSON 이 아니면 키 · 파라미터 문제로 본다.
     * */
    private JsonNode extractBody(String responseBody) {

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (RuntimeException e) {
            throw ExternalApiException.failed(API_NAME, "응답 형식이 올바르지 않습니다. (서비스 키 · 요청 파라미터를 확인하세요)", e);
        }

        if (root == null || !root.isObject()) {
            throw ExternalApiException.failed(API_NAME, "응답 형식이 올바르지 않습니다.", null);
        }

        if (root.has("response")) {
            root = root.path("response");
        }

        JsonNode header = root.path("header");
        String resultCode = header.path("resultCode").asString("");
        if (!"00".equals(resultCode)) {
            log.warn("[공공데이터 응답] resultCode={}", resultCode.matches("[0-9]{1,3}") ? resultCode : "unknown");
            throw ExternalApiException.failed(API_NAME, "공공데이터 응답이 성공이 아닙니다.", null);
        }

        JsonNode body = root.path("body");
        if (!body.isObject()) {
            throw ExternalApiException.failed(API_NAME, "공공데이터 본문이 없습니다.", null);
        }
        return body;
    }
}

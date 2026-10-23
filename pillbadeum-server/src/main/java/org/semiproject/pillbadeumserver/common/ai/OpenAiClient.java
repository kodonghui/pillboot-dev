package org.semiproject.pillbadeumserver.common.ai;

import org.semiproject.pillbadeumserver.common.exception.AiUnavailableException;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.ExternalApiCaller;
import org.semiproject.pillbadeumserver.common.external.RestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** OpenAI Decisions/Responses/Embedding 클라이언트. 분류/임베딩10초, 답변/요약60초(요약은 자체 마감30초), 처방전60초, 낱알85초임.
 * 이미지 판독은 연결 장애·429·일시5xx만0.5초 뒤1회 재시도하며 시간 초과는 재호출하지 않음.
 * 여러 외부 작업을 합친 전체 마감은 담당 서비스에서 적용함.
 */
@Component
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);
    private static final String API_NAME = "OpenAI";

    private final RestClient shortClient;
    private final RestClient longClient;
    private final RestClient prescriptionClient;
    private final RestClient pillClient;
    private final Semaphore permits;
    private final long permitTimeoutMs;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    private final String chatModel;
    private final String decisionModel;
    private final String visionModel;
    private final String embeddingModel;
    private final String reasoningEffort;
    // 대화 답변만 high를 기본으로 사용하며 요약·일정·낱알의 기존 공통 설정은 유지한다.
    @Value("${openai.chat-reasoning-effort:high}")
    private String chatReasoningEffort = "high";
    // 처방전 전용 모델·effort임. Responses 출력 토큰 수는 별도로 제한하지 않음.
    @Value("${openai.model.prescription:}")
    private String prescriptionModel;
    @Value("${openai.prescription-reasoning-effort:}")
    private String prescriptionReasoningEffort;
    private final BigDecimal decisionInputUsdPerMillion;

    public OpenAiClient(ObjectMapper objectMapper,
                        @Value("${openai.api-key}") String apiKey,
                        @Value("${openai.base-url}") String baseUrl,
                        @Value("${openai.model.chat}") String chatModel,
                        @Value("${openai.model.decision:}") String decisionModel,
                        @Value("${openai.model.vision}") String visionModel,
                        @Value("${openai.model.embedding}") String embeddingModel,
                        @Value("${openai.reasoning-effort}") String reasoningEffort,
                        @Value("${openai.connect-timeout-ms}") long connectTimeoutMs,
                        @Value("${openai.short-read-timeout-ms}") long shortReadTimeoutMs,
                        @Value("${openai.answer-read-timeout-ms}") long answerReadTimeoutMs,
                        @Value("${photo.prescription-ocr-timeout-ms:60000}") long prescriptionReadTimeoutMs,
                        @Value("${openai.max-concurrent-calls:4}") int maxConcurrentCalls,
                        @Value("${openai.permit-timeout-ms:10000}") long permitTimeoutMs,
                        @Value("${openai.decision-input-usd-per-million:0.10}") BigDecimal decisionInputUsdPerMillion,
                        @Value("${photo.pill-timeout-ms:85000}") long pillTimeoutMs) {

        this.objectMapper = objectMapper;
        this.enabled = apiKey != null && !apiKey.isBlank();
        this.chatModel = chatModel;
        this.decisionModel = decisionModel;
        this.visionModel = visionModel;
        this.embeddingModel = embeddingModel;
        this.reasoningEffort = reasoningEffort;
        if (decisionInputUsdPerMillion == null || decisionInputUsdPerMillion.signum() < 0) {
            throw new IllegalArgumentException("분류 입력 토큰 단가는 0 이상이어야 합니다.");
        }
        this.decisionInputUsdPerMillion = decisionInputUsdPerMillion;

        if (maxConcurrentCalls < 1 || permitTimeoutMs < 1 || prescriptionReadTimeoutMs < 1 || pillTimeoutMs < 1) {
            throw new IllegalArgumentException("OpenAI 동시 호출 수와 대기 시간은 양수여야 합니다.");
        }
        this.permits = new Semaphore(maxConcurrentCalls);
        this.permitTimeoutMs = permitTimeoutMs;
        this.shortClient = client(baseUrl, apiKey, connectTimeoutMs, shortReadTimeoutMs);
        this.longClient = client(baseUrl, apiKey, connectTimeoutMs, answerReadTimeoutMs);
        this.prescriptionClient = client(baseUrl, apiKey, connectTimeoutMs, prescriptionReadTimeoutMs);
        this.pillClient = client(baseUrl, apiKey, connectTimeoutMs, pillTimeoutMs);

        log.info("[OpenAiClient] API 키 설정 여부={} (모델/권한/연결 성공은 실제 호출 때 확인)", enabled);
    }

    /** API 키 설정 여부일 뿐 모델의 존재/계정 권한이나 실제 연결 성공을 뜻하지 않는다. */
    public boolean isEnabled() {
        return enabled;
    }

    /* 목차. 1. 글로 된 답을 한 번에 받기 */
    public String createText(String instructions, List<AiMessage> messages, int maxOutputTokens) {
        return createTextResult(instructions, messages, maxOutputTokens, reasoningEffort).text();
    }

    /** CHAT-007: 본문과 보조 ID를 받는다. 이전 응답 연결 없이 매번 DB에서 만든 input을 보낸다. */
    public AiTextResult createTextResult(String instructions, List<AiMessage> messages, int maxOutputTokens) {
        return createTextResult(instructions, messages, maxOutputTokens, chatReasoningEffort);
    }

    private AiTextResult createTextResult(String instructions, List<AiMessage> messages, int maxOutputTokens, String effort) {
        Map<String, Object> body = requestBody(chatModel, instructions, toInput(messages), maxOutputTokens);
        if (effort == null || effort.isBlank()) body.remove("reasoning");
        else body.put("reasoning", Map.of("effort", effort));
        JsonNode response = post(longClient, "/responses", body, chatModel, false);
        String id = response.path("id").isString() ? response.path("id").asString("") : "";
        return new AiTextResult(outputText(response), id.isBlank() || id.length() > 255 ? null : id);
    }

    /* 목차. 2. 분류를 JSON Schema 모양으로 받기 (구조화 출력, 10초)
     *  schema 예) Map.of("type", "object",
     *                   "properties", Map.of("category", Map.of("type", "string", "enum", List.of("MEDICATION", "BLOCKED"))),
     *                   "required", List.of("category"),
     *                   "additionalProperties", false)
     * */
    public JsonNode createJson(String instructions, List<AiMessage> messages, int maxOutputTokens,
                               String schemaName, Map<String, Object> schema) {

        Map<String, Object> body = requestBody(chatModel, instructions, toInput(messages), maxOutputTokens);
        body.put("text", jsonSchemaFormat(schemaName, schema));

        return parseJson(outputText(post(shortClient, "/responses", body, chatModel, false)));
    }

    /**
     * CHAT-005 분류용 Decisions choice 호출. 질문 원문과 고정 선택지를 받아 선택된 값만 반환한다.
     * Responses 전용 생성 옵션을 섞지 않고 짧은 클라이언트·공통 호출 제한을 사용하며 재시도하지 않는다.
     * refusal·누락·다른 질문의 답·선택지 밖 값은 통과 결과로 사용하지 않고 공통 503 예외로 처리한다.
     */
    public String createChoice(String input, String name, String instructions, List<DecisionChoice> choices) {
        Map<String, Object> question = Map.of("type", "choice", "name", name,
                "instructions", instructions, "choices", choices);
        Map<String, Object> body = Map.of("model", decisionModel, "input", input,
                "questions", List.of(question));
        JsonNode response = post(shortClient, "/decisions", body, decisionModel, false);
        JsonNode answers = response.path("answers");
        if (!answers.isArray() || answers.size() != 1) {
            throw ExternalApiException.failed(API_NAME, "분류 응답이 올바르지 않습니다.", null);
        }
        JsonNode answer = answers.path(0);
        JsonNode value = answer.path("choice");
        if (!"choice".equals(answer.path("type").asString())
                || !name.equals(answer.path("name").asString())
                || !value.isString()
                || choices.stream().noneMatch(choice -> choice.value().equals(value.asString()))) {
            throw ExternalApiException.failed(API_NAME, "분류 응답이 올바르지 않습니다.", null);
        }
        return value.asString();
    }

    /* 목차. 3. 사진을 보고 JSON 으로 받기 (처방전 · 약봉투 · 알약 사진 식별)
     *  @param prompt        사진과 함께 보낼 질문 (예: "앞면과 뒷면 사진이다. 각인 · 색 · 모양을 읽어라")
     *  @param imageDataUrls OpenAiClient.toDataUrl(bytes, mimeType) 로 만든 이미지 목록 (앞 · 뒤 등 여러 장 가능)
     * */
    public JsonNode createJsonFromImages(String instructions, String prompt, List<String> imageDataUrls,
                                         int maxOutputTokens, String schemaName, Map<String, Object> schema) {

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.<String, Object>of("type", "input_text", "text", prompt));
        for (String imageDataUrl : imageDataUrls) {
            content.add(Map.<String, Object>of("type", "input_image", "image_url", imageDataUrl));
        }
        List<Map<String, Object>> input = List.of(Map.<String, Object>of("role", "user", "content", content));

        boolean prescription = "photo_prescription_names".equals(schemaName)
                || "prescription_medicine_names".equals(schemaName);
        String model = prescription && prescriptionModel != null && !prescriptionModel.isBlank()
                ? prescriptionModel : visionModel;
        String effort = prescription && prescriptionReasoningEffort != null && !prescriptionReasoningEffort.isBlank()
                ? prescriptionReasoningEffort : reasoningEffort;
        Map<String, Object> body = requestBody(model, instructions, input, maxOutputTokens);
        if (effort != null && !effort.isBlank())
            body.put("reasoning", Map.of("effort", effort));
        body.put("text", jsonSchemaFormat(schemaName, schema));

        return parseJson(outputText(post(prescription ? prescriptionClient : pillClient, "/responses", body, model, true, false)));
    }

    /** 벡터와 같은 모델명을 Qdrant payload·모델별 컬렉션에 기록할 때 사용한다. */
    public String embeddingModel() { return embeddingModel; }

    /* 목차. 4. 문장을 벡터로 바꾸기 (임베딩) */
    public float[] embed(String text) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", embeddingModel);
        body.put("input", text);
        body.put("encoding_format", "float");

        JsonNode vector = post(shortClient, "/embeddings", body, embeddingModel, true).path("data").path(0).path("embedding");

        if (!vector.isArray() || vector.isEmpty()) {
            throw ExternalApiException.failed(API_NAME, "임베딩 응답이 올바르지 않습니다.", null);
        }
        float[] result = new float[vector.size()];
        for (int i = 0; i < result.length; i++) {
            if (!vector.get(i).isNumber()) {
                throw ExternalApiException.failed(API_NAME, "임베딩 응답이 올바르지 않습니다.", null);
            }
            result[i] = (float) vector.get(i).asDouble();
            if (!Float.isFinite(result[i])) {
                throw ExternalApiException.failed(API_NAME, "임베딩 응답이 올바르지 않습니다.", null);
            }
        }
        return result;
    }

    /* 목차. 5. 이미지 바이트를 OpenAI 가 받는 data URL 로 바꾼다.
     *  사용 예) OpenAiClient.toDataUrl(fileStorage.readBytes(name, FileStorage.DIR_PILL), fileStorage.getMimeType(name))
     * */
    public static String toDataUrl(byte[] bytes, String mimeType) {
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /* 설명. ===== 아래는 이 클래스 안에서만 쓰는 보조 메서드 ===== */

    private void checkEnabled() {
        if (!enabled) {
            throw new AiUnavailableException("AI 기능이 설정되지 않았습니다. (OPENAI_API_KEY)");
        }
    }

    private static RestClient client(String baseUrl, String apiKey, long connectTimeoutMs, long readTimeoutMs) {
        return RestClientFactory.builder(baseUrl, Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(readTimeoutMs))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    /* 키 확인 -> 호출 제한 -> 요청/예외 변환 -> 사용량 기록. 원문/프롬프트/키는 기록하지 않는다. */
    private JsonNode post(RestClient client, String uri, Map<String, Object> body, String model, boolean retry) {
        return post(client, uri, body, model, retry, true);
    }

    private JsonNode post(RestClient client, String uri, Map<String, Object> body, String model, boolean retry, boolean retryTimeouts) {
        checkEnabled();
        if (model == null || model.isBlank()) {
            throw new AiUnavailableException("해당 AI 작업의 모델이 설정되지 않았습니다.");
        }
        long start = System.nanoTime();
        Supplier<String> request = () -> client.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> status.value() == 401, (outgoing, response) -> {
                    throw new AiUnavailableException("AI 기능 설정을 확인해주세요.");
                })
                .body(String.class);
        String responseBody = ExternalApiCaller.withPermit(API_NAME, permits, permitTimeoutMs,
                () -> !retry ? ExternalApiCaller.call(API_NAME, request) : retryTimeouts
                        ? ExternalApiCaller.callWithRetry(API_NAME, request) : ExternalApiCaller.callWithQuickRetry(API_NAME, request));
        JsonNode response = parseJson(responseBody);
        if (!response.isObject()) {
            throw ExternalApiException.failed(API_NAME, "AI 응답이 올바르지 않습니다.", null);
        }
        logUsage(uri, model, response, start);
        return response;
    }

    private JsonNode parseJson(String text) {
        try {
            JsonNode result = objectMapper.readTree(text);
            if (result == null || result.isNull()) {
                throw ExternalApiException.failed(API_NAME, "AI 응답이 비어 있습니다.", null);
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ExternalApiException.failed(API_NAME, "AI 응답 형식이 올바르지 않습니다.", e);
        }
    }

    /* 설명. Responses API 요청 본문
     *  - store : false 로 Responses 응답 객체 저장을 비활성화한다. 제공자의 모든 보관 정책을 제어하는 값은 아니다.
     *  - reasoning.effort : 값이 있을 때만 보낸다. 추론 기능이 없는 모델에 보내면 오류가 난다.
     *  - maxOutputTokens 인자는 기존 호출부 호환용이며 max_output_tokens는 전송하지 않음.
     * */
    private Map<String, Object> requestBody(String model, String instructions, Object input, int maxOutputTokens) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (instructions != null && !instructions.isBlank()) {
            body.put("instructions", instructions);
        }
        body.put("input", input);
        // 사용자가 임의 생성 상한 제거를 선택함. 제공자의 모델·컨텍스트 한도는 별도임.
        body.put("store", false);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            body.put("reasoning", Map.of("effort", reasoningEffort));
        }
        return body;
    }

    private List<Map<String, String>> toInput(List<AiMessage> messages) {
        return messages.stream()
                .map(message -> Map.of("role", message.role(), "content", message.content()))
                .toList();
    }

    private Map<String, Object> jsonSchemaFormat(String schemaName, Map<String, Object> schema) {
        return Map.<String, Object>of("format", Map.of(
                "type", "json_schema",
                "name", schemaName,
                "strict", true,
                "schema", schema));
    }

    /* 설명. 응답의 output 중 type=message 항목에서 output_text 글만 이어 붙인다. */
    private String outputText(JsonNode response) {

        if (!"completed".equals(response.path("status").asString())) {
            throw ExternalApiException.failed(API_NAME, "AI 응답 생성이 완료되지 않았습니다.", null);
        }

        StringBuilder text = new StringBuilder();
        for (JsonNode item : response.path("output")) {
            if ("message".equals(item.path("type").asString())) {
                for (JsonNode content : item.path("content")) {
                    if ("refusal".equals(content.path("type").asString())) {
                        throw ExternalApiException.failed(API_NAME, "AI가 응답을 생성하지 못했습니다.", null);
                    }
                    if ("output_text".equals(content.path("type").asString())) {
                        if (!content.path("text").isString()) {
                            throw ExternalApiException.failed(API_NAME, "AI 응답 본문 형식이 올바르지 않습니다.", null);
                        }
                        text.append(content.path("text").asString());
                    }
                }
            }
        }
        if (text.toString().isBlank()) {
            throw ExternalApiException.failed(API_NAME, "AI 응답 본문이 없습니다.", null);
        }
        return text.toString();
    }

    private void logUsage(String uri, String model, JsonNode response, long start) {

        JsonNode usage = response.path("usage");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // Decisions는 입력 토큰만 과금한다. 사용량 누락을 0원으로 기록하지 않고, 단가 기반 추정과 실제 청구를 구분한다.
        if ("/decisions".equals(uri)) {
            JsonNode input = usage.path("input_tokens");
            boolean known = input.isIntegralNumber() && input.asLong() >= 0;
            String cost = known ? BigDecimal.valueOf(input.asLong()).multiply(decisionInputUsdPerMillion)
                    .movePointLeft(6).toPlainString() : "unknown";
            log.info("[OpenAiClient] api=decisions model={} inputTokens={} elapsedMs={} estimatedCostUsd={} inputUsdPerMillion={}",
                    model, known ? input.asLong() : "unknown", elapsedMs, cost, decisionInputUsdPerMillion);
            return;
        }
        if ("/embeddings".equals(uri)) {
            JsonNode input = usage.path("prompt_tokens");
            log.info("[OpenAiClient] api=embeddings model={} inputTokens={} elapsedMs={}", model,
                    input.isIntegralNumber() && input.asLong() >= 0 ? input.asLong() : "unknown", elapsedMs);
            return;
        }
        log.info("[OpenAiClient] 완료 : model={}, 입력 토큰={}, 출력 토큰={}, {}ms",
                model,
                usage.path("input_tokens").asInt(),
                usage.path("output_tokens").asInt(),
                elapsedMs);
    }

}

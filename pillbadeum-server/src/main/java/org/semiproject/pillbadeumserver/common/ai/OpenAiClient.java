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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** OpenAI Responses/Embedding 클라이언트. 분류/임베딩 10초, 비전/답변/요약 30초.
 * 임베딩만 일시 오류를 0.5초 뒤 1회 재시도하며, 생성 요청은 자동 재시도하지 않는다.
 * 여러 호출을 합친 요청 전체 90초 제한은 담당 서비스에서 별도로 적용해야 한다.
 */
@Component
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);
    private static final String API_NAME = "OpenAI";

    private final RestClient shortClient;
    private final RestClient longClient;
    private final Semaphore permits;
    private final long permitTimeoutMs;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    private final String chatModel;
    private final String visionModel;
    private final String embeddingModel;
    private final String reasoningEffort;

    public OpenAiClient(ObjectMapper objectMapper,
                        @Value("${openai.api-key}") String apiKey,
                        @Value("${openai.base-url}") String baseUrl,
                        @Value("${openai.model.chat}") String chatModel,
                        @Value("${openai.model.vision}") String visionModel,
                        @Value("${openai.model.embedding}") String embeddingModel,
                        @Value("${openai.reasoning-effort}") String reasoningEffort,
                        @Value("${openai.connect-timeout-ms}") long connectTimeoutMs,
                        @Value("${openai.short-read-timeout-ms}") long shortReadTimeoutMs,
                        @Value("${openai.long-read-timeout-ms}") long longReadTimeoutMs,
                        @Value("${openai.max-concurrent-calls:4}") int maxConcurrentCalls,
                        @Value("${openai.permit-timeout-ms:1000}") long permitTimeoutMs) {

        this.objectMapper = objectMapper;
        this.enabled = apiKey != null && !apiKey.isBlank();
        this.chatModel = chatModel;
        this.visionModel = visionModel;
        this.embeddingModel = embeddingModel;
        this.reasoningEffort = reasoningEffort;

        if (maxConcurrentCalls < 1 || permitTimeoutMs < 1) {
            throw new IllegalArgumentException("OpenAI 동시 호출 수와 대기 시간은 양수여야 합니다.");
        }
        this.permits = new Semaphore(maxConcurrentCalls);
        this.permitTimeoutMs = permitTimeoutMs;
        this.shortClient = client(baseUrl, apiKey, connectTimeoutMs, shortReadTimeoutMs);
        this.longClient = client(baseUrl, apiKey, connectTimeoutMs, longReadTimeoutMs);

        log.info("[OpenAiClient] API 키 설정 여부={} (모델/권한/연결 성공은 실제 호출 때 확인)", enabled);
    }

    /** API 키 설정 여부일 뿐 모델의 존재/계정 권한이나 실제 연결 성공을 뜻하지 않는다. */
    public boolean isEnabled() {
        return enabled;
    }

    /* 목차. 1. 글로 된 답을 한 번에 받기 */
    public String createText(String instructions, List<AiMessage> messages, int maxOutputTokens) {

        Map<String, Object> body = requestBody(chatModel, instructions, toInput(messages), maxOutputTokens);
        return outputText(post(longClient, "/responses", body, chatModel, false));
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

        Map<String, Object> body = requestBody(visionModel, instructions, input, maxOutputTokens);
        body.put("text", jsonSchemaFormat(schemaName, schema));

        return parseJson(outputText(post(longClient, "/responses", body, visionModel, false)));
    }

    /* 목차. 4. 문장을 벡터로 바꾸기 (임베딩) */
    public float[] embed(String text) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", embeddingModel);
        body.put("input", text);

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
        checkEnabled();
        if (model == null || model.isBlank()) {
            throw new AiUnavailableException("해당 AI 작업의 모델이 설정되지 않았습니다.");
        }
        long start = System.currentTimeMillis();
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
                () -> retry ? ExternalApiCaller.callWithRetry(API_NAME, request) : ExternalApiCaller.call(API_NAME, request));
        JsonNode response = parseJson(responseBody);
        if (!response.isObject()) {
            throw ExternalApiException.failed(API_NAME, "AI 응답이 올바르지 않습니다.", null);
        }
        logUsage(model, response, start);
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
     *  - store : false 면 OpenAI 가 대화를 보관하지 않는다. (복용 약 · 건강 정보가 담기므로 끈다)
     *  - reasoning.effort : 값이 있을 때만 보낸다. 추론 기능이 없는 모델에 보내면 오류가 난다.
     * */
    private Map<String, Object> requestBody(String model, String instructions, Object input, int maxOutputTokens) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (instructions != null && !instructions.isBlank()) {
            body.put("instructions", instructions);
        }
        body.put("input", input);
        body.put("max_output_tokens", maxOutputTokens);
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

        if ("incomplete".equals(response.path("status").asString())
                || "failed".equals(response.path("status").asString())) {
            throw ExternalApiException.failed(API_NAME, "응답이 출력 토큰 상한에서 끊겼습니다.", null);
        }

        StringBuilder text = new StringBuilder();
        for (JsonNode item : response.path("output")) {
            if ("message".equals(item.path("type").asString())) {
                for (JsonNode content : item.path("content")) {
                    if ("output_text".equals(content.path("type").asString())) {
                        text.append(content.path("text").asString());
                    }
                }
            }
        }
        if (text.isEmpty()) {
            throw ExternalApiException.failed(API_NAME, "AI 응답 본문이 없습니다.", null);
        }
        return text.toString();
    }

    private void logUsage(String model, JsonNode response, long start) {

        JsonNode usage = response.path("usage");
        log.info("[OpenAiClient] 완료 : model={}, 입력 토큰={}, 출력 토큰={}, {}ms",
                model,
                usage.path("input_tokens").asInt(),
                usage.path("output_tokens").asInt(),
                System.currentTimeMillis() - start);
    }

}

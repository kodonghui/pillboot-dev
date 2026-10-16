package org.semiproject.pillbadeumserver.common.ai;

import org.semiproject.pillbadeumserver.common.exception.AiUnavailableException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.ExternalApiCaller;
import org.semiproject.pillbadeumserver.common.external.RestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/* 설명. OpenAI API 를 부르는 공통 클라이언트 (이미지 식별 담당과 챗봇 담당이 함께 쓴다)
 *  ----------------------------------------------------------------------------------
 *  제공하는 호출
 *  1) createText()           : 글로 된 답을 한 번에 받기                     (CHAT-009 10턴 요약)
 *  2) createJson()           : 정해 준 JSON Schema 모양으로 받기             (CHAT-005 질문 분류)
 *  3) createJsonFromImages() : 사진을 보고 JSON 으로 받기                    (REG-003 처방전 식별, REG-007 알약 식별)
 *  4) streamText()           : 답변을 글자 조각으로 조금씩 받기 (SSE 용)     (CHAT-007 답변 만들기)
 *  5) embed()                : 문장을 벡터(float 배열)로 바꾸기              (CHAT-004 임베딩)
 *  ----------------------------------------------------------------------------------
 *  - 모델 이름 · 시간 제한은 application.yaml 의 openai.* 에서 바꾼다.
 *  - OPENAI_API_KEY 가 없으면 서버는 정상으로 뜨고, AI 호출만 503(AiUnavailableException) 으로 막는다.
 *  - 호출 실패는 502, 시간 초과는 504 로 바뀐다. (ExternalApiCaller, COMMON-001)
 *  - 매 호출의 토큰 수(= 비용)와 걸린 시간을 로그로 남긴다. 키 값은 절대 로그에 찍지 않는다.
 * */
@Component
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);
    private static final String API_NAME = "OpenAI";

    private final RestClient restClient;
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
                        @Value("${openai.read-timeout-ms}") long readTimeoutMs) {

        this.objectMapper = objectMapper;
        this.enabled = apiKey != null && !apiKey.isBlank();
        this.chatModel = chatModel;
        this.visionModel = visionModel;
        this.embeddingModel = embeddingModel;
        this.reasoningEffort = reasoningEffort;

        this.restClient = RestClientFactory.builder(baseUrl,
                        Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(readTimeoutMs))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();

        log.info(enabled
                ? "[OpenAiClient] OPENAI_API_KEY 확인됨 -> AI 기능을 쓸 수 있다"
                : "[OpenAiClient] OPENAI_API_KEY 가 없다 -> AI 기능은 503 으로 응답한다 (나머지 기능은 정상)");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /* 목차. 1. 글로 된 답을 한 번에 받기 */
    public String createText(String instructions, List<AiMessage> messages, int maxOutputTokens) {

        Map<String, Object> body = requestBody(chatModel, instructions, toInput(messages), maxOutputTokens);
        return outputText(post("/responses", body, chatModel));
    }

    /* 목차. 2. JSON Schema 모양으로 받기 (구조화 출력)
     *  schema 예) Map.of("type", "object",
     *                   "properties", Map.of("category", Map.of("type", "string", "enum", List.of("MEDICATION", "BLOCKED"))),
     *                   "required", List.of("category"),
     *                   "additionalProperties", false)
     * */
    public JsonNode createJson(String instructions, List<AiMessage> messages, int maxOutputTokens,
                               String schemaName, Map<String, Object> schema) {

        Map<String, Object> body = requestBody(chatModel, instructions, toInput(messages), maxOutputTokens);
        body.put("text", jsonSchemaFormat(schemaName, schema));

        return objectMapper.readTree(outputText(post("/responses", body, chatModel)));
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

        return objectMapper.readTree(outputText(post("/responses", body, visionModel)));
    }

    /* 목차. 4. 답변을 글자 조각으로 조금씩 받기 (스트리밍)
     *  글자 조각이 올 때마다 onDelta 를 부르고, 다 받으면 전체 답변을 돌려준다. (대화 이력 저장용)
     *  onDelta 예) delta -> emitter.send(SseEmitter.event().name("delta").data(delta))
     * */
    public String streamText(String instructions, List<AiMessage> messages, int maxOutputTokens,
                             Consumer<String> onDelta) {

        checkEnabled();

        Map<String, Object> body = requestBody(chatModel, instructions, toInput(messages), maxOutputTokens);
        body.put("stream", true);
        long start = System.currentTimeMillis();

        return ExternalApiCaller.call(API_NAME, () -> restClient.post()
                .uri("/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(body)
                .exchange((request, response) -> {

                    if (response.getStatusCode().isError()) {
                        throw toException(response.getStatusCode(), response.getBody());
                    }

                    StringBuilder fullText = new StringBuilder();

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {

                        String line;
                        while ((line = reader.readLine()) != null) {

                            if (!line.startsWith("data:")) {
                                continue;
                            }

                            JsonNode event = objectMapper.readTree(line.substring("data:".length()).trim());

                            switch (event.path("type").asString()) {
                                case "response.output_text.delta" -> {
                                    String delta = event.path("delta").asString();
                                    fullText.append(delta);
                                    onDelta.accept(delta);
                                }
                                case "response.completed", "response.incomplete" ->
                                        logUsage(chatModel, event.path("response"), start);
                                case "response.failed", "error" -> throw ExternalApiException.failed(API_NAME,
                                        "답변 생성 중 오류가 발생했습니다.", null);
                                default -> {
                                    // 그 밖의 이벤트(response.created 등)는 쓰지 않는다.
                                }
                            }
                        }
                    }
                    return fullText.toString();
                }));
    }

    /* 목차. 5. 문장을 벡터로 바꾸기 (임베딩) */
    public float[] embed(String text) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", embeddingModel);
        body.put("input", text);

        JsonNode vector = post("/embeddings", body, embeddingModel).path("data").path(0).path("embedding");

        float[] result = new float[vector.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = (float) vector.get(i).asDouble();
        }
        return result;
    }

    /* 목차. 6. 이미지 바이트를 OpenAI 가 받는 data URL 로 바꾼다.
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

    /* 설명. 한 번에 받는 호출의 공통 처리 : 키 확인 -> 요청 -> 오류 변환 -> 토큰 사용량 로그 */
    private JsonNode post(String uri, Map<String, Object> body, String model) {

        checkEnabled();
        long start = System.currentTimeMillis();

        String responseBody = ExternalApiCaller.call(API_NAME, () -> restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw toException(response.getStatusCode(), response.getBody());
                })
                .body(String.class));

        JsonNode response = objectMapper.readTree(responseBody);
        logUsage(model, response, start);
        return response;
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

        if ("incomplete".equals(response.path("status").asString())) {
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

    /* 설명. OpenAI 오류 응답을 우리 예외로 바꾼다.
     *  OpenAI 의 401 은 '우리 서비스 로그인'과 관계없는 설정 문제이므로 401 로 넘기지 않고 503 으로 바꾼다.
     * */
    private RuntimeException toException(HttpStatusCode status, InputStream errorBody) throws IOException {

        String body = new String(errorBody.readAllBytes(), StandardCharsets.UTF_8);
        String message;
        try {
            message = objectMapper.readTree(body).path("error").path("message").asString(body);
        } catch (RuntimeException e) {
            message = body;
        }

        log.warn("[OpenAiClient] 실패 : HTTP {} - {}", status.value(), message);

        return switch (status.value()) {
            case 401 -> new AiUnavailableException("AI 기능 설정에 문제가 있습니다. (OPENAI_API_KEY 확인)");
            case 429 -> ExternalApiException.failed(API_NAME, "요청이 많아 잠시 후 다시 시도해주세요.", null);
            default -> ExternalApiException.failed(API_NAME, "(HTTP " + status.value() + ")", null);
        };
    }
}

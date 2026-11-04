package org.semiproject.pillbadeumserver.common.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.ExternalApiCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * V3-COMMON-005·DEC-028: OpenAI 호출이 실패했을 때 같은 요청을 Claude(기본 claude-sonnet-5-5, effort medium)로 한 번 다시 보낸다.
 * 키가 비어 있으면 아무것도 하지 않는다. 읽기 한도는 같은 호출의 OpenAI 한도를 그대로 쓴다(짧은10초·답변60초·처방전60초·낱알85초).
 * 질문·사진·키·응답 원문은 로그에 남기지 않고 모델명·토큰 수·걸린 시간만 남긴다.
 */
@Component
public class ClaudeBackupClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeBackupClient.class);
    static final String API_NAME = "Claude";
    // 서버 측 거절 대체(fallbacks "default")를 켜는 베타 헤더. 배열 형식(-2026-06-01)과 섞으면 400임.
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    // Claude 구조화 출력이 받지 않는 숫자·길이 제약. 지우고 설명(description)으로 옮긴다.
    private static final Set<String> MOVED_TO_DESCRIPTION = Set.of("minimum", "maximum", "exclusiveMinimum",
            "exclusiveMaximum", "multipleOf", "minLength", "maxLength", "pattern", "maxItems", "uniqueItems");

    /** OpenAI 쪽 같은 호출과 같은 읽기 한도를 쓰기 위한 호출 종류. */
    public enum Kind { SHORT, ANSWER, PRESCRIPTION, PILL }

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String model;
    private final String effort;
    private final long maxTokens;
    private final boolean refusalFallback;
    private final Semaphore permits;
    private final long permitTimeoutMs;
    private final Map<Kind, AnthropicClient> clients = new LinkedHashMap<>();

    public ClaudeBackupClient(ObjectMapper objectMapper,
                              @Value("${anthropic.api-key:}") String apiKey,
                              @Value("${anthropic.base-url:https://api.anthropic.com}") String baseUrl,
                              @Value("${anthropic.model:claude-sonnet-5-5}") String model,
                              @Value("${anthropic.effort:medium}") String effort,
                              @Value("${anthropic.max-tokens:16000}") long maxTokens,
                              @Value("${anthropic.refusal-fallback:true}") boolean refusalFallback,
                              @Value("${anthropic.max-concurrent-calls:4}") int maxConcurrentCalls,
                              @Value("${openai.permit-timeout-ms:10000}") long permitTimeoutMs,
                              @Value("${openai.short-read-timeout-ms:10000}") long shortReadTimeoutMs,
                              @Value("${openai.answer-read-timeout-ms:60000}") long answerReadTimeoutMs,
                              @Value("${photo.prescription-ocr-timeout-ms:60000}") long prescriptionTimeoutMs,
                              @Value("${photo.pill-timeout-ms:85000}") long pillTimeoutMs) {
        if (maxConcurrentCalls < 1 || permitTimeoutMs < 1 || maxTokens < 1) {
            throw new IllegalArgumentException("Claude 백업 동시 호출 수·대기 시간·출력 한도는 양수여야 합니다.");
        }
        this.objectMapper = objectMapper;
        this.model = model == null ? "" : model.trim();
        this.effort = effort == null ? "" : effort.trim();
        this.maxTokens = maxTokens;
        this.refusalFallback = refusalFallback;
        this.permits = new Semaphore(maxConcurrentCalls);
        this.permitTimeoutMs = permitTimeoutMs;
        this.enabled = apiKey != null && !apiKey.isBlank() && !this.model.isBlank();
        if (enabled) {
            // 짧은 호출은 재시도하지 않고, 긴 호출만 일시 오류(429·529·5xx)에 한 번 다시 보낸다.
            clients.put(Kind.SHORT, client(apiKey, baseUrl, shortReadTimeoutMs, 0));
            clients.put(Kind.ANSWER, client(apiKey, baseUrl, answerReadTimeoutMs, 1));
            clients.put(Kind.PRESCRIPTION, client(apiKey, baseUrl, prescriptionTimeoutMs, 1));
            clients.put(Kind.PILL, client(apiKey, baseUrl, pillTimeoutMs, 1));
        }
        log.info("[ClaudeBackupClient] 백업 키 설정 여부={} model={} effort={}", enabled, this.model, this.effort);
    }

    /** 키·모델 설정 여부일 뿐 실제 연결 성공을 뜻하지 않는다. */
    public boolean isEnabled() {
        return enabled;
    }

    /** 글로 된 답. */
    public String text(Kind kind, String instructions, List<AiMessage> messages) {
        MessageCreateParams.Builder params = base(instructions, null);
        for (MessageParam message : toMessages(messages)) params.addMessage(message);
        return send(kind, params.build());
    }

    /** JSON Schema 모양의 답. Claude가 받지 않는 숫자·길이 제약은 설명으로 옮긴다. */
    public JsonNode json(Kind kind, String instructions, List<AiMessage> messages, Map<String, Object> schema) {
        MessageCreateParams.Builder params = base(instructions, schema);
        for (MessageParam message : toMessages(messages)) params.addMessage(message);
        return parseJson(send(kind, params.build()));
    }

    /** 사진을 보고 JSON으로 답함. 사진은 OpenAiClient.toDataUrl 형식(data:image/...;base64,...)으로 받는다. */
    public JsonNode jsonFromImages(Kind kind, String instructions, String prompt, List<String> imageDataUrls,
                                   Map<String, Object> schema) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (String dataUrl : imageDataUrls) {
            DataUrl image = parseDataUrl(dataUrl);
            blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.of(image.mediaType()))
                            .data(image.base64())
                            .build())
                    .build()));
        }
        if (prompt != null && !prompt.isBlank()) {
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(prompt).build()));
        }
        MessageCreateParams params = base(instructions, schema).addUserMessageOfContentBlockParams(blocks).build();
        return parseJson(send(kind, params));
    }

    /** Decisions choice 대신 하나의 enum 값을 JSON으로 고르게 하고, 선택지 밖 값은 실패로 본다. */
    public String choice(String input, String name, String instructions, List<DecisionChoice> choices) {
        List<String> values = choices.stream().map(DecisionChoice::value).toList();
        StringBuilder rules = new StringBuilder(instructions == null ? "" : instructions.trim());
        rules.append("\n\n아래 선택지 중 하나만 골라 JSON의 \"").append(name).append("\" 값으로 답한다.");
        for (DecisionChoice choice : choices) {
            rules.append("\n- ").append(choice.value()).append(": ").append(choice.description());
        }
        Map<String, Object> schema = choiceSchema(name, values);
        JsonNode answer = json(Kind.SHORT, rules.toString(), List.of(AiMessage.user(input)), schema).path(name);
        if (!answer.isString() || !values.contains(answer.asString())) {
            throw ExternalApiException.failed(API_NAME, "분류 응답이 올바르지 않습니다.", null);
        }
        return answer.asString();
    }

    /* 설명. ===== 아래는 이 클래스 안에서만 쓰는 보조 메서드 ===== */

    private static AnthropicClient client(String apiKey, String baseUrl, long timeoutMs, int maxRetries) {
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .timeout(Duration.ofMillis(timeoutMs))
                .maxRetries(maxRetries)
                .build();
    }

    private MessageCreateParams.Builder base(String instructions, Map<String, Object> schema) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens);
        if (instructions != null && !instructions.isBlank()) params.system(instructions);
        OutputConfig.Builder output = OutputConfig.builder();
        boolean hasOutput = false;
        if (!effort.isBlank()) {
            output.effort(OutputConfig.Effort.of(effort));
            hasOutput = true;
        }
        if (schema != null) {
            JsonOutputFormat.Schema.Builder jsonSchema = JsonOutputFormat.Schema.builder();
            sanitizeSchema(schema).forEach((key, value) -> jsonSchema.putAdditionalProperty(key, JsonValue.from(value)));
            output.format(JsonOutputFormat.builder().schema(jsonSchema.build()).build());
            hasOutput = true;
        }
        if (hasOutput) params.outputConfig(output.build());
        if (refusalFallback) {
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA);
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return params;
    }

    private String send(Kind kind, MessageCreateParams params) {
        if (!enabled) {
            throw ExternalApiException.failed(API_NAME, "백업 키가 설정되지 않았습니다.", null);
        }
        AnthropicClient client = clients.get(kind);
        long start = System.nanoTime();
        Message message = ExternalApiCaller.withPermit(API_NAME, permits, permitTimeoutMs, () -> call(client, params));
        StopReason stop = message.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            throw ExternalApiException.failed(API_NAME, "AI가 응답을 거절했습니다.", null);
        }
        if (StopReason.MAX_TOKENS.equals(stop)) {
            throw ExternalApiException.failed(API_NAME, "AI 응답이 출력 한도에서 끊겼습니다.", null);
        }
        StringBuilder text = new StringBuilder();
        message.content().forEach(block -> block.text().ifPresent(part -> text.append(part.text())));
        if (text.toString().isBlank()) {
            throw ExternalApiException.failed(API_NAME, "AI 응답 본문이 없습니다.", null);
        }
        log.info("[ClaudeBackupClient] 완료 : model={}, 입력 토큰={}, 출력 토큰={}, {}ms",
                message.model(), message.usage().inputTokens(), message.usage().outputTokens(),
                (System.nanoTime() - start) / 1_000_000);
        return text.toString();
    }

    /* 키·응답 원문·예외 메시지는 남기지 않고 상태 코드와 종류만 고정 사유로 바꾼다. */
    private static Message call(AnthropicClient client, MessageCreateParams params) {
        try {
            return client.messages().create(params);
        } catch (AnthropicServiceException e) {
            log.warn("[외부 API] api={} status={}", API_NAME, e.statusCode());
            throw ExternalApiException.failed(API_NAME, "(HTTP " + e.statusCode() + ")", e);
        } catch (AnthropicIoException e) {
            log.warn("[외부 API] api={} type={}", API_NAME, e.getClass().getSimpleName());
            if (isTimeout(e)) throw ExternalApiException.timeout(API_NAME, e);
            throw ExternalApiException.failed(API_NAME, "서버에 연결할 수 없습니다.", e);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[외부 API] api={} type={}", API_NAME, e.getClass().getSimpleName());
            throw ExternalApiException.failed(API_NAME, "응답을 처리할 수 없습니다.", e);
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedIOException) return true;
        }
        return false;
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

    /**
     * Claude 대화 규칙에 맞춘다: 빈 글은 빼고, 같은 쪽 연속 글은 합치고, 첫 글이 AI면 앞에 사용자 글을 둔다.
     * Sonnet 5.5는 마지막 글을 AI 글로 미리 채우는 것(prefill)을 받지 않으므로 마지막 글은 사용자 글이어야 한다.
     */
    static List<MessageParam> toMessages(List<AiMessage> messages) {
        List<String[]> merged = new ArrayList<>();
        for (AiMessage message : messages == null ? List.<AiMessage>of() : messages) {
            if (message == null || message.content() == null || message.content().isBlank()) continue;
            String role = "assistant".equals(message.role()) ? "assistant" : "user";
            if (merged.isEmpty() && "assistant".equals(role)) merged.add(new String[]{"user", "(이전 대화)"});
            if (!merged.isEmpty() && merged.getLast()[0].equals(role)) {
                merged.getLast()[1] = merged.getLast()[1] + "\n\n" + message.content();
            } else {
                merged.add(new String[]{role, message.content()});
            }
        }
        if (merged.isEmpty() || !"user".equals(merged.getLast()[0])) {
            throw ExternalApiException.failed(API_NAME, "백업으로 보낼 사용자 글이 없습니다.", null);
        }
        return merged.stream().map(pair -> "user".equals(pair[0])
                ? MessageParam.ofUser(pair[1]) : MessageParam.ofAssistant(pair[1])).toList();
    }

    /** 스키마 키워드 자리만 고친다. properties 안의 이름(예: "format", "pattern")은 그대로 둔다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> sanitizeSchema(Map<String, Object> schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> moved = new ArrayList<>();
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "properties", "$defs", "definitions" -> {
                    if (value instanceof Map<?, ?> named) {
                        Map<String, Object> children = new LinkedHashMap<>();
                        named.forEach((name, child) -> children.put(String.valueOf(name), sanitizeNode(child)));
                        out.put(key, children);
                    } else {
                        out.put(key, value);
                    }
                }
                case "items", "additionalProperties", "not", "anyOf", "allOf", "oneOf", "prefixItems" ->
                        out.put(key, sanitizeNode(value));
                case "minItems" -> {
                    if (value instanceof Number number && number.longValue() > 1) moved.add(key + "=" + value);
                    else out.put(key, value);
                }
                default -> {
                    if (MOVED_TO_DESCRIPTION.contains(key)) moved.add(key + "=" + value);
                    else out.put(key, value);
                }
            }
        }
        if (!moved.isEmpty()) {
            Object description = out.get("description");
            String note = "(" + String.join(", ", moved) + ")";
            out.put("description", description instanceof String text && !text.isBlank() ? text + " " + note : note);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object sanitizeNode(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return sanitizeSchema(copy);
        }
        if (node instanceof List<?> list) {
            return list.stream().map(ClaudeBackupClient::sanitizeNode).toList();
        }
        return node;
    }

    static Map<String, Object> choiceSchema(String name, List<String> values) {
        return Map.of("type", "object",
                "properties", Map.of(name, Map.of("type", "string", "enum", values)),
                "required", List.of(name),
                "additionalProperties", false);
    }

    record DataUrl(String mediaType, String base64) {
    }

    static DataUrl parseDataUrl(String dataUrl) {
        String marker = ";base64,";
        int at = dataUrl == null ? -1 : dataUrl.indexOf(marker);
        if (at < 0 || !dataUrl.startsWith("data:")) {
            throw ExternalApiException.failed(API_NAME, "사진 형식이 올바르지 않습니다.", null);
        }
        String mediaType = dataUrl.substring("data:".length(), at).trim().toLowerCase();
        if ("image/jpg".equals(mediaType)) mediaType = "image/jpeg";
        if (!IMAGE_TYPES.contains(mediaType)) {
            throw ExternalApiException.failed(API_NAME, "백업이 받지 않는 사진 형식입니다.", null);
        }
        return new DataUrl(mediaType, dataUrl.substring(at + marker.length()));
    }
}

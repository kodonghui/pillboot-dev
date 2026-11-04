package org.semiproject.pillbadeumserver.common.ai;

import com.anthropic.models.messages.MessageParam;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.exception.AiUnavailableException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V3-COMMON-005: Claude 백업의 요청 모양·응답 처리와 OpenAI 실패 뒤 백업 전환. 실제 외부 호출은 하지 않는다. */
class ClaudeBackupClientTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<AiMessage> MESSAGES = List.of(AiMessage.user("테스트 질문"));
    private static final Map<String, Object> LIMITED_SCHEMA = Map.of("type", "object",
            "properties", Map.of(
                    "choice", Map.of("type", "integer", "minimum", 0, "maximum", 5),
                    "pattern", Map.of("type", "string", "description", "이름이 pattern인 칸"),
                    "names", Map.of("type", "array", "maxItems", 2, "minItems", 1,
                            "items", Map.of("type", "string", "maxLength", 40))),
            "required", List.of("choice", "pattern", "names"),
            "additionalProperties", false);

    private HttpServer server;
    private String baseUrl;
    private final List<JsonNode> claudeBodies = new CopyOnWriteArrayList<>();
    private final List<String> betaHeaders = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> claudeReply = new AtomicReference<>();
    private final AtomicInteger claudeStatus = new AtomicInteger(200);
    private final AtomicInteger openAiCalls = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            claudeBodies.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            betaHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("anthropic-beta")));
            respond(exchange, claudeStatus.get(), claudeStatus.get() == 200 ? claudeReply.get()
                    : "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
        });
        // OpenAI 키가 거절되는 상황(오늘 운영 서버와 같은 401 invalid_api_key)
        server.createContext("/openai/responses", exchange -> {
            openAiCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 401, "{\"error\":{\"code\":\"invalid_api_key\"}}");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        replyText("{\"category\":\"MEDICATION\"}", "end_turn");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsSonnetMediumWithSchemaAndRefusalFallback() {
        JsonNode result = claude(true).json(ClaudeBackupClient.Kind.SHORT, "규칙", MESSAGES, LIMITED_SCHEMA);

        assertThat(result.path("category").asString()).isEqualTo("MEDICATION");
        JsonNode body = claudeBodies.getFirst();
        assertThat(body.path("model").asString()).isEqualTo("claude-sonnet-5-5");
        assertThat(body.path("max_tokens").asLong()).isEqualTo(16000);
        assertThat(body.path("system").asString()).isEqualTo("규칙");
        assertThat(body.path("output_config").path("effort").asString()).isEqualTo("medium");
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.path("fallbacks").asString()).isEqualTo("default");
        assertThat(betaHeaders.getFirst()).contains("server-side-fallback-2026-07-01");
        assertThat(body.path("messages").path(0).path("role").asString()).isEqualTo("user");

        JsonNode format = body.path("output_config").path("format");
        assertThat(format.path("type").asString()).isEqualTo("json_schema");
        JsonNode properties = format.path("schema").path("properties");
        assertThat(properties.path("choice").has("minimum")).isFalse();
        assertThat(properties.path("choice").has("maximum")).isFalse();
        assertThat(properties.path("choice").path("description").asString()).contains("minimum=0", "maximum=5");
        assertThat(properties.has("pattern")).isTrue();
        assertThat(properties.path("pattern").path("description").asString()).isEqualTo("이름이 pattern인 칸");
        assertThat(properties.path("names").has("maxItems")).isFalse();
        assertThat(properties.path("names").path("minItems").asInt()).isEqualTo(1);
        assertThat(properties.path("names").path("items").has("maxLength")).isFalse();
        assertThat(format.path("schema").path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void canTurnOffRefusalFallback() {
        claude(false).text(ClaudeBackupClient.Kind.ANSWER, "규칙", MESSAGES);

        assertThat(claudeBodies.getFirst().has("fallbacks")).isFalse();
        assertThat(betaHeaders.getFirst()).doesNotContain("server-side-fallback");
    }

    @Test
    void sendsPhotosAsBase64ImagesBeforeThePrompt() {
        claude(true).jsonFromImages(ClaudeBackupClient.Kind.PILL, "규칙", "앞뒤 사진",
                List.of("data:image/png;base64,AA==", "data:image/jpg;base64,BB=="), Map.of("type", "object"));

        JsonNode content = claudeBodies.getFirst().path("messages").path(0).path("content");
        assertThat(content.size()).isEqualTo(3);
        assertThat(content.path(0).path("type").asString()).isEqualTo("image");
        assertThat(content.path(0).path("source").path("type").asString()).isEqualTo("base64");
        assertThat(content.path(0).path("source").path("media_type").asString()).isEqualTo("image/png");
        assertThat(content.path(0).path("source").path("data").asString()).isEqualTo("AA==");
        assertThat(content.path(1).path("source").path("media_type").asString()).isEqualTo("image/jpeg");
        assertThat(content.path(2).path("type").asString()).isEqualTo("text");
        assertThat(content.path(2).path("text").asString()).isEqualTo("앞뒤 사진");
    }

    @Test
    void choiceAcceptsOnlyGivenValues() {
        var choices = List.of(new DecisionChoice("MEDICATION", "약 질문"), new DecisionChoice("BLOCKED", "약과 무관"));
        replyText("{\"category\":\"BLOCKED\"}", "end_turn");
        assertThat(claude(true).choice("오늘 날씨", "category", "질문을 분류한다.", choices)).isEqualTo("BLOCKED");
        JsonNode body = claudeBodies.getFirst();
        assertThat(body.path("system").asString()).contains("MEDICATION: 약 질문", "BLOCKED: 약과 무관");
        assertThat(body.path("output_config").path("format").path("schema").path("properties")
                .path("category").path("enum").toString()).contains("MEDICATION", "BLOCKED");

        replyText("{\"category\":\"OTHER\"}", "end_turn");
        assertThrows(ExternalApiException.class, () -> claude(true).choice("질문", "category", "분류", choices));
    }

    @Test
    void refusalCutOffAndHttpErrorsAreFailures() {
        replyText("", "refusal");
        assertThrows(ExternalApiException.class, () -> claude(true).text(ClaudeBackupClient.Kind.ANSWER, "규칙", MESSAGES));

        replyText("끊긴 답", "max_tokens");
        assertThrows(ExternalApiException.class, () -> claude(true).text(ClaudeBackupClient.Kind.ANSWER, "규칙", MESSAGES));

        claudeStatus.set(401);
        ExternalApiException failure = assertThrows(ExternalApiException.class,
                () -> claude(true).text(ClaudeBackupClient.Kind.SHORT, "규칙", MESSAGES));
        assertThat(failure.getFailureReason()).isEqualTo("(HTTP 401)");
        assertThat(failure.getMessage()).doesNotContain("invalid x-api-key");
    }

    @Test
    void blankKeyDisablesBackup() {
        ClaudeBackupClient client = new ClaudeBackupClient(JSON, "", baseUrl, "claude-sonnet-5-5", "medium",
                16000, true, 1, 1000, 3000, 3000, 3000, 3000);
        assertThat(client.isEnabled()).isFalse();
        assertThrows(ExternalApiException.class, () -> client.text(ClaudeBackupClient.Kind.SHORT, "규칙", MESSAGES));
        assertThat(claudeBodies).isEmpty();
    }

    @Test
    void messagesFollowClaudeTurnRules() {
        List<MessageParam> messages = ClaudeBackupClient.toMessages(List.of(
                AiMessage.assistant("이전 답"), AiMessage.user("질문1"), AiMessage.user("질문2"), AiMessage.user(" ")));
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).isUser()).isTrue();
        assertThat(messages.get(1).isAssistant()).isTrue();
        assertThat(messages.get(2).isUser()).isTrue();
        assertThat(messages.get(2).asUser().content().asString()).isEqualTo("질문1\n\n질문2");

        assertThrows(ExternalApiException.class,
                () -> ClaudeBackupClient.toMessages(List.of(AiMessage.user("질문"), AiMessage.assistant("답"))));
        assertThrows(ExternalApiException.class, () -> ClaudeBackupClient.toMessages(List.of()));
    }

    @Test
    void dataUrlsMustBeSupportedImages() {
        assertThat(ClaudeBackupClient.parseDataUrl("data:image/webp;base64,QQ==").mediaType()).isEqualTo("image/webp");
        assertThrows(ExternalApiException.class, () -> ClaudeBackupClient.parseDataUrl("data:image/heic;base64,QQ=="));
        assertThrows(ExternalApiException.class, () -> ClaudeBackupClient.parseDataUrl("https://example.com/a.png"));
    }

    /* ===== OpenAiClient → Claude 전환 ===== */

    @Test
    void rejectedOpenAiKeyFallsBackToClaude() {
        OpenAiClient openAi = openAi("rejected-key");
        openAi.setBackup(claude(true));
        replyText("백업 답", "end_turn");

        AiTextResult result = openAi.createTextResult("규칙", MESSAGES, 1600);

        assertThat(openAiCalls.get()).isEqualTo(1);
        assertThat(result.text()).isEqualTo("백업 답");
        assertThat(result.responseId()).isNull();
        assertThat(claudeBodies).hasSize(1);
    }

    @Test
    void everyGenerationKindUsesTheMatchingBackupCall() {
        ClaudeBackupClient backup = enabledMock();
        OpenAiClient openAi = openAi("");
        openAi.setBackup(backup);
        when(backup.text(any(), anyString(), anyList())).thenReturn("답");
        when(backup.json(any(), anyString(), anyList(), any())).thenReturn(JSON.createObjectNode());
        when(backup.jsonFromImages(any(), anyString(), anyString(), anyList(), any())).thenReturn(JSON.createObjectNode());
        when(backup.choice(anyString(), anyString(), anyString(), anyList())).thenReturn("MEDICATION");

        assertThat(openAi.createText("요약", MESSAGES, 100)).isEqualTo("답");
        openAi.createJson("검색어", MESSAGES, 100, "drug_query", Map.of("type", "object"));
        assertThat(openAi.createChoice("질문", "category", "분류", List.of(new DecisionChoice("MEDICATION", "약"))))
                .isEqualTo("MEDICATION");
        openAi.createJsonFromImages("규칙", "사진", List.of("data:image/png;base64,AA=="), 100,
                "photo_prescription_names", Map.of("type", "object"));
        openAi.createJsonFromImages("규칙", "사진", List.of("data:image/png;base64,AA=="), 100,
                "photo_pill_clues", Map.of("type", "object"));

        verify(backup).text(eq(ClaudeBackupClient.Kind.ANSWER), eq("요약"), eq(MESSAGES));
        verify(backup).json(eq(ClaudeBackupClient.Kind.SHORT), eq("검색어"), eq(MESSAGES), any());
        verify(backup).choice(eq("질문"), eq("category"), eq("분류"), anyList());
        verify(backup).jsonFromImages(eq(ClaudeBackupClient.Kind.PRESCRIPTION), anyString(), anyString(), anyList(), any());
        verify(backup).jsonFromImages(eq(ClaudeBackupClient.Kind.PILL), anyString(), anyString(), anyList(), any());
    }

    @Test
    void failedBackupKeepsTheOriginalOpenAiFailure() {
        ClaudeBackupClient backup = enabledMock();
        when(backup.text(any(), anyString(), anyList())).thenThrow(ExternalApiException.failed("Claude", "(HTTP 529)", null));
        OpenAiClient openAi = openAi("");
        openAi.setBackup(backup);

        AiUnavailableException failure = assertThrows(AiUnavailableException.class,
                () -> openAi.createText("규칙", MESSAGES, 100));
        assertThat(failure.getSuppressed()).hasSize(1);
    }

    @Test
    void noBackupWhenDisabledInterruptedOrForEmbeddings() {
        ClaudeBackupClient disabled = mock(ClaudeBackupClient.class);
        OpenAiClient openAi = openAi("");
        openAi.setBackup(disabled);
        assertThrows(AiUnavailableException.class, () -> openAi.createText("규칙", MESSAGES, 100));
        verify(disabled, never()).text(any(), anyString(), anyList());

        ClaudeBackupClient backup = enabledMock();
        openAi.setBackup(backup);
        Thread.currentThread().interrupt();
        try {
            assertThrows(AiUnavailableException.class, () -> openAi.createText("규칙", MESSAGES, 100));
        } finally {
            Thread.interrupted();
        }
        assertThrows(AiUnavailableException.class, () -> openAi.embed("문장"));
        verify(backup, never()).text(any(), anyString(), anyList());
    }

    /* ===== 보조 ===== */

    private ClaudeBackupClient claude(boolean refusalFallback) {
        return new ClaudeBackupClient(JSON, "synthetic-key", baseUrl, "claude-sonnet-5-5", "medium",
                16000, refusalFallback, 2, 1000, 3000, 3000, 3000, 3000);
    }

    private OpenAiClient openAi(String key) {
        return new OpenAiClient(JSON, key, baseUrl + "/openai", "chat", "decision", "vision", "embedding", "medium",
                2000, 3000, 3000, 3000, 1, 1000, new BigDecimal("0.10"), 3000);
    }

    private static ClaudeBackupClient enabledMock() {
        ClaudeBackupClient backup = mock(ClaudeBackupClient.class);
        when(backup.isEnabled()).thenReturn(true);
        return backup;
    }

    private void replyText(String text, String stopReason) {
        String content = text.isEmpty() ? "[]"
                : "[{\"type\":\"text\",\"text\":" + JSON.writeValueAsString(text) + "}]";
        claudeStatus.set(200);
        claudeReply.set("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5-5\","
                + "\"content\":" + content + ",\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":12,\"output_tokens\":5}}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.common.exception.AiUnavailableException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.ExternalApiCaller;
import org.semiproject.pillbadeumserver.common.external.RestClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** GPT-Live의 세션 생성·종료와 서버 sideband 연결을 담당한다. */
@Component
public class MedicationLiveClient {
    static final String GREETING = "안녕하세요 AI 복약도우미입니다. 어떻게 일정 등록을 도와드릴까요?";
    public record RemoteSession(String id, String sdp) {}
    public interface Connection {
        void send(Map<String, Object> event);
        void close();
    }

    private final ObjectMapper json;
    private final RestClient rest;
    private final HttpClient sockets;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String voice;

    public MedicationLiveClient(ObjectMapper json, @Value("${openai.api-key}") String apiKey,
                                @Value("${openai.base-url}") String baseUrl,
                                @Value("${OPENAI_LIVE_MODEL:gpt-live-1}") String model,
                                @Value("${OPENAI_LIVE_VOICE:marin}") String voice) {
        this.json = json;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.voice = voice;
        this.rest = RestClientFactory.builder(baseUrl, Duration.ofSeconds(5), Duration.ofSeconds(20))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).build();
        this.sockets = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public RemoteSession create(String sdp, String instructions) {
        if (apiKey == null || apiKey.isBlank() || model == null || model.isBlank()) {
            throw new AiUnavailableException("Live API 키·모델을 설정해주세요.");
        }
        var visibleEvents = List.of("session.started", "session.closed", "session.input_transcript.delta",
                "session.output_transcript.delta", "error").stream().map(type -> Map.of("type", type)).toList();
        var session = Map.of("model", model, "instructions", instructions, "store", false,
                "input", List.of(Map.of("type", "message", "role", "developer", "content", List.of(Map.of(
                        "type", "input_text", "text", "지금 한국어로 다음 인사를 한 번만 말한 뒤 사용자의 말을 기다린다: "
                                + GREETING + " 사용자가 먼저 말하면 그 요청부터 듣고 인사를 반복하지 않는다.")))),
                "audio", Map.of("output", Map.of("voice", voice)),
                "delegation", Map.of("type", "client"),
                "client", Map.of("data_channel", Map.of("allowed_client_events", List.of("session.close"),
                        "allowed_server_events", visibleEvents)));
        String body = ExternalApiCaller.call("OpenAI Live", () -> rest.post().uri("/live/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("session", session, "transport", Map.of("type", "webrtc", "sdp", sdp)))
                .retrieve().body(String.class));
        try {
            JsonNode response = json.readTree(body);
            String id = response.path("session").path("id").asString("");
            String answer = response.path("transport").path("sdp").asString("");
            if (id.isBlank() || answer.isBlank()) throw new IllegalArgumentException();
            return new RemoteSession(id, answer);
        } catch (RuntimeException e) {
            throw ExternalApiException.failed("OpenAI Live", "세션 응답이 올바르지 않습니다.", e);
        }
    }

    public Connection attach(String sessionId, Consumer<JsonNode> onEvent, Runnable onDisconnect) {
        URI uri = org.springframework.web.util.UriComponentsBuilder.fromUriString(baseUrl)
                .scheme(baseUrl.startsWith("https:") ? "wss" : "ws")
                .pathSegment("live", "sessions", sessionId, "attach").build().encode().toUri();
        try {
            WebSocket socket = sockets.newWebSocketBuilder().header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(uri, new SocketListener(json, onEvent, onDisconnect)).get(6, TimeUnit.SECONDS);
            return new Connection() {
                public void send(Map<String, Object> event) {
                    try {
                        socket.sendText(json.writeValueAsString(event), true).get(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw ExternalApiException.timeout("OpenAI Live", e);
                    } catch (Exception e) {
                        throw ExternalApiException.failed("OpenAI Live", "이벤트를 보낼 수 없습니다.", e);
                    }
                }
                public void close() { socket.abort(); }
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ExternalApiException.timeout("OpenAI Live", e);
        } catch (Exception e) {
            throw ExternalApiException.failed("OpenAI Live", "서버 연결을 시작할 수 없습니다.", e);
        }
    }

    /** 장애·유효시간 만료의 강제 종료용. 정상 종료는 session.close → session.closed 순서를 사용한다. */
    public void hangup(String sessionId) {
        ExternalApiCaller.call("OpenAI Live", () -> rest.post()
                .uri(builder -> builder.path("/live/sessions").pathSegment(sessionId, "hangup").build())
                .retrieve().toBodilessEntity());
    }

    static final class SocketListener implements WebSocket.Listener {
        private final ObjectMapper json;
        private final Consumer<JsonNode> events;
        private final Runnable disconnect;
        private final StringBuilder fragment = new StringBuilder();

        SocketListener(ObjectMapper json, Consumer<JsonNode> events, Runnable disconnect) {
            this.json = json;
            this.events = events;
            this.disconnect = disconnect;
        }

        @Override public void onOpen(WebSocket socket) { socket.request(1); }

        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            try {
                fragment.append(data);
                if (fragment.length() > 2_000_000) throw new IllegalArgumentException("과도한 이벤트");
                if (last) {
                    JsonNode event = json.readTree(fragment.toString());
                    fragment.setLength(0);
                    // 반사된 음성 바이트는 처리·저장하지 않는다. 원문을 로그로 남기지 않는다.
                    if (event != null) events.accept(event);
                }
                socket.request(1);
            } catch (RuntimeException e) {
                socket.abort();
                disconnect.run();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            disconnect.run();
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket socket, Throwable error) { disconnect.run(); }
    }
}

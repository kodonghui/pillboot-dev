package org.semiproject.pillbadeumserver.medication.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.exception.AiUnavailableException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MedicationLiveClientTests {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private String base;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }

    @Test
    void createsAClientDelegationWebrtcSessionWithRestrictedFrontendEvents() {
        var received = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        server.createContext("/live/sessions", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"session\":{\"id\":\"live_fixture\"},\"transport\":{\"type\":\"webrtc\",\"sdp\":\"answer\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        var result = client("fixture-key").create("offer", "fixture instructions");
        assertThat(result.id()).isEqualTo("live_fixture");
        assertThat(result.sdp()).isEqualTo("answer");
        assertThat(auth.get()).isEqualTo("Bearer fixture-key");
        var body = json.readTree(received.get());
        assertThat(body.path("session").path("model").asString()).isEqualTo("gpt-live-1");
        assertThat(body.path("session").path("delegation").path("type").asString()).isEqualTo("client");
        assertThat(body.path("session").path("store").asBoolean()).isFalse();
        assertThat(body.path("session").path("input").path(0).path("role").asString()).isEqualTo("developer");
        assertThat(body.path("session").path("input").path(0).path("content").path(0).path("text").asString())
                .contains("안녕하세요 AI 복약도우미입니다. 어떻게 일정 등록을 도와드릴까요?", "한 번만");
        assertThat(body.path("session").path("client").path("data_channel").path("allowed_client_events").size()).isEqualTo(1);
        assertThat(body.path("session").path("client").path("data_channel").path("allowed_client_events").path(0).asString()).isEqualTo("session.close");
        assertThat(body.path("transport").path("sdp").asString()).isEqualTo("offer");
    }

    @Test
    void missingKeyDoesNotCreateAPaidSession() {
        assertThatThrownBy(() -> client("").create("offer", "instructions")).isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void creationErrorsAreNotRetried() {
        var calls = new AtomicInteger();
        server.createContext("/live/sessions", exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(429, -1); exchange.close();
        });
        assertThatThrownBy(() -> client("fixture-key").create("offer", "instructions")).isInstanceOf(ExternalApiException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void hangupUsesTheRemoteSessionPath() {
        var calls = new AtomicInteger();
        server.createContext("/live/sessions/live_fixture/hangup", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) calls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        client("fixture-key").hangup("live_fixture");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void fragmentedWebsocketTextIsReassembledBeforeDispatching() {
        WebSocket socket = mock(WebSocket.class);
        var event = new AtomicReference<JsonNode>();
        var failures = new AtomicInteger();
        var listener = new MedicationLiveClient.SocketListener(json, event::set, failures::incrementAndGet);
        listener.onOpen(socket);
        listener.onText(socket, "{\"type\":\"session.input_transcript.", false);
        assertThat(event.get()).isNull();
        listener.onText(socket, "delta\",\"delta\":\"알아서 저장해\"}", true);
        assertThat(event.get().path("delta").asString()).isEqualTo("알아서 저장해");
        assertThat(failures.get()).isZero();
        verify(socket, times(3)).request(1);
    }

    @Test
    void invalidSocketMessageClosesInsteadOfTreatingItAsAToolRequest() {
        WebSocket socket = mock(WebSocket.class);
        var failures = new AtomicInteger();
        var listener = new MedicationLiveClient.SocketListener(json, ignored -> fail("Invalid JSON must not be dispatched"), failures::incrementAndGet);
        listener.onText(socket, "not-json", true);
        verify(socket).abort(); assertThat(failures.get()).isEqualTo(1);
    }

    private MedicationLiveClient client(String key) { return new MedicationLiveClient(json, key, base, "gpt-live-1", "marin"); }
}

package org.semiproject.pillbadeumserver.medication;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.exception.AiUnavailableException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationVoiceDtos.CommandRequest;
import org.semiproject.pillbadeumserver.medication.service.MedicationSpeechClient;
import org.semiproject.pillbadeumserver.medication.service.MedicationVoiceInterpreter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** 실제 과금 호출 없이 로컬 HTTP fixture로 Responses·Speech 요청 계약을 확인한다. */
class MedicationVoiceClientTests {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsStrictSchemaOwnedChoicesAndBothTodayAndSelectedDate() {
        var received = new AtomicReference<String>();
        String fields = """
                {"intent":"CREATE_SCHEDULE","myDrugId":null,"startDate":null,"endDate":null,"durationDays":7,
                 "doseTimes":null,"dailyCount":3,"dosePerTime":null,"timing":null}
                """;
        server.createContext("/responses", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = json.writeValueAsString(Map.of("status", "completed", "output", List.of(
                    Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", fields))))));
            respond(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
        });
        var interpreter = new MedicationVoiceInterpreter(openAi(), json);
        var request = new CommandRequest("일주일 동안 삼시세끼 약 일정 추가해줘", 13L, 25L, LocalDate.of(2026, 11, 3));
        var target = new MedicationTarget(25, 13, "관리자", "SELF", "테스트약", 1, null,
                null, null, null, LocalDateTime.of(2026, 10, 19, 9, 0));

        var result = interpreter.parse(request, LocalDate.of(2026, 10, 19), List.of(target));

        assertThat(result.durationDays()).isEqualTo(7);
        assertThat(result.dailyCount()).isEqualTo(3);
        assertThat(result.myDrugId()).isNull();
        var body = json.readTree(received.get());
        assertThat(body.path("store").asBoolean()).isFalse();
        assertThat(body.path("text").path("format").path("type").asString()).isEqualTo("json_schema");
        assertThat(body.path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(body.path("text").path("format").path("schema").path("additionalProperties").asBoolean()).isFalse();
        var input = json.readTree(body.path("input").path(0).path("content").asString());
        assertThat(input.path("today").asString()).isEqualTo("2026-10-19");
        assertThat(input.path("selectedDate").asString()).isEqualTo("2026-11-03");
        assertThat(input.path("candidates").size()).isEqualTo(1);
        assertThat(input.path("candidates").path(0).path("myDrugId").asLong()).isEqualTo(25);
    }

    @Test
    void speechUsesMp3AndReturnsAudioBytes() {
        var received = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        server.createContext("/audio/speech", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "audio/mpeg", new byte[]{1, 2, 3});
        });

        assertThat(speech("fixture-key", "speech-model").speak("관리자님의 약 일정을 저장했습니다."))
                .containsExactly(1, 2, 3);
        var body = json.readTree(received.get());
        assertThat(body.path("model").asString()).isEqualTo("speech-model");
        assertThat(body.path("response_format").asString()).isEqualTo("mp3");
        assertThat(body.path("input").asString()).isEqualTo("관리자님의 약 일정을 저장했습니다.");
        assertThat(authorization.get()).isEqualTo("Bearer fixture-key");
    }

    @Test
    void missingSpeechConfigurationNeverCallsTheProvider() {
        assertThatThrownBy(() -> speech("", "speech-model").speak("저장했습니다."))
                .isInstanceOf(AiUnavailableException.class);
        assertThatThrownBy(() -> speech("fixture-key", "").speak("저장했습니다."))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void speechErrorsAreNotRetriedOrReturnedAsAudio() {
        var calls = new AtomicInteger();
        server.createContext("/audio/speech", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 429, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        });
        assertThatThrownBy(() -> speech("fixture-key", "speech-model").speak("저장했습니다."))
                .isInstanceOf(ExternalApiException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void successfulHttpResponseWithJsonBodyIsNotAudio() {
        server.createContext("/audio/speech", exchange ->
                respond(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> speech("fixture-key", "speech-model").speak("저장했습니다."))
                .isInstanceOf(ExternalApiException.class);
    }

    private OpenAiClient openAi() {
        return new OpenAiClient(json, "fixture-key", baseUrl, "fixture-model", "decision", "fixture-model", "embedding", "",
                1000, 2000, 2000, 60000, 1, 100, new java.math.BigDecimal("0.10"), 85_000);
    }

    private MedicationSpeechClient speech(String key, String model) {
        return new MedicationSpeechClient(key, baseUrl, model, "coral", 1000, 2000, 1, 100);
    }

    private void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}

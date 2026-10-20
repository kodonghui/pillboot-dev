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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** 저장 완료 문구만 MP3로 변환한다. 오디오·사용자 문장을 파일이나 로그에 남기지 않는다. */
@Component
public class MedicationSpeechClient {
    private final RestClient client;
    private final boolean configured;
    private final String model;
    private final String voice;
    private final Semaphore permits;
    private final long permitTimeoutMs;

    public MedicationSpeechClient(@Value("${openai.api-key}") String apiKey,
                                  @Value("${openai.base-url}") String baseUrl,
                                  @Value("${OPENAI_TTS_MODEL:}") String model,
                                  @Value("${OPENAI_TTS_VOICE:coral}") String voice,
                                  @Value("${openai.connect-timeout-ms}") long connectTimeoutMs,
                                  @Value("${openai.long-read-timeout-ms}") long readTimeoutMs,
                                  @Value("${openai.max-concurrent-calls:4}") int maxConcurrentCalls,
                                  @Value("${openai.permit-timeout-ms:1000}") long permitTimeoutMs) {
        if (maxConcurrentCalls < 1 || permitTimeoutMs < 1) throw new IllegalArgumentException("음성 호출 제한은 양수여야 합니다.");
        this.client = RestClientFactory.builder(baseUrl, Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(readTimeoutMs))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).build();
        this.model = model;
        this.voice = voice;
        this.configured = apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank() && voice != null && !voice.isBlank();
        this.permits = new Semaphore(maxConcurrentCalls);
        this.permitTimeoutMs = permitTimeoutMs;
    }

    public byte[] speak(String message) {
        if (!configured) throw new AiUnavailableException("완료 음성 모델이 설정되지 않았습니다. (OPENAI_TTS_MODEL)");
        var response = ExternalApiCaller.withPermit("OpenAI TTS", permits, permitTimeoutMs,
                () -> ExternalApiCaller.call("OpenAI TTS", () -> client.post().uri("/audio/speech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("model", model, "voice", voice, "input", message, "response_format", "mp3"))
                        .retrieve().toEntity(byte[].class)));
        byte[] audio = response.getBody();
        var type = response.getHeaders().getContentType();
        if (audio == null || audio.length == 0 || type == null
                || !(type.getType().equals("audio") || type.isCompatibleWith(MediaType.APPLICATION_OCTET_STREAM))) {
            throw ExternalApiException.failed("OpenAI TTS", "음성 응답이 올바르지 않습니다.", null);
        }
        return audio;
    }
}

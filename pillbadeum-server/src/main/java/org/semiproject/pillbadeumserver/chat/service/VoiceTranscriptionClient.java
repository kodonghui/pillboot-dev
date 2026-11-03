package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.semiproject.pillbadeumserver.common.exception.AiUnavailableException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.ExternalApiCaller;
import org.semiproject.pillbadeumserver.common.external.RestClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * V3-KAKAO-006(8.3): 음성 하나를 OpenAI 받아쓰기로 보낸다. 음성만 보내고 언어·힌트 글·사용자 정보는 보내지 않는다.
 * 음성·받아쓴 글을 파일이나 로그에 남기지 않는다.
 */
@Component
public class VoiceTranscriptionClient {
    private static final String API_NAME = "OpenAI 받아쓰기";
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final boolean configured;
    private final String model;
    private final Semaphore permits;
    private final long permitTimeoutMs;

    public VoiceTranscriptionClient(@Value("${openai.api-key}") String apiKey,
                                    @Value("${openai.base-url}") String baseUrl,
                                    @Value("${openai.model.transcribe:}") String model,
                                    @Value("${openai.connect-timeout-ms}") long connectTimeoutMs,
                                    @Value("${openai.long-read-timeout-ms}") long readTimeoutMs,
                                    @Value("${openai.max-concurrent-calls:4}") int maxConcurrentCalls,
                                    @Value("${openai.permit-timeout-ms:1000}") long permitTimeoutMs,
                                    ObjectMapper objectMapper) {
        if (maxConcurrentCalls < 1 || permitTimeoutMs < 1) throw new IllegalArgumentException("받아쓰기 호출 제한은 양수여야 합니다.");
        this.client = RestClientFactory.builder(baseUrl, Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(readTimeoutMs))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).build();
        this.objectMapper = objectMapper;
        this.model = model;
        this.configured = apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
        this.permits = new Semaphore(maxConcurrentCalls);
        this.permitTimeoutMs = permitTimeoutMs;
    }

    /** 받아쓴 글을 그대로 돌려준다. 키·모델이 없으면 503, 호출 실패·시간 초과는 기존 외부 호출 오류(503)로 바꾼다. */
    public String transcribe(byte[] audio, AudioClip.Format format) {
        if (!configured) throw new AiUnavailableException("받아쓰기 모델이 설정되지 않았습니다. (OPENAI_TRANSCRIBE_MODEL)");
        var fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(format.mediaType()));
        var file = new ByteArrayResource(audio) {
            @Override
            public String getFilename() { return format.fileName(); }
        };
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new HttpEntity<>(file, fileHeaders));
        form.add("model", model);
        form.add("response_format", "json");
        String body = ExternalApiCaller.withPermit(API_NAME, permits, permitTimeoutMs,
                () -> ExternalApiCaller.call(API_NAME, () -> client.post().uri("/audio/transcriptions")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(form)
                        .retrieve()
                        .onStatus(status -> status.value() == 401, (request, response) -> {
                            throw new AiUnavailableException("AI 기능 설정을 확인해주세요.");
                        })
                        .body(String.class)));
        JsonNode text;
        try {
            text = body == null ? null : objectMapper.readTree(body).path("text");
        } catch (RuntimeException e) {
            throw ExternalApiException.failed(API_NAME, "받아쓰기 응답을 읽을 수 없습니다.", e);
        }
        if (text == null || !text.isString()) throw ExternalApiException.failed(API_NAME, "받아쓰기 응답이 올바르지 않습니다.", null);
        return text.asString();
    }
}

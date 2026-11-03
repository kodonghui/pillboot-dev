package org.semiproject.pillbadeumserver.chat;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.controller.VoiceTranscriptionController;
import org.semiproject.pillbadeumserver.chat.service.AudioClip;
import org.semiproject.pillbadeumserver.chat.service.VoiceTranscriptionClient;
import org.semiproject.pillbadeumserver.chat.service.VoiceTranscriptionService;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.config.WebConfig;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.security.JwtAccessDeniedHandler;
import org.semiproject.pillbadeumserver.common.security.JwtAuthenticationEntryPoint;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.semiproject.pillbadeumserver.common.security.SecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** V3-KAKAO-006(TASK-032): 실제 보안 필터·음성 1개 검사·오류 코드와 응답 모양을 확인한다. 받아쓰기 호출만 대역이다. */
@WebMvcTest(controllers = VoiceTranscriptionController.class)
@ActiveProfiles("test")
@Import({VoiceTranscriptionService.class, SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, WebConfig.class, GlobalExceptionHandler.class})
class VoiceTranscriptionWebTests {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean VoiceTranscriptionClient client;

    @Test
    void oneVoiceFileGetsOnlyTheTextBack() throws Exception {
        when(client.transcribe(any(), eq(AudioClip.Format.M4A))).thenReturn(" 타이레놀 등록해 줘 ");
        mvc.perform(multipart("/api/voice-transcriptions").file(audio(15_000)).header("Authorization", auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.text").value("타이레놀 등록해 줘"));
    }

    @Test
    void loginIsRequiredAndMissingOrExtraFilesAreRejectedBeforeTranscribing() throws Exception {
        mvc.perform(multipart("/api/voice-transcriptions").file(audio(15_000))).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/voice-transcriptions").header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(multipart("/api/voice-transcriptions").file(audio(15_000)).file(audio(15_000)).header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(multipart("/api/voice-transcriptions").file(new MockMultipartFile("image", "v.m4a", "audio/mp4",
                        audioBytes(15_000))).header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(client);
    }

    @Test
    void lengthFormatEmptyAndFailureUseTheContractCodes() throws Exception {
        mvc.perform(multipart("/api/voice-transcriptions").file(audio(61_000)).header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUDIO_TOO_LONG"));
        mvc.perform(multipart("/api/voice-transcriptions").file(new MockMultipartFile("audio", "v.mp3", "audio/mpeg",
                        "ID3-not-readable".getBytes(StandardCharsets.US_ASCII))).header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
        verifyNoInteractions(client);

        when(client.transcribe(any(), any())).thenReturn("   ");
        mvc.perform(multipart("/api/voice-transcriptions").file(audio(15_000)).header("Authorization", auth()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("TRANSCRIPTION_EMPTY"));
        when(client.transcribe(any(), any())).thenThrow(ExternalApiException.failed("OpenAI 받아쓰기", "(HTTP 500)", null));
        mvc.perform(multipart("/api/voice-transcriptions").file(audio(15_000)).header("Authorization", auth()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EXTERNAL_API_FAILED"));
    }

    private String auth() {
        return "Bearer " + tokens.createAccessToken(7L, "yakbong@example.test", "ROLE_USER");
    }

    private static MockMultipartFile audio(long millis) {
        return new MockMultipartFile("audio", "kakao-voice", "application/octet-stream", audioBytes(millis));
    }

    /** ftyp·moov(mvhd 판 0, timescale 1000)만 있는 가장 작은 m4a 머리. */
    private static byte[] audioBytes(long millis) {
        byte[] ftyp = "M4A \0\0\0\0".getBytes(StandardCharsets.US_ASCII);
        byte[] mvhd = ByteBuffer.allocate(100).putInt(0).putInt(0).putInt(0).putInt(1000).putInt((int) millis).array();
        return ByteBuffer.allocate(8 + ftyp.length + 16 + mvhd.length)
                .putInt(8 + ftyp.length).put("ftyp".getBytes(StandardCharsets.US_ASCII)).put(ftyp)
                .putInt(16 + mvhd.length).put("moov".getBytes(StandardCharsets.US_ASCII))
                .putInt(8 + mvhd.length).put("mvhd".getBytes(StandardCharsets.US_ASCII)).put(mvhd).array();
    }
}

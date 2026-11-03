package org.semiproject.pillbadeumserver.chat.service;

import java.io.IOException;
import org.semiproject.pillbadeumserver.chat.dto.VoiceTranscriptionResponse;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * V3-KAKAO-006: 카카오 다리가 보낸 음성 하나를 글로 바꾼다. 저장하는 것이 없고, 받아쓰기를 부르기 전에 형식·크기·재생 시간을
 * 확인한다. 로그에는 형식·크기·재생 시간·걸린 시간·글자 수만 남긴다(음성·글 원문은 남기지 않음).
 */
@Service
public class VoiceTranscriptionService {
    private static final Logger log = LoggerFactory.getLogger(VoiceTranscriptionService.class);
    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final long MAX_MILLIS = 60_000;
    private final VoiceTranscriptionClient client;

    public VoiceTranscriptionService(VoiceTranscriptionClient client) {
        this.client = client;
    }

    public VoiceTranscriptionResponse transcribe(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) throw new BusinessException(ErrorCode.INVALID_INPUT, "음성 파일 1개를 보내 주세요.");
        if (audio.getSize() > MAX_BYTES) throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "음성은 5MB 이하만 받을 수 있습니다.");
        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "음성 파일을 읽지 못했습니다.", e);
        }
        var clip = AudioClip.read(bytes).orElseThrow(() ->
                new BusinessException(ErrorCode.INVALID_FILE, "재생 시간을 읽을 수 있는 m4a·WAV 음성만 받아쓸 수 있습니다."));
        if (clip.durationMillis() > MAX_MILLIS) throw new BusinessException(ErrorCode.AUDIO_TOO_LONG, "1분 이하 음성만 받아쓸 수 있습니다.");

        long start = System.nanoTime();
        String text = client.transcribe(bytes, clip.format()).strip();
        log.info("voice_transcription format={} bytes={} audioMs={} ms={} chars={}", clip.format(), bytes.length,
                clip.durationMillis(), (System.nanoTime() - start) / 1_000_000, text.length());
        if (text.isEmpty()) throw new BusinessException(ErrorCode.TRANSCRIPTION_EMPTY, "음성에서 글을 받아쓰지 못했습니다.");
        return new VoiceTranscriptionResponse(text);
    }
}

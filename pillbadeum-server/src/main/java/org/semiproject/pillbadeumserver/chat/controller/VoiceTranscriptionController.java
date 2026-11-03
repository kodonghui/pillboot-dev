package org.semiproject.pillbadeumserver.chat.controller;

import java.util.List;
import org.semiproject.pillbadeumserver.chat.dto.VoiceTranscriptionResponse;
import org.semiproject.pillbadeumserver.chat.service.VoiceTranscriptionService;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/** V3-KAKAO-006: 카카오 다리의 음성 받아쓰기(openapi-v3 transcribeVoice). 저장하는 것이 없다. */
@RestController
public class VoiceTranscriptionController {
    private final VoiceTranscriptionService transcriptions;

    public VoiceTranscriptionController(VoiceTranscriptionService transcriptions) {
        this.transcriptions = transcriptions;
    }

    @PostMapping(path = "/api/voice-transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VoiceTranscriptionResponse transcribe(@RequestParam(required = false) MultipartFile audio,
                                                 MultipartHttpServletRequest multipart) {
        // 같은 이름의 파일을 여러 번 보내면 단일 MultipartFile 바인딩은 첫 개만 고르므로 전체 수를 다시 센다.
        int uploaded = multipart.getMultiFileMap().values().stream().mapToInt(List::size).sum();
        if (audio == null || audio.isEmpty() || uploaded != 1)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "음성 파일 1개를 보내 주세요.");
        return transcriptions.transcribe(audio);
    }
}

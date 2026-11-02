package org.semiproject.pillbadeumserver.chat.controller;

import jakarta.validation.constraints.Positive;
import java.util.List;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.chat.service.QuestionService;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/** V3-CHAT-006: 상담방에서 처방전·약봉투 사진 1장을 받아 등록 제안 답변을 돌려준다(openapi-v3 submitChatPhoto). */
@RestController
@RequestMapping("/api/chat-sessions/{sessionId}/photos")
public class ChatPhotoController {
    private final QuestionService questions;

    public ChatPhotoController(QuestionService questions) {
        this.questions = questions;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<QuestionResponse> submit(@PathVariable @Positive Long sessionId,
                                                   @RequestParam(required = false) MultipartFile image,
                                                   MultipartHttpServletRequest multipart) {
        // 같은 이름의 파일을 여러 번 보내면 단일 MultipartFile 바인딩은 첫 장만 고르므로 전체 수를 다시 센다.
        int uploaded = multipart.getMultiFileMap().values().stream().mapToInt(List::size).sum();
        if (image == null || image.isEmpty() || uploaded != 1)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "처방전·약봉투 사진 1장을 올려 주세요.");
        return ResponseEntity.ok(questions.submitPhoto(sessionId, image));
    }
}

package org.semiproject.pillbadeumserver.chat.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import org.semiproject.pillbadeumserver.chat.domain.ChatChannel;
import org.semiproject.pillbadeumserver.chat.service.ChatSessionService;
import org.semiproject.pillbadeumserver.chat.dto.ChatSessionListResponse;
import org.semiproject.pillbadeumserver.chat.dto.ChatSessionResponse;
import org.semiproject.pillbadeumserver.common.security.KakaoBridgeAuthFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profiles/{profileId}/chat-sessions")
public class ChatSessionController {

    /** 약봉이 운영 계정(자동 프로필)으로 카톡 상담방을 열 때 다리가 보내는 머리글(9.2 Q5 B안). */
    static final String CHANNEL_HEADER = "X-Pill-Channel";

    private final ChatSessionService sessions;

    public ChatSessionController(ChatSessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    public ChatSessionListResponse list(@PathVariable @Positive Long profileId) {
        return sessions.list(profileId);
    }

    @PostMapping
    public ResponseEntity<ChatSessionResponse> create(@PathVariable @Positive Long profileId,
                                                      @RequestHeader(name = CHANNEL_HEADER, required = false) String channel,
                                                      HttpServletRequest request) {
        boolean kakao = KakaoBridgeAuthFilter.isLinkedMemberRequest(request) || "KAKAO".equalsIgnoreCase(channel);
        var result = sessions.createOrReuse(profileId, kakao ? ChatChannel.KAKAO : ChatChannel.WEB);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.session());
    }
}

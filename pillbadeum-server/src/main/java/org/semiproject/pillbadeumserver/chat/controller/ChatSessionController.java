package org.semiproject.pillbadeumserver.chat.controller;

import jakarta.validation.constraints.Positive;
import org.semiproject.pillbadeumserver.chat.service.ChatSessionService;
import org.semiproject.pillbadeumserver.chat.dto.ChatSessionListResponse;
import org.semiproject.pillbadeumserver.chat.dto.ChatSessionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profiles/{profileId}/chat-sessions")
public class ChatSessionController {

    private final ChatSessionService sessions;

    public ChatSessionController(ChatSessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    public ChatSessionListResponse list(@PathVariable @Positive Long profileId) {
        return sessions.list(profileId);
    }

    @PostMapping
    public ResponseEntity<ChatSessionResponse> create(@PathVariable @Positive Long profileId) {
        var result = sessions.createOrReuse(profileId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.session());
    }
}

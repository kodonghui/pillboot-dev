package org.semiproject.pillbadeumserver.chat.dto;

import java.time.OffsetDateTime;
import org.semiproject.pillbadeumserver.chat.domain.ChatSession;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;

public record ChatSessionResponse(Long sessionId, String title, OffsetDateTime createdAt) {

    public static ChatSessionResponse from(ChatSession session) {
        return new ChatSessionResponse(session.getSessionId(), session.getTitle(),
                session.getCreatedAt().atZone(TimeZoneConfig.ZONE).toOffsetDateTime());
    }
}

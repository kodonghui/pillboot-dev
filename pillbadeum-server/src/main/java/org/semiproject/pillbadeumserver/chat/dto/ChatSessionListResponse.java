package org.semiproject.pillbadeumserver.chat.dto;

import java.time.OffsetDateTime;
import java.util.List;
import org.semiproject.pillbadeumserver.chat.domain.ChatSession;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;

public record ChatSessionListResponse(List<Session> sessions) {

    public static ChatSessionListResponse from(List<ChatSession> sessions) {
        return new ChatSessionListResponse(sessions.stream().map(Session::from).toList());
    }

    public record Session(Long sessionId, String title, OffsetDateTime updatedAt) {

        private static Session from(ChatSession session) {
            return new Session(session.getSessionId(), session.getTitle(),
                    session.getUpdatedAt().atZone(TimeZoneConfig.ZONE).toOffsetDateTime());
        }
    }
}

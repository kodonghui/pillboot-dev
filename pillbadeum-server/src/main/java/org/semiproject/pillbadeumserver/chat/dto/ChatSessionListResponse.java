package org.semiproject.pillbadeumserver.chat.dto;

import java.time.OffsetDateTime;
import java.util.List;
import org.semiproject.pillbadeumserver.chat.domain.ChatChannel;
import org.semiproject.pillbadeumserver.chat.domain.ChatSession;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;

public record ChatSessionListResponse(List<Session> sessions) {

    public static ChatSessionListResponse from(List<ChatSession> sessions) {
        return new ChatSessionListResponse(sessions.stream().map(Session::from).toList());
    }

    /** channel은 v3에서 기존 칸 뒤에 더한 칸이다(CHT-01 `카톡` 꼬리표, 9.2 Q5 B안). */
    public record Session(Long sessionId, String title, OffsetDateTime updatedAt, ChatChannel channel) {

        private static Session from(ChatSession session) {
            return new Session(session.getSessionId(), session.getTitle(),
                    session.getUpdatedAt().atZone(TimeZoneConfig.ZONE).toOffsetDateTime(), session.getChannel());
        }
    }
}

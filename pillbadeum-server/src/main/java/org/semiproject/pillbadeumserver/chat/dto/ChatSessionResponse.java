package org.semiproject.pillbadeumserver.chat.dto;

import java.time.OffsetDateTime;
import org.semiproject.pillbadeumserver.chat.domain.ChatChannel;
import org.semiproject.pillbadeumserver.chat.domain.ChatSession;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;

/** 상담방 열기 응답. channel은 v3에서 기존 칸 뒤에 더한 칸이다(V3-KAKAO-014, 9.2 Q5 B안). */
public record ChatSessionResponse(Long sessionId, String title, OffsetDateTime createdAt, ChatChannel channel) {

    public static ChatSessionResponse from(ChatSession session) {
        return new ChatSessionResponse(session.getSessionId(), session.getTitle(),
                session.getCreatedAt().atZone(TimeZoneConfig.ZONE).toOffsetDateTime(), session.getChannel());
    }
}

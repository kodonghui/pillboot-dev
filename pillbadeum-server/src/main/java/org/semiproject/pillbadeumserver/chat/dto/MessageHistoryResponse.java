package org.semiproject.pillbadeumserver.chat.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.semiproject.pillbadeumserver.chat.domain.ChatMessage;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;

/** CHAT-003: 화면에 필요한 방 정보와 턴 단위 이력만 전달한다. 원문 content의 근거 꼬리표도 보존한다. */
public record MessageHistoryResponse(Long sessionId, Long profileId, String profileName, String title,
                                     long drugCount, Long retryMessageId, List<Message> messages, boolean hasMore) {
    public enum Role { USER, ASSISTANT }

    public record Message(Long messageId, int turnNo, Role role, String content, OffsetDateTime createdAt, DurNotice durNotice) {
        public Message(Long messageId, int turnNo, Role role, String content, OffsetDateTime createdAt) {
            this(messageId, turnNo, role, content, createdAt, null);
        }
        /** DB 소문자 역할을 API 대문자로 변환하며 저장 시각은 기존 KST 계약을 적용한다. */
        public static Message from(ChatMessage message) {
            return new Message(message.getMessageId(), message.getTurnNo(),
                    Role.valueOf(message.getRole().toUpperCase(Locale.ROOT)), message.getContent(),
                    message.getCreatedAt().atZone(TimeZoneConfig.ZONE).toOffsetDateTime(), message.getDurNotice());
        }
    }
}

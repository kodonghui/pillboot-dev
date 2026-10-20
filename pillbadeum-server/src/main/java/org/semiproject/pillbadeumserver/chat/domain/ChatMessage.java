package org.semiproject.pillbadeumserver.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import org.semiproject.pillbadeumserver.chat.dto.DurNotice;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** CHAT-004·007: 통과 질문 또는 답변 한 행이다. 팀 SQL의 trun_no와 소문자 role을 그대로 사용한다. */
@Entity
@Table(name = "chat_message")
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "trun_no", nullable = false)
    private int turnNo;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "dur_notice", columnDefinition = "JSON")
    @Convert(converter = DurNoticeConverter.class)
    private DurNotice durNotice;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ChatMessage() {}

    /** 정규화한 PASS 질문·다음 턴·KST 시각으로 객체를 만든다. 미계산 tokenCount는 null로 둔다. */
    public static ChatMessage question(Long sessionId, int turnNo, String content, LocalDateTime now) {
        ChatMessage message = new ChatMessage();
        message.sessionId = sessionId;
        message.turnNo = turnNo;
        message.role = "user";
        message.content = content;
        message.createdAt = now;
        return message;
    }

    /** CHAT-007: 이미 저장된 질문과 같은 턴에 assistant 답변을 만든다. 근거는 content 꼬리표에 포함한다. */
    public static ChatMessage answer(Long sessionId, int turnNo, String content, LocalDateTime now) {
        ChatMessage message = question(sessionId, turnNo, content, now);
        message.role = "assistant";
        return message;
    }

    public static ChatMessage answer(Long sessionId, int turnNo, String content, LocalDateTime now, DurNotice notice) {
        var message = answer(sessionId, turnNo, content, now);
        message.durNotice = notice;
        return message;
    }

    public DurNotice getDurNotice() { return durNotice; }

    public Long getMessageId() { return messageId; }
    public int getTurnNo() { return turnNo; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

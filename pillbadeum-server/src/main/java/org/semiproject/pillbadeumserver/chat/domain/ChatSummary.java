package org.semiproject.pillbadeumserver.chat.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** CHAT-009: 기존 chat_summary의 고정 10턴 구간 요약이다. 원문·대화방 활동 시각은 변경하지 않는다. */
@Entity
@Table(name = "chat_summary")
public class ChatSummary {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "summary_id") private Long summaryId;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "from_turn", nullable = false) private int fromTurn;
    @Column(name = "to_turn", nullable = false) private int toTurn;
    @Column(name = "content", nullable = false, columnDefinition = "TEXT") private String content;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;

    protected ChatSummary() {}

    public static ChatSummary of(long sessionId, int fromTurn, int toTurn, String content, LocalDateTime now) {
        var summary = new ChatSummary();
        summary.sessionId = sessionId; summary.fromTurn = fromTurn; summary.toTurn = toTurn;
        summary.content = content; summary.createdAt = now;
        return summary;
    }

    public Long getSummaryId() { return summaryId; }
    public Long getSessionId() { return sessionId; }
    public int getFromTurn() { return fromTurn; }
    public int getToTurn() { return toTurn; }
    public String getContent() { return content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

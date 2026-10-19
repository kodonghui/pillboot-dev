package org.semiproject.pillbadeumserver.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "chat_session")
@DynamicUpdate
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "profile_id")
    private Long profileId;

    @Column(name = "title")
    private String title;

    @Column(name = "last_response_id")
    private String lastResponseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "snap_birth_year", nullable = false)
    private Long snapBirthYear;

    @Column(name = "snap_gender", nullable = false, length = 10)
    private String snapGender;

    @Column(name = "snap_pregnant")
    private Boolean snapPregnant;

    protected ChatSession() {}

    public static ChatSession create(Long memberId, Long profileId, int birthYear,
                                     String gender, boolean pregnant, LocalDateTime now) {
        ChatSession session = new ChatSession();
        session.memberId = memberId;
        session.profileId = profileId;
        session.snapBirthYear = (long) birthYear;
        session.snapGender = gender;
        session.snapPregnant = pregnant;
        session.createdAt = now;
        session.updatedAt = now;
        return session;
    }

    // 빈 방을 다시 열어도 초기 사본은 보존한다. 첫 PASS 질문의 확정은 CHAT-004에서 처리한다.
    public void reopen(LocalDateTime now) {
        updatedAt = now;
    }

    public Long getSessionId() { return sessionId; }
    public String getTitle() { return title; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

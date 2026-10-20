package org.semiproject.pillbadeumserver.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.DynamicUpdate;

/**
 * CHAT-001·002·004의 대화방 한 행이다. memberId는 소유 회원, profileId는 상담 대상 프로필이다.
 * 프로필별로 여러 방을 가질 수 있으며 snap_*는 방에 보관하는 프로필 사본이다.
 */
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

    /**
     * 소유 회원·프로필 번호와 현재 프로필 값으로 새 방 객체를 만든다. DB 저장은 Service가 요청한다.
     * now를 생성·활동 시각에 함께 사용하며 제목과 응답 연결 ID는 아직 비워 둔다.
     * 이 사본은 초기값이며 CHAT-004가 첫 PASS 질문을 저장할 때 현재 프로필로 확정한다.
     */
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

    /** 빈 방의 활동 시각만 갱신한다. 생성 시각·초기 사본은 재사용 때 보존한다. */
    public void reopen(LocalDateTime now) {
        recordActivity(now);
    }

    /** 첫 USER 질문일 때만 호출한다. 현재 프로필 사본과 질문 앞 20자의 제목을 함께 확정한다. */
    public void confirmFirstQuestion(int birthYear, String gender, boolean pregnant, String question) {
        snapBirthYear = (long) birthYear;
        snapGender = gender;
        snapPregnant = pregnant;
        // 이모지 등 보조 문자를 중간에서 잘라 잘못된 문자열을 저장하지 않는다.
        int characters = question.codePointCount(0, question.length());
        title = question.substring(0, question.offsetByCodePoints(0, Math.min(20, characters)))
                + (characters > 20 ? "…" : "");
    }

    /** PASS 질문 저장과 같은 트랜잭션에서 마지막 활동 시각을 갱신한다. */
    public void recordActivity(LocalDateTime now) {
        updatedAt = now;
    }

    /** 답변 저장과 함께 활동 시각·보조 응답 ID를 기록하며 프로필 사본은 변경하지 않는다. */
    public void recordAnswer(LocalDateTime now, String responseId) {
        updatedAt = now;
        lastResponseId = responseId;
    }

    public Long getSessionId() { return sessionId; }
    public Long getMemberId() { return memberId; }
    public Long getProfileId() { return profileId; }
    public Long getSnapBirthYear() { return snapBirthYear; }
    public String getSnapGender() { return snapGender; }
    public Boolean getSnapPregnant() { return snapPregnant; }
    public String getTitle() { return title; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}

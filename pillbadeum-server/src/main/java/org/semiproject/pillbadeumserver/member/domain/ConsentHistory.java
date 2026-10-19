package org.semiproject.pillbadeumserver.member.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "consent_history")
public class ConsentHistory {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "consent_history_id")
    private Long consentHistoryId; // 약관 동의 이력 코드


    /* 동의 이력 조회 시 회원, 약관 상세정보를 항상 함께 읽지 않고, 필요한 경우에만 조회. */

    /* 여러 개의 약관동의이력내역이 회원 한 명을 가리킨다. */
    /*  이력을 조회할 때 회원 정보까지 항상 필요하지 않으므로
    *   회원 정보가 실제로 필요할 때 조회하도록 LAZY로 설정한다.
    */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    /* 동의 이력 여러 건이 같은 약관 한 건을 가리킬 수 있다. */
    /*  DB에는 terms_id가 저장되어, 어떤 약관에 동의했는지 알 수 있다.
     * 약관 제목과 본문을 항상 읽지 않고 필요할 때 조회하도록 LAZY로 설정한다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "terms_id")
    private Consent consent;

    @Column(name = "consent_status")
    private String consentStatus;

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;


    public ConsentHistory() {
    }

    public ConsentHistory( Member member, Consent consent,
                          String consentStatus) {
        this.member = member;
        this.consent = consent;
        this.consentStatus = consentStatus;
        this.recordedAt = LocalDateTime.now();
    }

    public Long getConsentHistoryId() {
        return consentHistoryId;
    }

    public void setConsentHistoryId(Long consentHistoryId) {
        this.consentHistoryId = consentHistoryId;
    }

    public Member getMember() {
        return member;
    }

    public void setMember(Member member) {
        this.member = member;
    }

    public Consent getConsent() {
        return consent;
    }

    public void setConsent(Consent consent) {
        this.consent = consent;
    }

    public String getConsentStatus() {
        return consentStatus;
    }

    public void setConsentStatus(String consentStatus) {
        this.consentStatus = consentStatus;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(LocalDateTime recordedAt) {
        this.recordedAt = recordedAt;
    }

    @Override
    public String toString() {
        return "ConsentHistory{" +
            "consentHistoryId=" + consentHistoryId +
            ", consentStatus='" + consentStatus + '\'' +
            ", recordedAt=" + recordedAt +
            '}';
    }
}

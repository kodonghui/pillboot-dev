package org.semiproject.pillbadeumserver.member.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "consent")
public class Consent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "terms_id")
    private Long termsId; // 약관 코드

    @Column(name = "terms_type")
    private String termsType; // 약관 종류

    @Column(name = "terms_version")
    private String termsVersion; // 약관 버전

    @Column(name = "terms_title")
    private String termsTitle; // 약관 제목

    @Column(name = "terms_content")
    private String termsContent; // 약관 내용

    @Column(name = "is_required")
    private boolean required; // 약관 필수여부

    @Column(name = "is_active")
    private boolean active; // 약관 활성여부

    @Column(name = "effective_at")
    private LocalDateTime effectiveAt; // 시행일시

    @Column(name = "created_at")
    private LocalDateTime createdAt; // 등록일시

    public Consent() {
    }

    public Consent(Long termsId, String termsType, String termsVersion, String termsTitle,
                   String termsContent, boolean required, boolean active, LocalDateTime effectiveAt,
                   LocalDateTime createdAt) {
        this.termsId = termsId;
        this.termsType = termsType;
        this.termsVersion = termsVersion;
        this.termsTitle = termsTitle;
        this.termsContent = termsContent;
        this.required = required;
        this.active = active;
        this.effectiveAt = effectiveAt;
        this.createdAt = createdAt;
    }

    public Long getTermsId() {
        return termsId;
    }

    public void setTermsId(Long memberId) {
        this.termsId = memberId;
    }

    public String getTermsType() {
        return termsType;
    }

    public void setTermsType(String termsType) {
        this.termsType = termsType;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public void setTermsVersion(String termsVersion) {
        this.termsVersion = termsVersion;
    }

    public String getTermsTitle() {
        return termsTitle;
    }

    public void setTermsTitle(String termsTitle) {
        this.termsTitle = termsTitle;
    }

    public String getTermsContent() {
        return termsContent;
    }

    public void setTermsContent(String termsContent) {
        this.termsContent = termsContent;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getEffectiveAt() {
        return effectiveAt;
    }

    public void setEffectiveAt(LocalDateTime effectiveAt) {
        this.effectiveAt = effectiveAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

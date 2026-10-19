package org.semiproject.pillbadeumserver.member.dto;


import org.semiproject.pillbadeumserver.member.domain.Consent;

public class ConsentPolicyResponse {

    private Long termsId; // 약관코드
    private String termsTitle; // 약관제목
    private String termsContent; // 약관내용
    private boolean required; // 필수동의여부

    public ConsentPolicyResponse() {
    }

    public ConsentPolicyResponse(Long termsId, String termsTitle, String termsContent, boolean required) {
        this.termsId = termsId;
        this.termsTitle = termsTitle;
        this.termsContent = termsContent;
        this.required = required;
    }

    // 엔티티를 응답 dto로 변환함
    public static ConsentPolicyResponse from(Consent consent) {
        return new ConsentPolicyResponse(
            consent.getTermsId(),
            consent.getTermsTitle(),
            consent.getTermsContent(),
            consent.isRequired()
        );
    }


    public Long getTermsId() {
        return termsId;
    }

    public void setTermsId(Long termsId) {
        this.termsId = termsId;
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
}


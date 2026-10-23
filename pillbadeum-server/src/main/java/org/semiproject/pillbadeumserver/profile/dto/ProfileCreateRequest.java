package org.semiproject.pillbadeumserver.profile.dto;

import jakarta.validation.constraints.*;

public class ProfileCreateRequest {

    @com.fasterxml.jackson.annotation.JsonIgnore
    @AssertTrue(message = "출생연도는 올해보다 클 수 없습니다.")
    public boolean isValidBirthYear() {
        return org.semiproject.pillbadeumserver.profile.domain.Profile.validBirthYear(profileBirthYear);
    }

    // 입력 받아야 할 것 : 프로필 이름, 관계, 출생연도, 성별, 임신 여부, 키, 몸무게

    @NotBlank(message = "프로필의 이름을 작성해주세요.")
    @Size(max = 10, message = "프로필 이름은 10자 이하로 작성해주세요.")
    private String profileName; // 프로필 이름

    @NotBlank(message = "관계를 선택해주세요.")
    @Pattern(
        regexp = "SELF|MOTHER|FATHER|DAUGHTER|SON|GRANDMA|GRANDPA|SPOUSE",
        message = "올바른 관계를 선택해주세요."
    )
    private String relationshipType; // 회원과 프로필과의 관계

    @NotNull(message = "출생연도를 입력해주세요.")
    private Integer profileBirthYear; // 출생연도

    @NotBlank(message = "성별을 선택해주세요.")
    @Pattern(
        regexp = "MALE|FEMALE|UNKNOWN",
        message = "올바른 성별을 선택해주세요."
    )
    private String profileGender; // 성별

    @Positive(message = "키를 0보다 큰 값으로 입력해주세요.")
    private Integer profileHeight; // 키, 선택 입력

    @Positive(message = "몸무게를 0보다 큰 값으로 입력해주세요.")
    private Integer profileWeight; // 몸무게, 선택 입력

    @NotNull(message = "임신 여부를 선택해주세요.")
    private Boolean profilePregnant; // 임신 여부



    public String getProfileName() {
        return profileName;
    }

    public void setProfileName(String profileName) {
        this.profileName = profileName;
    }

    public String getRelationshipType() {
        return relationshipType;
    }

    public void setRelationshipType(String relationshipType) {
        this.relationshipType = relationshipType;
    }

    public Integer getProfileBirthYear() {
        return profileBirthYear;
    }

    public void setProfileBirthYear(Integer profileBirthYear) {
        this.profileBirthYear = profileBirthYear;
    }

    public String getProfileGender() {
        return profileGender;
    }

    public void setProfileGender(String profileGender) {
        this.profileGender = profileGender;
    }

    public Integer getProfileHeight() {
        return profileHeight;
    }

    public void setProfileHeight(Integer profileHeight) {
        this.profileHeight = profileHeight;
    }

    public Integer getProfileWeight() {
        return profileWeight;
    }

    public void setProfileWeight(Integer profileWeight) {
        this.profileWeight = profileWeight;
    }

    public Boolean getProfilePregnant() {
        return profilePregnant;
    }

    public void setProfilePregnant(Boolean profilePregnant) {
        this.profilePregnant = profilePregnant;
    }
}

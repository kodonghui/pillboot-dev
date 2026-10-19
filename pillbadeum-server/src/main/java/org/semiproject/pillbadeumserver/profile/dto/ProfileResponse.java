package org.semiproject.pillbadeumserver.profile.dto;

import org.semiproject.pillbadeumserver.profile.domain.Profile;

public class ProfileResponse {

    private final Long profileId;
    private final String profileName;
    private final String relationshipType;
    private final int profileBirthYear;
    private final String profileGender;
    private final Integer profileHeight;
    private final Integer profileWeight;
    private final boolean profilePregnant;

    public ProfileResponse(Long profileId, String profileName, String relationshipType,
                           int profileBirthYear, String profileGender, Integer profileHeight,
                           Integer profileWeight, boolean profilePregnant) {
        this.profileId = profileId;
        this.profileName = profileName;
        this.relationshipType = relationshipType;
        this.profileBirthYear = profileBirthYear;
        this.profileGender = profileGender;
        this.profileHeight = profileHeight;
        this.profileWeight = profileWeight;
        this.profilePregnant = profilePregnant;
    }

    /* db에서 읽은 프로필 객체를 ProfileResponse에 담아 화면에 보낼 데이터로 바꾼다. */
    public static ProfileResponse from(Profile profile) {
       return new ProfileResponse(
        profile.getProfileId(),
        profile.getProfileName(),
        profile.getRelationshipType(),
        profile.getProfileBirthYear(),
        profile.getProfileGender(),
        profile.getProfileHeight(),
        profile.getProfileWeight(),
        profile.isProfilePregnant()
       );
    }

    public Long getProfileId() {
        return profileId;
    }

    public String getProfileName() {
        return profileName;
    }

    public String getRelationshipType() {
        return relationshipType;
    }

    public int getProfileBirthYear() {
        return profileBirthYear;
    }

    public String getProfileGender() {
        return profileGender;
    }

    public Integer getProfileHeight() {
        return profileHeight;
    }

    public Integer getProfileWeight() {
        return profileWeight;
    }

    public boolean isProfilePregnant() {
        return profilePregnant;
    }
}

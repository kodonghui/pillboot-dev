package org.semiproject.pillbadeumserver.profile.domain;

import jakarta.persistence.*;
import org.semiproject.pillbadeumserver.member.domain.Member;

import java.time.LocalDateTime;

@Entity
@Table(name = "profile")
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_id")
    private Long profileId;

    // 하나의 계정이 여러 개의 프로필을 가진다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "profile_name")
    private String profileName;

    @Column(name = "realationship_type")
    private String relationshipType;

    @Column(name = "profile_birth_year")
    private int profileBirthYear;

    @Column(name = "profile_gender")
    private String profileGender; // 프로필 성별 (MALE(남성), FEMALE(여성), UNKNOWN(모름))

    @Column(name="profile_height")
    private Integer profileHeight;

    @Column(name = "profile_weight")
    private Integer profileWeight;

    @Column(name = "profile_pregnant")
    private boolean profilePregnant;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;


    public Profile() {
    }

    public Profile(Member member, String profileName, String relationshipType,
                   int profileBirthYear, String profileGender, Integer profileHeight,
                   Integer profileWeight, boolean profilePregnant) {
        this.member = member;
        this.profileName = profileName;
        this.relationshipType = relationshipType;
        this.profileBirthYear = profileBirthYear;
        this.profileGender = profileGender;
        this.profileHeight = profileHeight;
        this.profileWeight = profileWeight;
        this.profilePregnant = profilePregnant;

        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }


    /* 프로필 정보 수정과 수정일시 갱신 */
    public void updateProfile(String profileName, String relationshipType, int profileBirthYear,
                              String profileGender, Integer profileHeight,
                              Integer profileWeight, boolean profilePregnant) {
        this.profileName = profileName;
        this.relationshipType = relationshipType;
        this.profileBirthYear = profileBirthYear;
        this.profileGender = profileGender;
        this.profileHeight = profileHeight;
        this.profileWeight = profileWeight;
        this.profilePregnant = profilePregnant;
        this.updatedAt = LocalDateTime.now();;
    }

    public Long getProfileId() {
        return profileId;
    }

    public void setProfileId(Long profileId) {
        this.profileId = profileId;
    }

    public Member getMember() {
        return member;
    }

    public void setMember(Member member) {
        this.member = member;
    }

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

    public int getProfileBirthYear() {
        return profileBirthYear;
    }

    public void setProfileBirthYear(int profileBirthYear) {
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

    public boolean isProfilePregnant() {
        return profilePregnant;
    }

    public void setProfilePregnant(boolean profilePregnant) {
        this.profilePregnant = profilePregnant;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

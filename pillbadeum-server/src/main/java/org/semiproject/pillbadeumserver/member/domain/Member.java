package org.semiproject.pillbadeumserver.member.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "member")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long memberId; // 자동으로 증가하는 회원 코드. 우리는 로그인할 떄 id 대신 email을 쓴다. 헷갈리지 않기.

    @Column(name = "email")
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "nickname")
    private String nickname;

    @Column(name = "user_role")
    private String userRole;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;


    public Member() {
    }

    public Member(String email, String passwordHash,
                  String nickname, String userRole) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.userRole = userRole;

        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now; // 일단 가입일시를 초기값으로 넣어둔다.
    }

    public Long getMemberId() {
        return memberId;
    }

    @Override
    public String toString() {
        return "Member{" +
            "memberId=" + memberId +
            ", email='" + email + '\'' +
            ", nickname='" + nickname + '\'' +
            ", userRole='" + userRole + '\'' +
            ", createdAt=" + createdAt +
            ", updatedAt=" + updatedAt +
            '}';
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getUserRole() {
        return userRole;
    }

    public void setUserRole(String userRole) {
        this.userRole = userRole;
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

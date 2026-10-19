package org.semiproject.pillbadeumserver.member.dto;

import org.semiproject.pillbadeumserver.member.domain.Member;

public class MemberResponse {

    private Long memberId; // 회원코드(pk값)
    private String email; // 이걸로 로그인
    private String nickname;
    private String userRole;

    public MemberResponse() {
    }

    public MemberResponse(Long memberId, String email, String nickname, String userRole) {
        this.memberId = memberId;
        this.email = email;
        this.nickname = nickname;
        this.userRole = userRole;
    }

    /* Member 엔티티에서 응답에 필요한 정보만 골라 담는다. */
    public static MemberResponse from (Member member) {
        return new MemberResponse(
            member.getMemberId(),
            member.getEmail(),
            member.getNickname(),
            member.getUserRole()
        );
    }

    public Long getMemberId() {
        return memberId;
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
}

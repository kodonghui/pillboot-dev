package org.semiproject.pillbadeumserver.member.dto;


/* 로그인 성공 시 access 토큰과 회원 정보를 전달 */
public class LoginResponse {
    private final String accessToken;
    private final MemberResponse member;

    public LoginResponse(String accessToken, MemberResponse member) {
        this.accessToken = accessToken;
        this.member = member;
    }

    public MemberResponse getMember() {
        return member;
    }

    public String getAccessToken() {
        return accessToken;
    }
}

package org.semiproject.pillbadeumserver.common.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/* 설명. Spring Security 가 이해하는 '인증된 사용자' 객체
 *  ----------------------------------------------------------------------------------
 *  JwtAuthenticationFilter 가 요청마다 토큰 안의 값으로 만든다. (DB 조회 없음, 비밀번호 없음)
 *  컨트롤러에서는 두 가지 방법으로 꺼낸다.
 *  1) @AuthenticationPrincipal CustomUserDetails user  -> user.getMemberId()
 *  2) SecurityUtil.getCurrentMemberId()                -> 서비스 계층에서 바로 꺼낼 때
 *  ----------------------------------------------------------------------------------
 *  member 패키지의 Member 엔티티에 의존하지 않게 만들었다.
 *  그래서 회원 담당이 Member 를 만들기 전에도 다른 팀원이 로그인 사용자 정보를 쓸 수 있다.
 * */
public class CustomUserDetails implements UserDetails {

    // 회원 PK (토큰의 sub). "누구의 요청인지" 알아낼 때 쓴다.
    private final Long memberId;
    // 로그인 아이디 (이메일 등. Spring Security 가 말하는 username)
    private final String loginId;
    // 권한 ('ROLE_USER' 또는 'ROLE_ADMIN')
    private final String role;

    public CustomUserDetails(Long memberId, String loginId, String role) {
        this.memberId = memberId;
        this.loginId = loginId;
        this.role = role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role));
    }

    // 토큰으로 만든 객체이므로 비밀번호는 없다. (로그인 시 비밀번호 비교는 MemberService 에서 PasswordEncoder 로 한다)
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return loginId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getLoginId() {
        return loginId;
    }

    public String getRole() {
        return role;
    }

    @Override
    public String toString() {
        return "CustomUserDetails{memberId=" + memberId + ", loginId='" + loginId + "', role='" + role + "'}";
    }
}

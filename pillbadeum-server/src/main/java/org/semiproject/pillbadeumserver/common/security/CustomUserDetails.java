package org.semiproject.pillbadeumserver.common.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/* 설명. Spring Security 가 이해하는 '인증된 사용자' 객체
 *  ----------------------------------------------------------------------------------
 *  로그인할 때는 DB 회원 정보로 만든다. (비밀번호 해시 포함)
 *  로그인 후 요청에서는 JwtAuthenticationFilter가 토큰 정보로 만든다. (비밀번호 해시 없음)
 *  1) @AuthenticationPrincipal CustomUserDetails user  -> user.getMemberId()
 *  2) SecurityUtil.getCurrentMemberId()                -> 서비스 계층에서 바로 꺼낼 때
 *  ----------------------------------------------------------------------------------
 *  member 패키지의 Member 엔티티에 의존하지 않게 만들었다.
 *  그래서 회원 담당이 Member 를 만들기 전에도 다른 팀원이 로그인 사용자 정보를 쓸 수 있다.
 * */
public class CustomUserDetails implements UserDetails {

    // 회원 PK (토큰의 sub). "누구의 요청인지" 알아낼 때  사용
    private final Long memberId;

    // 로그인 아이디 (Spring Security 가 말하는 username. 우리는 회원의 이메일을 넣음)
    private final String loginId;

    // 암호화 된 비밀번호 해시
    private final String passwordHash;

    // 권한 ('ROLE_USER' 또는 'ROLE_ADMIN')
    private final String role;


    /* 로그인 후 토큰을 확인할 때 사용한다. 이때는 비밀번호 해시가 필요 없다.  */
    public CustomUserDetails(Long memberId, String loginId, String role) {
        this(memberId, loginId, null, role);
    }

    /* 처음 로그인할 때 사용한다. 비밀번호 검사를 위해 DB의 비밀번호 해시를 담는다. */
    public CustomUserDetails(Long memberId, String loginId, String passwordHash, String role) {
        this.memberId = memberId;
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role));
    }

    /* 로그인용이면 비밀번호 해시를, 토큰 확인용이면 null을 반환한다. */
    @Override
    public String getPassword() {
        return passwordHash;
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

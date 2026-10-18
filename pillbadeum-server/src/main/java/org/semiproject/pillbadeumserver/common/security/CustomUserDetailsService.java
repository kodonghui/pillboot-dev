package org.semiproject.pillbadeumserver.common.security;

import org.semiproject.pillbadeumserver.member.domain.Member;
import org.semiproject.pillbadeumserver.member.domain.MemberRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    @Autowired
    public CustomUserDetailsService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /* 우리 프로젝트에서는 username에 email 사용 */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Member member = memberRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 회원입니다."));

        return new CustomUserDetails(
            member.getMemberId(),
            member.getEmail(),
            member.getPasswordHash(),
            member.getUserRole()
        );
    }

}

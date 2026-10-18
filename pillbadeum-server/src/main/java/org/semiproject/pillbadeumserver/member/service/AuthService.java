package org.semiproject.pillbadeumserver.member.service;

import org.semiproject.pillbadeumserver.member.domain.MemberRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final MemberRepository memberRepository;

    @Autowired
    public AuthService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /* 가입된 이메일이 없으면 true(사용가능), 이미 있는 상태면 false(사용불가)를 반환한다.  */
    public boolean isEmailAvailable(String email) {
        return !memberRepository.existsByEmail(email);
    }
}

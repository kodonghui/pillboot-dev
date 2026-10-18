package org.semiproject.pillbadeumserver.member.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.member.domain.*;
import org.semiproject.pillbadeumserver.member.dto.SignupRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class AuthService {

    private final MemberRepository memberRepository;
    private final ConsentRepository consentRepository;
    private final ConsentHistoryRepository consentHistoryRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public AuthService(MemberRepository memberRepository, ConsentRepository consentRepository,
                       ConsentHistoryRepository consentHistoryRepository, PasswordEncoder passwordEncoder) {
        this.memberRepository = memberRepository;
        this.consentRepository = consentRepository;
        this.consentHistoryRepository = consentHistoryRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /* 가입된 이메일이 없으면 true(사용가능), 이미 있는 상태면 false(사용불가)를 반환한다.  */
    @Transactional(readOnly = true)
    public boolean isEmailAvailable(String email) {
        return !memberRepository.existsByEmail(email);
    }


    /* 회원가입 */
    @Transactional
    public void signup(SignupRequest  signupRequest) {

        /*  이메일 중복 검사 */
        if(memberRepository.existsByEmail(signupRequest.getEmail())) {
            throw new BusinessException(ErrorCode.DUPLICATE, "이미 가입된 이메일입니다.");
        }

        /* 회원 가입시 동의할 수 있는 약관의 기준 목록 */
        List<Consent> currentConsents =
            consentRepository.findByActiveTrueAndEffectiveAtLessThanEqualOrderByTermsIdAsc(
                LocalDateTime.now()
            );

        /* 사용자가 동의했다고 보낸 약관 ID를 Set에 담는다. (중복 제거) */
        Set<Long> agreedTermsIds = new HashSet<>(signupRequest.getAgreedTermsIds());
        /* db에서 조회한 유효한 약관코드(terms_id)를 담을 빈 set을 만든다. */
        Set<Long> validTermsIds = new HashSet<>();

        /* 현재 동의할 수 있는 약관의 약관코드(terms_id)를 set에 담는다. */
        for (Consent consent : currentConsents) {
            validTermsIds.add(consent.getTermsId());
        }

        /* 현재 동의할 수 없는 약관 ID를 보냈다면 예외 처리. */
        if (!validTermsIds.containsAll(agreedTermsIds)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "유효하지 않은 약관이 포함되어 있습니다.");
        }

        /* 필수 약관을 모두 선택했는지 확인한다. */
        for (Consent consent : currentConsents) {
            if (consent.isRequired() && !agreedTermsIds.contains(consent.getTermsId())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "필수 약관에 모두 동의해주세요.");
            }
        }

        /* 비밀번호는 BCrypt로 암호화한다. */
        String encodedPassword = passwordEncoder.encode(signupRequest.getPassword());

        /* 신규 회원은 일반 사용자 권한으로 저장한다. */
        Member member = new Member(
            signupRequest.getEmail(),
            encodedPassword,
            signupRequest.getNickname(),
            "ROLE_USER"
        );

        Member savedMember = memberRepository.save(member);

        /* 사용자가 동의한 약관만 저장된 회원과 연결해 동의 이력으로 남긴다. */
        for (Consent consent : currentConsents) {
            if (agreedTermsIds.contains(consent.getTermsId())) {
                ConsentHistory history = new ConsentHistory(savedMember, consent, "AGREED");
                consentHistoryRepository.save(history);
            }
        }
    }


}

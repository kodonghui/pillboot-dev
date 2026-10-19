package org.semiproject.pillbadeumserver.member.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.CustomUserDetails;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.member.domain.*;
import org.semiproject.pillbadeumserver.member.dto.LoginRequest;
import org.semiproject.pillbadeumserver.member.dto.LoginResponse;
import org.semiproject.pillbadeumserver.member.dto.MemberResponse;
import org.semiproject.pillbadeumserver.member.dto.SignupRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
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
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;

    @Autowired
    public AuthService(MemberRepository memberRepository, ConsentRepository consentRepository,
                       ConsentHistoryRepository consentHistoryRepository, PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager, JwtTokenProvider jwtTokenProvider) {
        this.memberRepository = memberRepository;
        this.consentRepository = consentRepository;
        this.consentHistoryRepository = consentHistoryRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
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

    /* 로그인 */
    /* 이메일과 비밀번호를 확인하고, 성공하면 토큰과 회원 정보를 반환한다. */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest loginRequest) {
        Authentication authentication;

        try {
            // 아직 검사되지 않은 이메일·비밀번호를 한 묶음으로 만든다.
            UsernamePasswordAuthenticationToken loginToken =
                UsernamePasswordAuthenticationToken.unauthenticated(
                    loginRequest.getEmail(),
                    loginRequest.getPassword()
                );

            // 회원 조회와 비밀번호 비교를 Spring Security에 맡긴다.
            authentication = authenticationManager.authenticate(loginToken);
        } catch (AuthenticationException e) {
            // 이메일이 없거나 비밀번호가 틀려도 같은 오류를 반환한다.
            throw new BusinessException(
                ErrorCode.LOGIN_FAILED,
                "이메일 또는 비밀번호가 맞지 않습니다."
            );
        }

        CustomUserDetails user = (CustomUserDetails) authentication.getPrincipal();

        String accessToken = jwtTokenProvider.createAccessToken(
            user.getMemberId(),
            user.getLoginId(),
            user.getRole()
        );

        Member member = memberRepository.findById(user.getMemberId())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.LOGIN_FAILED,
                "이메일 또는 비밀번호가 맞지 않습니다."
            ));

        return new LoginResponse(accessToken, MemberResponse.from(member));
    }

    /* 현재 로그인한 회원의 정보 조회 */
    @Transactional(readOnly = true)
    public MemberResponse getMe() {

        Long memberId = SecurityUtil.getCurrentMemberId(); // 검증된 토큰에서 지금 요청한 회원의 ID를 꺼낸다.

        Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new NotFoundException("해당 회원이 존재하지 않습니다."));

        return MemberResponse.from(member);
    }
}

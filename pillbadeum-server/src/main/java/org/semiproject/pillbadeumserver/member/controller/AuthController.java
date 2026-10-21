package org.semiproject.pillbadeumserver.member.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.semiproject.pillbadeumserver.member.dto.*;
import org.semiproject.pillbadeumserver.member.service.AuthService;
import org.semiproject.pillbadeumserver.member.service.ConsentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "인증", description = "약관 조회·이메일 중복 확인·회원가입·로그인·내 정보 조회")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final ConsentService consentService;
    private final AuthService authService;

    @Autowired
    public AuthController(ConsentService consentService, AuthService authService) {
        this.consentService = consentService;
        this.authService = authService;
    }

    /* 회원 가입 화면에 보여줄 약관 목록 조회 */
    @Operation(summary = "회원 가입 약관 목록 조회")
    @GetMapping("/consent-policies")
    public ResponseEntity<List<ConsentPolicyResponse>> getConsentPolicies() {

        List<ConsentPolicyResponse> policies = consentService.getConsentPolicies();

        return ResponseEntity.ok(policies);
    }

    /* 이메일 사용 가능 여부 확인 */
    @Operation(summary = "이메일 사용 가능 여부 확인")
    @GetMapping("/email-availability")
    public ResponseEntity<Boolean> checkEmailAvailability(@RequestParam String email) {

        boolean available = authService.isEmailAvailable(email);

        return ResponseEntity.ok(available);
    }

    /* 회원가입 요청을 검증한 뒤 서비스에 전달하고, 성공하면 201 created 반환 */
    @Operation(summary = "회원가입")
    @ApiResponse(responseCode = "201", description = "회원가입 완료")
    @PostMapping("/signup")
    public ResponseEntity<Void> signup (@Valid @RequestBody SignupRequest signupRequest) {
        authService.signup(signupRequest);

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /* 로그인 요청을 서비스에 전달하고, 성공하면 토큰과 회원 정보를 반환 */
    @Operation(summary = "로그인")
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        LoginResponse response = authService.login(loginRequest);
        return ResponseEntity.ok(response);
    }

    /* 로그인 한 회원의 정보를 반환. 토큰에서 회원을 식별하기 때문에  request body는 없다. */
    @Operation(summary = "내 회원 정보 조회")
    @GetMapping("/me")
    public ResponseEntity<MemberResponse> getMe() {
        MemberResponse member = authService.getMe();

        return ResponseEntity.ok(member);

    }
}

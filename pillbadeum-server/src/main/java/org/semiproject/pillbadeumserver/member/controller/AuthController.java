package org.semiproject.pillbadeumserver.member.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.semiproject.pillbadeumserver.member.dto.ConsentPolicyResponse;
import org.semiproject.pillbadeumserver.member.service.ConsentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "인증", description= "약관 조회 및 동의, 회원가입, 로그인 및 로그아웃")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final ConsentService consentService;

    @Autowired
    public AuthController(ConsentService consentService) {
        this.consentService = consentService;
    }

    /* 회원 가입 화면에 보여줄 약관 목록 조회 */
    @GetMapping("/consent-policies")
    public ResponseEntity<List<ConsentPolicyResponse>> getConsentPolicies() {
        List<ConsentPolicyResponse> policies = consentService.getConsentPolicies();
        return ResponseEntity.ok(policies);
    }

}

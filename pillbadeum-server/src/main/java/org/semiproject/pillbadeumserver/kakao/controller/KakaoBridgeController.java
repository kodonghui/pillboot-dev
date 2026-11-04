package org.semiproject.pillbadeumserver.kakao.controller;

import org.semiproject.pillbadeumserver.common.security.KakaoBridgePrincipal;
import org.semiproject.pillbadeumserver.kakao.dto.KakaoLinkDtos.KakaoBridgeLinkRequest;
import org.semiproject.pillbadeumserver.kakao.dto.KakaoLinkDtos.KakaoBridgeLinkResponse;
import org.semiproject.pillbadeumserver.kakao.dto.KakaoLinkDtos.KakaoBridgeProfileRequest;
import org.semiproject.pillbadeumserver.kakao.service.KakaoLinkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V3-KAKAO-013~015: 카카오 다리 전용(다리 키 + 카카오 사용자 HMAC, KakaoBridgeAuthFilter). 공개 주소에서는 Caddy가 막는다.
 * 사람은 다리가 보낸 HMAC으로 가리키므로 경로에 사람 번호가 없다.
 */
@RestController
@RequestMapping("/api/kakao-bridge/links")
public class KakaoBridgeController {

    private final KakaoLinkService links;

    public KakaoBridgeController(KakaoLinkService links) {
        this.links = links;
    }

    @PostMapping
    public ResponseEntity<KakaoBridgeLinkResponse> link(@AuthenticationPrincipal KakaoBridgePrincipal bridge,
                                                        @RequestBody KakaoBridgeLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(links.link(bridge.kakaoUserHmac(), request.code()));
    }

    @GetMapping("/current")
    public KakaoBridgeLinkResponse current(@AuthenticationPrincipal KakaoBridgePrincipal bridge) {
        return links.current(bridge.kakaoUserHmac());
    }

    @PutMapping("/current/profile")
    public KakaoBridgeLinkResponse selectProfile(@AuthenticationPrincipal KakaoBridgePrincipal bridge,
                                                 @RequestBody KakaoBridgeProfileRequest request) {
        return links.selectProfile(bridge.kakaoUserHmac(), request.profileId());
    }

    @DeleteMapping("/current")
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal KakaoBridgePrincipal bridge) {
        links.unlink(bridge.kakaoUserHmac());
        return ResponseEntity.noContent().build();
    }
}

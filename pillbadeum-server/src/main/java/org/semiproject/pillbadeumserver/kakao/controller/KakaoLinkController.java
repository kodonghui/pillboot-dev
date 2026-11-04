package org.semiproject.pillbadeumserver.kakao.controller;

import jakarta.validation.constraints.Positive;
import java.util.List;
import org.semiproject.pillbadeumserver.kakao.dto.KakaoLinkDtos.KakaoLinkCodeRequest;
import org.semiproject.pillbadeumserver.kakao.dto.KakaoLinkDtos.KakaoLinkCodeResponse;
import org.semiproject.pillbadeumserver.kakao.dto.KakaoLinkDtos.KakaoLinkResponse;
import org.semiproject.pillbadeumserver.kakao.service.KakaoLinkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** V3-KAKAO-012·015: 로그인 회원이 웹(PRF-01·PRF-04)에서 카카오톡 연결을 보고, 코드를 받고, 끊는다. */
@Validated
@RestController
public class KakaoLinkController {

    private final KakaoLinkService links;

    public KakaoLinkController(KakaoLinkService links) {
        this.links = links;
    }

    @GetMapping("/api/kakao-links")
    public List<KakaoLinkResponse> list() {
        return links.list();
    }

    @PostMapping("/api/kakao-link-codes")
    public ResponseEntity<KakaoLinkCodeResponse> createCode(@RequestBody KakaoLinkCodeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(links.createCode(request.agreed()));
    }

    @DeleteMapping("/api/kakao-links/{linkId}")
    public ResponseEntity<Void> delete(@PathVariable @Positive Long linkId) {
        links.delete(linkId);
        return ResponseEntity.noContent().build();
    }
}

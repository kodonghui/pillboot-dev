package org.semiproject.pillbadeumserver.kakao.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** V3-KAKAO-012~016 요청·응답 모양. 칸 이름은 docs/06-api/openapi-v3.yaml과 같다. */
public final class KakaoLinkDtos {

    private KakaoLinkDtos() {
    }

    /** 회원이 보는 연결 한 줄(GET /api/kakao-links). 카카오 사용자 키는 주지 않는다. */
    public record KakaoLinkResponse(Long linkId, Long currentProfileId, String currentProfileName,
                                    OffsetDateTime linkedAt, OffsetDateTime lastUsedAt) {
    }

    public record KakaoLinkCodeRequest(Boolean agreed) {
    }

    public record KakaoLinkCodeResponse(String code, OffsetDateTime expiresAt) {
    }

    public record KakaoBridgeLinkRequest(String code) {
    }

    public record KakaoBridgeProfileRequest(Long profileId) {
    }

    public record KakaoBridgeProfile(Long profileId, String profileName, String relationshipType) {
    }

    /** 다리가 받는 연결(9.2 Q4 B안: 그 회원의 프로필 전부를 고를 수 있음). */
    public record KakaoBridgeLinkResponse(Long linkId, Long currentProfileId, List<KakaoBridgeProfile> profiles) {
    }
}

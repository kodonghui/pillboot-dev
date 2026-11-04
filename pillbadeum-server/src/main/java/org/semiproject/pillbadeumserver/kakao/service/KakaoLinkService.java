package org.semiproject.pillbadeumserver.kakao.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.kakao.domain.KakaoLinkStore;
import org.semiproject.pillbadeumserver.kakao.domain.KakaoLinkStore.Link;
import org.semiproject.pillbadeumserver.kakao.dto.KakaoLinkDtos.KakaoBridgeLinkResponse;
import org.semiproject.pillbadeumserver.kakao.dto.KakaoLinkDtos.KakaoBridgeProfile;
import org.semiproject.pillbadeumserver.kakao.dto.KakaoLinkDtos.KakaoLinkCodeResponse;
import org.semiproject.pillbadeumserver.kakao.dto.KakaoLinkDtos.KakaoLinkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V3-KAKAO-012~016(DEC-029): 웹에서 받은 연결 코드로 카카오톡 사용자를 회원 계정에 잇고 끊는다.
 * 연결은 계정 단위이며 그 회원의 모든 프로필 가운데 지금 보는 하나를 가리킨다(9.2 Q4 B안).
 * 로그에는 연결 번호·회원 번호만 남기고 코드·카카오 키·HMAC은 남기지 않는다.
 */
@Service
public class KakaoLinkService {

    private static final Logger log = LoggerFactory.getLogger(KakaoLinkService.class);

    /** 연결 안내(PRF-04) 판 번호. 안내 글을 바꾸면 올린다. */
    public static final String NOTICE_VERSION = "KAKAO-LINK-2";
    static final int MAX_LINKS = 5;
    static final int MAX_CODES_PER_WINDOW = 5;
    static final long CODE_MINUTES = 10;

    private final KakaoLinkStore store;
    private final KakaoLinkAttempts attempts;

    public KakaoLinkService(KakaoLinkStore store, KakaoLinkAttempts attempts) {
        this.store = store;
        this.attempts = attempts;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(TimeZoneConfig.ZONE).truncatedTo(ChronoUnit.SECONDS);
    }

    // ── 회원(웹) ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<KakaoLinkResponse> list() {
        Long memberId = SecurityUtil.getCurrentMemberId();
        return store.findByMember(memberId).stream()
                .map(link -> new KakaoLinkResponse(link.linkId(), link.currentProfileId(),
                        link.currentProfileName(), offset(link.linkedAt()), offset(link.lastUsedAt())))
                .toList();
    }

    @Transactional
    public KakaoLinkCodeResponse createCode(Boolean agreed) {
        if (!Boolean.TRUE.equals(agreed)) {
            throw new BusinessException(ErrorCode.KAKAO_LINK_NOT_AGREED, ErrorCode.KAKAO_LINK_NOT_AGREED.getDescription());
        }
        Long memberId = SecurityUtil.getCurrentMemberId();
        // 같은 회원의 코드 받기·연결 만들기를 회원 행 잠금으로 차례로 처리해 개수 검사가 최신 값을 보게 함.
        store.lockMember(memberId).orElseThrow(() -> new NotFoundException("회원을 찾을 수 없습니다."));
        if (store.countLinks(memberId) >= MAX_LINKS) {
            throw new BusinessException(ErrorCode.KAKAO_LINK_LIMIT, ErrorCode.KAKAO_LINK_LIMIT.getDescription());
        }
        LocalDateTime now = now();
        if (store.countCodesSince(memberId, now.minusMinutes(CODE_MINUTES)) >= MAX_CODES_PER_WINDOW) {
            throw new BusinessException(ErrorCode.KAKAO_LINK_CODE_RATE_LIMITED,
                    ErrorCode.KAKAO_LINK_CODE_RATE_LIMITED.getDescription());
        }
        store.revokeOpenCodes(memberId, now);
        LocalDateTime expiresAt = now.plusMinutes(CODE_MINUTES);
        for (int attempt = 0; attempt < 3; attempt++) {
            String code = KakaoLinkCodes.generate();
            try {
                store.insertCode(memberId, KakaoLinkCodes.hash(code), NOTICE_VERSION, now, now, expiresAt);
                log.info("[카카오 연결] 코드 만듦 member={}", memberId);
                return new KakaoLinkCodeResponse(KakaoLinkCodes.display(code), offset(expiresAt));
            } catch (DuplicateKeyException e) {
                // 약 1조 가지 가운데 같은 해시가 이미 있음. 새로 뽑음.
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDescription());
    }

    @Transactional
    public void delete(Long linkId) {
        Long memberId = SecurityUtil.getCurrentMemberId();
        if (store.deleteByMember(linkId, memberId) == 0) {
            throw notLinked();
        }
        log.info("[카카오 연결] 웹에서 끊음 link={} member={}", linkId, memberId);
    }

    // ── 다리 ───────────────────────────────────────────────

    /** 다리 인증 필터가 부른다. 연결이 있으면 마지막 사용 시각을 남긴다. */
    @Transactional
    public Optional<Link> authenticate(String kakaoUserHmac) {
        Optional<Link> link = store.findByUser(kakaoUserHmac);
        link.ifPresent(found -> store.touch(found.linkId(), now()));
        return link;
    }

    @Transactional
    public KakaoBridgeLinkResponse link(String kakaoUserHmac, String received) {
        switch (attempts.admit(kakaoUserHmac)) {
            case LOCKED -> throw new BusinessException(ErrorCode.KAKAO_LINK_LOCKED,
                    ErrorCode.KAKAO_LINK_LOCKED.getDescription());
            case BUSY -> throw new BusinessException(ErrorCode.KAKAO_LINK_BUSY,
                    ErrorCode.KAKAO_LINK_BUSY.getDescription());
            case OK -> { }
        }
        Optional<String> code = KakaoLinkCodes.normalize(received);
        if (code.isEmpty()) {
            attempts.wrong(kakaoUserHmac);
            throw new BusinessException(ErrorCode.INVALID_INPUT, "연결 코드 모양이 아닙니다.");
        }
        if (store.findByUser(kakaoUserHmac).isPresent()) {
            throw new BusinessException(ErrorCode.KAKAO_ALREADY_LINKED, ErrorCode.KAKAO_ALREADY_LINKED.getDescription());
        }
        LocalDateTime now = now();
        String hash = KakaoLinkCodes.hash(code.get());
        // 코드 받기(회원 잠금 → 코드 행)와 같은 순서로 잠가 교착을 피함: 회원 번호만 먼저 읽고 회원 → 코드 순서로 잠금.
        Optional<Long> owner = store.findUsableCodeMember(hash, now);
        Optional<KakaoLinkStore.UsableCode> usable = owner.flatMap(memberId -> store.lockMember(memberId))
                .flatMap(memberId -> store.lockUsableCode(hash, now));
        if (usable.isEmpty()) {
            attempts.wrong(kakaoUserHmac);
            throw new BusinessException(ErrorCode.KAKAO_LINK_CODE_NOT_FOUND,
                    ErrorCode.KAKAO_LINK_CODE_NOT_FOUND.getDescription());
        }
        Long memberId = usable.get().memberId();
        if (store.countLinks(memberId) >= MAX_LINKS) {
            throw new BusinessException(ErrorCode.KAKAO_LINK_LIMIT, ErrorCode.KAKAO_LINK_LIMIT.getDescription());
        }
        var profiles = store.profiles(memberId);
        Long current = profiles.size() == 1 ? profiles.getFirst().profileId() : null;
        Long linkId;
        try {
            linkId = store.insertLink(memberId, kakaoUserHmac, current, usable.get().noticeVersion(),
                    usable.get().agreedAt(), now);
        } catch (DuplicateKeyException e) {
            // 같은 카카오 사용자가 동시에 코드 두 개를 보냄. 이 트랜잭션은 되돌리고 코드는 쓰지 않은 채 둠.
            throw new BusinessException(ErrorCode.KAKAO_ALREADY_LINKED, ErrorCode.KAKAO_ALREADY_LINKED.getDescription());
        }
        store.markCodeUsed(usable.get().codeId(), now);
        attempts.succeeded(kakaoUserHmac);
        log.info("[카카오 연결] 카톡에서 연결 link={} member={} profiles={}", linkId, memberId, profiles.size());
        return response(linkId, current, profiles);
    }

    @Transactional
    public KakaoBridgeLinkResponse current(String kakaoUserHmac) {
        Link link = store.findByUser(kakaoUserHmac).orElseThrow(KakaoLinkService::notLinked);
        store.touch(link.linkId(), now());
        return response(link.linkId(), link.currentProfileId(), store.profiles(link.memberId()));
    }

    @Transactional
    public KakaoBridgeLinkResponse selectProfile(String kakaoUserHmac, Long profileId) {
        Link link = store.findByUser(kakaoUserHmac).orElseThrow(KakaoLinkService::notLinked);
        var profiles = store.profiles(link.memberId());
        if (profileId == null || profiles.stream().noneMatch(p -> p.profileId().equals(profileId))) {
            throw new NotFoundException("프로필을 찾을 수 없습니다.");
        }
        store.updateCurrentProfile(link.linkId(), profileId);
        store.touch(link.linkId(), now());
        return response(link.linkId(), profileId, profiles);
    }

    @Transactional
    public void unlink(String kakaoUserHmac) {
        if (store.deleteByUser(kakaoUserHmac) == 0) {
            throw notLinked();
        }
        log.info("[카카오 연결] 카톡에서 끊음");
    }

    private static KakaoBridgeLinkResponse response(Long linkId, Long current, List<KakaoLinkStore.ProfileRow> profiles) {
        boolean stillThere = current != null && profiles.stream().anyMatch(p -> p.profileId().equals(current));
        return new KakaoBridgeLinkResponse(linkId, stillThere ? current : null, profiles.stream()
                .map(p -> new KakaoBridgeProfile(p.profileId(), p.profileName(), p.relationshipType()))
                .toList());
    }

    private static BusinessException notLinked() {
        return new BusinessException(ErrorCode.KAKAO_LINK_NOT_FOUND, ErrorCode.KAKAO_LINK_NOT_FOUND.getDescription());
    }

    private static java.time.OffsetDateTime offset(LocalDateTime time) {
        return time == null ? null : time.atZone(TimeZoneConfig.ZONE).toOffsetDateTime();
    }
}

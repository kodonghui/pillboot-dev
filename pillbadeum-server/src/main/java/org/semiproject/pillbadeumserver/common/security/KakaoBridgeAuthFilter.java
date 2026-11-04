package org.semiproject.pillbadeumserver.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.semiproject.pillbadeumserver.common.ErrorResponse;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.kakao.service.KakaoLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * V3-KAKAO-014(DEC-029): 카카오 다리 요청을 확인한다. 머리글 {@code X-Bridge-Key}가 없으면 아무것도 하지 않는다(보통 웹 요청).
 * <ul>
 *   <li>다리 키는 설정의 SHA-256(두 개까지)과 비교하고, {@code X-Kakao-User}는 소문자 16진 64자 HMAC이어야 한다.
 *       {@code Authorization}을 함께 보내면 받지 않는다. 어긋나면 401.</li>
 *   <li>{@code /api/kakao-bridge/**}: 회원 없이 다리로 인증한다(ROLE_KAKAO_BRIDGE).</li>
 *   <li>그 밖: 정한 목록의 API만 받고(아니면 403 KAKAO_BRIDGE_FORBIDDEN), 연결된 회원으로 인증한다(연결 없으면 404
 *       KAKAO_LINK_NOT_FOUND). 프로필이 그 회원의 것인지는 기존 소유권 검사가 그대로 한다.</li>
 * </ul>
 * 다리 키·HMAC은 로그에 남기지 않는다. JwtAuthenticationFilter처럼 SecurityConfig에서 new로 만든다(두 번 등록 방지).
 */
public class KakaoBridgeAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(KakaoBridgeAuthFilter.class);

    public static final String BRIDGE_KEY_HEADER = "X-Bridge-Key";
    public static final String KAKAO_USER_HEADER = "X-Kakao-User";
    /** 다리 인증으로 연결된 회원 요청이면 그 연결 번호가 들어 있다. */
    public static final String LINK_ATTRIBUTE = KakaoBridgeAuthFilter.class.getName() + ".linkId";
    public static final String BRIDGE_PREFIX = "/api/kakao-bridge/";

    private static final Pattern HMAC = Pattern.compile("[0-9a-f]{64}");
    private static final List<Route> ALLOWED = List.of(
            new Route("POST", Pattern.compile("/api/profiles/\\d+/chat-sessions")),
            new Route("POST", Pattern.compile("/api/chat-sessions/\\d+/messages")),
            new Route("POST", Pattern.compile("/api/chat-sessions/\\d+/photos")),
            new Route("POST", Pattern.compile("/api/voice-transcriptions")),
            new Route("GET", Pattern.compile("/api/chat-proposals/\\d+")),
            new Route("POST", Pattern.compile("/api/chat-proposals/\\d+/(?:confirm|cancel)")),
            new Route("GET", Pattern.compile("/api/profiles/\\d+/drugs")));

    private record Route(String method, Pattern path) {
    }

    private final List<byte[]> keyHashes;
    private final ObjectProvider<KakaoLinkService> links;
    private final ObjectMapper objectMapper;

    /**
     * @param keySha256 다리 키 SHA-256 16진값을 쉼표로 이은 것. 비어 있으면 다리 요청을 모두 401로 막는다.
     * @param links     연결 서비스. MVC 조각 시험처럼 서비스가 없는 곳에서도 보안 설정이 뜨도록 늦게 꺼낸다.
     */
    public KakaoBridgeAuthFilter(String keySha256, ObjectProvider<KakaoLinkService> links, ObjectMapper objectMapper) {
        this.keyHashes = Arrays.stream(keySha256 == null ? new String[0] : keySha256.split(","))
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .filter(value -> HMAC.matcher(value).matches())
                .map(HexFormat.of()::parseHex)
                .toList();
        this.links = links;
        this.objectMapper = objectMapper;
    }

    public static boolean isLinkedMemberRequest(HttpServletRequest request) {
        return request.getAttribute(LINK_ATTRIBUTE) != null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(BRIDGE_KEY_HEADER);
        if (key == null) {
            chain.doFilter(request, response);
            return;
        }
        String user = request.getHeader(KAKAO_USER_HEADER);
        KakaoLinkService service = links.getIfAvailable();
        if (service == null || !keyMatches(key) || user == null || !HMAC.matcher(user).matches()
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            log.info("[카카오 다리] 인증 거절 {} {}", request.getMethod(), request.getRequestURI());
            write(response, ErrorCode.UNAUTHORIZED, "다리 인증에 실패했습니다.");
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(BRIDGE_PREFIX)) {
            authenticate(new KakaoBridgePrincipal(user), "ROLE_KAKAO_BRIDGE");
            chain.doFilter(request, response);
            return;
        }
        String method = request.getMethod();
        if (ALLOWED.stream().noneMatch(route -> route.method().equals(method) && route.path().matcher(path).matches())) {
            write(response, ErrorCode.KAKAO_BRIDGE_FORBIDDEN, ErrorCode.KAKAO_BRIDGE_FORBIDDEN.getDescription());
            return;
        }
        var link = service.authenticate(user);
        if (link.isEmpty()) {
            write(response, ErrorCode.KAKAO_LINK_NOT_FOUND, ErrorCode.KAKAO_LINK_NOT_FOUND.getDescription());
            return;
        }
        request.setAttribute(LINK_ATTRIBUTE, link.get().linkId());
        authenticate(new CustomUserDetails(link.get().memberId(), "kakao-link:" + link.get().linkId(), "ROLE_USER"),
                "ROLE_USER");
        chain.doFilter(request, response);
    }

    private boolean keyMatches(String key) {
        byte[] digest = sha256(key);
        boolean matched = false;
        for (byte[] hash : keyHashes) {
            matched |= MessageDigest.isEqual(digest, hash);
        }
        return matched;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private static void authenticate(Object principal, String role) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority(role)));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(new ErrorResponse(code.getCode(), message)));
    }
}

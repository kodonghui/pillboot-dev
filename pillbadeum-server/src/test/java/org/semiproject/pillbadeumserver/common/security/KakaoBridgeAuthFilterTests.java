package org.semiproject.pillbadeumserver.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.kakao.domain.KakaoLinkStore;
import org.semiproject.pillbadeumserver.kakao.service.KakaoLinkService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/**
 * V3-KAKAO-014(DEC-029): 다리 인증 필터. 다리 키가 맞고 카카오 사용자 HMAC 모양이 맞을 때만 들어가며,
 * 다리 전용 API 밖에서는 정한 목록의 API만, 연결된 회원으로만 부른다.
 */
class KakaoBridgeAuthFilterTests {

    private static final String KEY = "pillbadeum-test-only-bridge-key";
    private static final String KEY_SHA256 = "7cf553cff9be18219e6f7c71b7239c77d5836da26f0fd6b2df0ada19a8ca90f7";
    private static final String USER = "c".repeat(64);

    private final KakaoLinkService service = mock(KakaoLinkService.class);
    private final AtomicReference<Authentication> seen = new AtomicReference<>();
    private boolean passed;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requestsWithoutTheBridgeKeyPassUntouched() throws Exception {
        var response = run(filter(KEY_SHA256), request("GET", "/api/profiles"));
        assertThat(passed).isTrue();
        assertThat(seen.get()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void wrongKeyBadUserAuthorizationTogetherOrNoConfiguredKeyIs401() throws Exception {
        var wrongKey = bridge("POST", "/api/kakao-bridge/links");
        wrongKey.removeHeader("X-Bridge-Key");
        wrongKey.addHeader("X-Bridge-Key", KEY + "x");
        var upperUser = bridge("POST", "/api/kakao-bridge/links");
        upperUser.removeHeader("X-Kakao-User");
        upperUser.addHeader("X-Kakao-User", USER.toUpperCase());
        var noUser = bridge("POST", "/api/kakao-bridge/links");
        noUser.removeHeader("X-Kakao-User");
        var withJwt = bridge("GET", "/api/profiles/1/drugs");
        withJwt.addHeader("Authorization", "Bearer something");
        for (var request : new MockHttpServletRequest[]{wrongKey, upperUser, noUser, withJwt}) {
            assertRejected(run(filter(KEY_SHA256), request), 401, "UNAUTHENTICATED");
        }
        assertRejected(run(filter(""), bridge("POST", "/api/kakao-bridge/links")), 401, "UNAUTHENTICATED");
        assertRejected(run(new KakaoBridgeAuthFilter(KEY_SHA256, provider(null), new JsonMapper()),
                bridge("POST", "/api/kakao-bridge/links")), 401, "UNAUTHENTICATED");
        verify(service, never()).authenticate(anyString());
    }

    @Test
    void eitherOfTwoConfiguredKeysWorksForKeyRotation() throws Exception {
        String other = "0".repeat(64);
        run(filter(other + ", " + KEY_SHA256.toUpperCase()), bridge("GET", "/api/kakao-bridge/links/current"));
        assertThat(passed).isTrue();
    }

    @Test
    void bridgePathsAuthenticateTheBridgeWithoutAMember() throws Exception {
        run(filter(KEY_SHA256), bridge("PUT", "/api/kakao-bridge/links/current/profile"));
        assertThat(passed).isTrue();
        assertThat(seen.get().getPrincipal()).isEqualTo(new KakaoBridgePrincipal(USER));
        assertThat(seen.get().getAuthorities()).extracting(Object::toString).containsExactly("ROLE_KAKAO_BRIDGE");
        assertThat(new KakaoBridgePrincipal(USER).toString()).doesNotContain(USER);
        verify(service, never()).authenticate(anyString());
    }

    @Test
    void allowedMemberApisRunAsTheLinkedMember() throws Exception {
        when(service.authenticate(USER)).thenReturn(Optional.of(new KakaoLinkStore.Link(7L, 70L, 700L)));
        for (String[] route : new String[][]{{"POST", "/api/profiles/700/chat-sessions"},
                {"POST", "/api/chat-sessions/5/messages"}, {"POST", "/api/chat-sessions/5/photos"},
                {"POST", "/api/voice-transcriptions"}, {"GET", "/api/chat-proposals/9"},
                {"POST", "/api/chat-proposals/9/confirm"}, {"POST", "/api/chat-proposals/9/cancel"},
                {"GET", "/api/profiles/700/drugs"}}) {
            passed = false;
            var request = bridge(route[0], route[1]);
            run(filter(KEY_SHA256), request);
            assertThat(passed).as(route[0] + " " + route[1]).isTrue();
            var principal = (CustomUserDetails) seen.get().getPrincipal();
            assertThat(principal.getMemberId()).isEqualTo(70L);
            assertThat(seen.get().getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
            assertThat(KakaoBridgeAuthFilter.isLinkedMemberRequest(request)).isTrue();
        }
    }

    @Test
    void otherMemberApisAre403BeforeLookingUpTheLink() throws Exception {
        for (String[] route : new String[][]{{"GET", "/api/profiles"}, {"DELETE", "/api/profiles/700"},
                {"GET", "/api/members/me"}, {"POST", "/api/kakao-link-codes"}, {"GET", "/api/kakao-links"},
                {"DELETE", "/api/kakao-links/7"}, {"GET", "/api/chat-sessions/5/messages"},
                {"POST", "/api/profiles/700/drugs"}, {"POST", "/api/chat-proposals/9/confirm/x"},
                {"GET", "/api/admin/members"}}) {
            passed = false;
            assertRejected(run(filter(KEY_SHA256), bridge(route[0], route[1])), 403, "KAKAO_BRIDGE_FORBIDDEN");
            assertThat(passed).as(route[0] + " " + route[1]).isFalse();
        }
        verify(service, never()).authenticate(anyString());
    }

    @Test
    void anUnlinkedKakaoUserIs404() throws Exception {
        when(service.authenticate(USER)).thenReturn(Optional.empty());
        assertRejected(run(filter(KEY_SHA256), bridge("GET", "/api/profiles/700/drugs")), 404, "KAKAO_LINK_NOT_FOUND");
        assertThat(passed).isFalse();
    }

    private KakaoBridgeAuthFilter filter(String keys) {
        return new KakaoBridgeAuthFilter(keys, provider(service), new JsonMapper());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<KakaoLinkService> provider(KakaoLinkService value) {
        ObjectProvider<KakaoLinkService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static MockHttpServletRequest request(String method, String uri) {
        var request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    private static MockHttpServletRequest bridge(String method, String uri) {
        var request = request(method, uri);
        request.addHeader("X-Bridge-Key", KEY);
        request.addHeader("X-Kakao-User", USER);
        return request;
    }

    private MockHttpServletResponse run(KakaoBridgeAuthFilter filter, MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        seen.set(null);
        filter.doFilter(request, response, (req, res) -> {
            passed = true;
            seen.set(SecurityContextHolder.getContext().getAuthentication());
        });
        return response;
    }

    private static void assertRejected(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentAsString()).contains("\"code\":\"" + code + "\"");
    }
}

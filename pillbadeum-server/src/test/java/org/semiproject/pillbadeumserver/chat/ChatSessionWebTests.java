package org.semiproject.pillbadeumserver.chat;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.service.ChatSessionService;
import org.semiproject.pillbadeumserver.chat.dto.ChatSessionListResponse;
import org.semiproject.pillbadeumserver.chat.dto.ChatSessionResponse;
import org.semiproject.pillbadeumserver.chat.controller.ChatSessionController;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.config.WebConfig;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.security.JwtAccessDeniedHandler;
import org.semiproject.pillbadeumserver.common.security.JwtAuthenticationEntryPoint;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.semiproject.pillbadeumserver.common.security.SecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 보안 필터·Controller 응답을 검사한다. Service는 대역이므로 DB 동작은 MySQL 테스트에서 확인한다. */
@WebMvcTest(controllers = ChatSessionController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, WebConfig.class, GlobalExceptionHandler.class})
class ChatSessionWebTests {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean ChatSessionService sessions;

    @Test
    void listUses200AndOnlyTheSpecifiedFieldsWithKoreanTimeOffset() throws Exception {
        when(sessions.list(10L)).thenReturn(new ChatSessionListResponse(List.of(
                new ChatSessionListResponse.Session(42L, null, OffsetDateTime.parse("2026-10-19T08:00:00+09:00")))));

        mvc.perform(get("/api/profiles/10/chat-sessions").header("Authorization", authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.sessions.length()").value(1))
                .andExpect(jsonPath("$.sessions[0]", aMapWithSize(3)))
                .andExpect(jsonPath("$.sessions[0].sessionId").value(42))
                .andExpect(jsonPath("$.sessions[0].title").value(nullValue()))
                .andExpect(jsonPath("$.sessions[0].updatedAt").value("2026-10-19T08:00:00+09:00"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void listReturnsAnEmptyArray() throws Exception {
        when(sessions.list(10L)).thenReturn(new ChatSessionListResponse(List.of()));
        mvc.perform(get("/api/profiles/10/chat-sessions").header("Authorization", authorization()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"sessions\":[]}"));
    }

    @Test
    void unauthenticatedListStopsBeforeTheService() throws Exception {
        mvc.perform(get("/api/profiles/10/chat-sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(sessions);
    }

    @Test
    void invalidListProfileIdIsRejectedBeforeTheService() throws Exception {
        for (String profileId : new String[]{"0", "-1"}) {
            mvc.perform(get("/api/profiles/{id}/chat-sessions", profileId)
                            .header("Authorization", authorization()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(get("/api/profiles/abc/chat-sessions").header("Authorization", authorization()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(sessions);
    }

    @Test
    void newRoomUses201AndOnlyTheThreeResponseFields() throws Exception {
        when(sessions.createOrReuse(10L)).thenReturn(result(true));
        mvc.perform(post("/api/profiles/10/chat-sessions").header("Authorization", authorization()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", aMapWithSize(3)))
                .andExpect(jsonPath("$.sessionId").value(42))
                .andExpect(jsonPath("$.title").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").value("2026-10-19T08:00:00+09:00"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void reusedRoomUses200() throws Exception {
        when(sessions.createOrReuse(10L)).thenReturn(result(false));
        mvc.perform(post("/api/profiles/10/chat-sessions").header("Authorization", authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(42));
    }

    @Test
    void unauthenticatedRequestStopsBeforeTheService() throws Exception {
        mvc.perform(post("/api/profiles/10/chat-sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(sessions);
    }

    @Test
    void nonPositiveProfileIdIsRejectedBeforeTheService() throws Exception {
        for (long profileId : new long[]{0, -1}) {
            mvc.perform(post("/api/profiles/{id}/chat-sessions", profileId)
                            .header("Authorization", authorization()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(sessions);
    }

    private ChatSessionService.Creation result(boolean created) {
        return new ChatSessionService.Creation(new ChatSessionResponse(42L, null,
                OffsetDateTime.parse("2026-10-19T08:00:00+09:00")), created);
    }

    private String authorization() {
        return "Bearer " + tokens.createAccessToken(7L, "fixture@example.test", "ROLE_USER");
    }
}

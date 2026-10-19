package org.semiproject.pillbadeumserver.chat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.service.ChatSessionService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ChatSessionController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, WebConfig.class, GlobalExceptionHandler.class})
class ChatSessionWebTests {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean ChatSessionService sessions;

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

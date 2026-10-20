package org.semiproject.pillbadeumserver.chat;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.service.MessageHistoryService;
import org.semiproject.pillbadeumserver.chat.dto.MessageHistoryResponse;
import org.semiproject.pillbadeumserver.chat.controller.MessageHistoryController;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.config.WebConfig;
import org.semiproject.pillbadeumserver.common.exception.ForbiddenException;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 보안 필터·GET 매핑·쿼리 검증·응답 계약을 확인한다. SQL은 별도 MySQL 테스트의 범위이다. */
@WebMvcTest(controllers = MessageHistoryController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, WebConfig.class, GlobalExceptionHandler.class})
class MessageHistoryWebTests {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean MessageHistoryService history;

    @Test
    void defaultsReturnOnlyContractFieldsWithKstRolesAndNoStore() throws Exception {
        when(history.read(42L, null, 30)).thenReturn(new MessageHistoryResponse(42L, 10L, "fixture profile", null, 0,
                100L, List.of(new MessageHistoryResponse.Message(100L, 1, MessageHistoryResponse.Role.USER, "question",
                OffsetDateTime.parse("2026-10-19T09:00:00+09:00"))), false));
        mvc.perform(get("/api/chat-sessions/42/messages").header("Authorization", auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", aMapWithSize(8)))
                .andExpect(jsonPath("$.profileId").value(10)).andExpect(jsonPath("$.profileName").value("fixture profile"))
                .andExpect(jsonPath("$.title").value(nullValue())).andExpect(jsonPath("$.drugCount").value(0))
                .andExpect(jsonPath("$.retryMessageId").value(100)).andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.messages[0]", aMapWithSize(6)))
                .andExpect(jsonPath("$.messages[0].durNotice").value(nullValue())).andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[0].createdAt").value("2026-10-19T09:00:00+09:00"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void cursorAndTurnLimitArePassedWithoutARequestBody() throws Exception {
        when(history.read(42L, 31, 2)).thenReturn(empty());
        mvc.perform(get("/api/chat-sessions/42/messages").param("beforeTurn", "31").param("limit", "2")
                        .header("Authorization", auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages", hasSize(0)));
        verify(history).read(42L, 31, 2);
    }

    @Test
    void blankOptionalQueryValuesUseTheSameDefaultsAsOmission() throws Exception {
        when(history.read(42L, null, 30)).thenReturn(empty());
        mvc.perform(get("/api/chat-sessions/42/messages").param("beforeTurn", "").param("limit", "")
                        .header("Authorization", auth())).andExpect(status().isOk());
        verify(history).read(42L, null, 30);
    }

    @Test
    void missingOrInvalidAuthenticationStopsBeforeTheService() throws Exception {
        mvc.perform(get("/api/chat-sessions/42/messages")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/chat-sessions/42/messages").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(history);
    }

    @Test
    void invalidSessionIdsAreRejectedBeforeTheService() throws Exception {
        for (String id : List.of("0", "-1", "abc", "9223372036854775808")) {
            mvc.perform(get("/api/chat-sessions/{id}/messages", id).header("Authorization", auth()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(history);
    }

    @Test
    void invalidCursorsAndLimitsAreRejectedBeforeTheService() throws Exception {
        for (String cursor : List.of("0", "-1", "abc", "2147483648")) {
            mvc.perform(get("/api/chat-sessions/42/messages").param("beforeTurn", cursor).header("Authorization", auth()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        for (String limit : List.of("0", "-1", "31", "abc", "2147483648")) {
            mvc.perform(get("/api/chat-sessions/42/messages").param("limit", limit).header("Authorization", auth()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(history);
    }

    @Test
    void missingRoomAndForeignOwnerUseTheExistingTwoFieldErrors() throws Exception {
        when(history.read(42L, null, 30)).thenThrow(new NotFoundException("대화방을 찾을 수 없습니다."));
        mvc.perform(get("/api/chat-sessions/42/messages").header("Authorization", auth()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$", aMapWithSize(2)))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        doThrow(new ForbiddenException()).when(history).read(42L, null, 30);
        mvc.perform(get("/api/chat-sessions/42/messages").header("Authorization", auth()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$", aMapWithSize(2)))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private String auth() { return "Bearer " + tokens.createAccessToken(7L, "fixture@example.test", "ROLE_USER"); }
    private MessageHistoryResponse empty() { return new MessageHistoryResponse(42L, 10L, "fixture", null, 0, null, List.of(), false); }
}

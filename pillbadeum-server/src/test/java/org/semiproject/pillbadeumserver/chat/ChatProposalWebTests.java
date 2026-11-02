package org.semiproject.pillbadeumserver.chat;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.controller.ChatProposalController;
import org.semiproject.pillbadeumserver.chat.controller.ChatProposalExceptionHandler;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalConfirmRequest;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse;
import org.semiproject.pillbadeumserver.chat.service.ChatProposalConflictException;
import org.semiproject.pillbadeumserver.chat.service.ChatProposalService;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.config.WebConfig;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.*;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** V3-CHAT-003~005: 실제 보안 필터·경로·본문 검증·409 본문 모양을 확인한다. 저장은 MySQL 테스트의 범위이다. */
@WebMvcTest(controllers = ChatProposalController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        CorsConfig.class, WebConfig.class, GlobalExceptionHandler.class, ChatProposalExceptionHandler.class})
class ChatProposalWebTests {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean ChatProposalService proposals;

    @Test
    void cardHasOnlyContractFieldsWithKstTimes() throws Exception {
        when(proposals.get(5L)).thenReturn(card(ChatProposalResponse.Status.PENDING));
        mvc.perform(get("/api/chat-proposals/5").header("Authorization", auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", aMapWithSize(10)))
                .andExpect(jsonPath("$.kind").value("REGISTER")).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-02T09:00:00+09:00"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-02T09:30:00+09:00"))
                .andExpect(jsonPath("$.decidedAt").value(nullValue())).andExpect(jsonPath("$.schedule").value(nullValue()))
                .andExpect(jsonPath("$.items[0]", aMapWithSize(12)))
                .andExpect(jsonPath("$.items[0].strengthMatch").value("MATCH"))
                .andExpect(jsonPath("$.items[0].alreadyRegistered").value(false));
    }

    @Test
    void confirmPassesTheChosenNumbersAndAnEmptyBodyStaysEmpty() throws Exception {
        when(proposals.confirm(eq(5L), any())).thenReturn(card(ChatProposalResponse.Status.CONFIRMED));
        mvc.perform(post("/api/chat-proposals/5/confirm").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemNos\":[2,1]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        verify(proposals).confirm(5L, new ChatProposalConfirmRequest(List.of(2, 1)));
        mvc.perform(post("/api/chat-proposals/5/confirm").header("Authorization", auth())).andExpect(status().isOk());
        verify(proposals).confirm(eq(5L), isNull());
    }

    @Test
    void malformedChoicesAreRejectedBeforeTheService() throws Exception {
        for (String body : List.of("{\"itemNos\":[]}", "{\"itemNos\":[0]}", "{\"itemNos\":[11]}", "{\"itemNos\":[null]}",
                "{\"itemNos\":[1,2,3,4,5,6,7,8,9,10,1]}", "{\"itemNos\":\"1\"}", "[1]")) {
            mvc.perform(post("/api/chat-proposals/5/confirm").header("Authorization", auth())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        for (String id : List.of("0", "-1", "abc")) {
            mvc.perform(post("/api/chat-proposals/{id}/cancel", id).header("Authorization", auth()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(proposals);
    }

    @Test
    void conflictCarriesTheCurrentCard() throws Exception {
        var confirmed = card(ChatProposalResponse.Status.CONFIRMED);
        when(proposals.confirm(eq(5L), any())).thenThrow(new ChatProposalConflictException(
                ChatProposalConflictException.Code.PROPOSAL_ALREADY_CONFIRMED, confirmed));
        mvc.perform(post("/api/chat-proposals/5/confirm").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemNos\":[1]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$", aMapWithSize(3)))
                .andExpect(jsonPath("$.code").value("PROPOSAL_ALREADY_CONFIRMED"))
                .andExpect(jsonPath("$.message").value(not(emptyString())))
                .andExpect(jsonPath("$.proposal.status").value("CONFIRMED"));
        when(proposals.cancel(5L)).thenThrow(new ChatProposalConflictException(
                ChatProposalConflictException.Code.PROPOSAL_EXPIRED, card(ChatProposalResponse.Status.EXPIRED)));
        mvc.perform(post("/api/chat-proposals/5/cancel").header("Authorization", auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROPOSAL_EXPIRED"))
                .andExpect(jsonPath("$.proposal.status").value("EXPIRED"));
    }

    @Test
    void missingOrForeignProposalUsesTheTwoFieldNotFoundAndLoginIsRequired() throws Exception {
        when(proposals.get(5L)).thenThrow(new NotFoundException("제안을 찾을 수 없습니다."));
        mvc.perform(get("/api/chat-proposals/5").header("Authorization", auth()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$", aMapWithSize(2)))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/chat-proposals/5/cancel")).andExpect(status().isUnauthorized());
        verify(proposals, never()).cancel(any());
    }

    private static ChatProposalResponse card(ChatProposalResponse.Status status) {
        var created = OffsetDateTime.parse("2026-11-02T09:00:00+09:00");
        boolean decided = status != ChatProposalResponse.Status.PENDING;
        boolean confirmed = status == ChatProposalResponse.Status.CONFIRMED;
        return new ChatProposalResponse(5L, ChatProposalResponse.Kind.REGISTER, status, 10L, 101L, created,
                created.plusMinutes(30), decided ? created.plusMinutes(5) : null,
                List.of(new ChatProposalResponse.Item(1, "199900002", "가상약정500밀리그램", "시험제약", null, "가상약정 500",
                        StrengthMatch.MATCH, false, confirmed, confirmed ? 77L : null, "가상약정", "500밀리그램")), null);
    }

    private String auth() { return "Bearer " + tokens.createAccessToken(7L, "fixture@example.test", "ROLE_USER"); }
}

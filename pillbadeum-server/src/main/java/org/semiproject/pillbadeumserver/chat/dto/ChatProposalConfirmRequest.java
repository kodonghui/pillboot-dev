package org.semiproject.pillbadeumserver.chat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * V3-CHAT-003 제안 확정 본문(openapi-v3 ChatProposalConfirmRequest). 등록 제안은 고른 항목 번호를 보내고, 일정 제안은
 * 본문 없이 보낸다. 여기서는 형식만 보고, 제안 종류·카드에 있는 번호·'이미 등록됨'·중복은 서비스가 본다.
 */
public record ChatProposalConfirmRequest(
        @Size(min = 1, max = 10, message = "약목록에 넣을 약을 1~10개 골라 주세요.")
        List<@NotNull(message = "고른 항목 번호가 비어 있어요.")
             @Min(value = 1, message = "카드에 있는 항목 번호를 골라 주세요.")
             @Max(value = 10, message = "카드에 있는 항목 번호를 골라 주세요.") Integer> itemNos) {
}

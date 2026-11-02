package org.semiproject.pillbadeumserver.chat.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalConfirmRequest;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse;
import org.semiproject.pillbadeumserver.chat.service.ChatProposalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** V3-CHAT-003~005: 대화 속 제안 카드 다시 보기·확정·취소(openapi-v3 약봉이 제안). 회원 번호는 로그인에서만 읽는다. */
@RestController
@RequestMapping("/api/chat-proposals/{proposalId}")
public class ChatProposalController {
    private final ChatProposalService proposals;

    public ChatProposalController(ChatProposalService proposals) { this.proposals = proposals; }

    @GetMapping
    public ChatProposalResponse read(@PathVariable @Positive Long proposalId) {
        return proposals.get(proposalId);
    }

    /** 등록 제안은 고른 항목 번호를 보내고, 일정 제안은 본문 없이 보낸다. */
    @PostMapping("/confirm")
    public ChatProposalResponse confirm(@PathVariable @Positive Long proposalId,
                                        @RequestBody(required = false) @Valid ChatProposalConfirmRequest request) {
        return proposals.confirm(proposalId, request);
    }

    @PostMapping("/cancel")
    public ChatProposalResponse cancel(@PathVariable @Positive Long proposalId) {
        return proposals.cancel(proposalId);
    }
}

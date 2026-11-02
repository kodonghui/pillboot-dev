package org.semiproject.pillbadeumserver.chat.service;

import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse;

/**
 * V3-CHAT-003~005: 제안을 확정·취소할 수 없어 409로 답할 때 쓴다. 본문에는 지금 제안 상태를 함께 준다
 * (openapi-v3 ChatProposalConflict). 이 예외가 나면 아무것도 저장하지 않은 상태다.
 */
public class ChatProposalConflictException extends RuntimeException {
    public enum Code {
        PROPOSAL_ALREADY_CONFIRMED("이미 확정한 제안이에요."),
        PROPOSAL_CANCELLED("취소한 제안이에요."),
        PROPOSAL_EXPIRED("만든 지 30분이 지나 만료된 제안이에요. 다시 말해 주시면 새 카드를 만들어 드릴게요."),
        CONFLICT("고른 약이 그사이 약목록에 먼저 저장됐어요. 카드를 다시 확인해 주세요."),
        SCHEDULE_CHANGED("카드를 만든 뒤 일정이 바뀌었어요. 다시 말해 주시면 새 카드를 만들어 드릴게요.");

        private final String message;

        Code(String message) { this.message = message; }

        public String message() { return message; }
    }

    private final Code code;
    private final transient ChatProposalResponse proposal;

    public ChatProposalConflictException(Code code, ChatProposalResponse proposal) {
        super(code.message());
        this.code = code;
        this.proposal = proposal;
    }

    public Code getCode() { return code; }

    public ChatProposalResponse getProposal() { return proposal; }
}

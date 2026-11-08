package org.semiproject.pillbadeumserver.chat.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalConfirmRequest;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * V3-CHAT-003~005·007 제안 카드 다시 보기·확정·취소. 없는 제안과 다른 회원의 제안은 같은 404다(V3-COMMON-003).
 * 확정·취소할 수 없으면 지금 상태를 붙여 409로 답하고 아무것도 저장하지 않는다.
 */
@Service
public class ChatProposalService {
    private static final Logger log = LoggerFactory.getLogger(ChatProposalService.class);
    private final ChatProposalStore proposals;

    public ChatProposalService(ChatProposalStore proposals) {
        this.proposals = proposals;
    }

    public ChatProposalResponse get(Long proposalId) {
        return load(proposalId, SecurityUtil.getCurrentMemberId());
    }

    /**
     * 같은 확정을 다시 보내거나 두 요청이 동시에 오면 하나만 저장되고 다른 하나는 처음 결과와 함께 409다.
     * 순서: 지금 상태(409) → 고른 항목(400) → 저장. 저장 중 상태가 바뀌었으면 다시 읽어 409로 답한다.
     */
    public ChatProposalResponse confirm(Long proposalId, ChatProposalConfirmRequest request) {
        Long memberId = SecurityUtil.getCurrentMemberId();
        var card = load(proposalId, memberId);
        refuseDecided(card);
        if (card.kind() == ChatProposalResponse.Kind.SCHEDULE) return confirmSchedule(card, request, memberId);
        var chosen = chosen(card, request);
        try {
            var confirmed = proposals.confirmRegister(proposalId, memberId, card.profileId(), chosen,
                    request.prescriptionRequested());
            log.info("[제안 확정] kind={} items={} rx={}", card.kind(), chosen.size(), confirmed.items().stream()
                    .filter(item -> item.prescription() != null && item.prescription().applied()).count());
            return confirmed;
        } catch (ChatProposalStore.NotPending changed) {
            refuseDecided(load(proposalId, memberId));
            throw new IllegalStateException("대기 제안을 확정하지 못함", changed);
        } catch (ChatProposalConflictException conflict) {
            throw conflict(conflict.getCode(), load(proposalId, memberId));
        } catch (DataIntegrityViolationException duplicate) {
            // 같은 프로필·품목이 다른 경로로 동시에 저장되면 my_drug UNIQUE가 막는다. 그때만 CONFLICT로 바꾼다.
            var codes = chosen.stream().map(ChatProposalResponse.Item::itemSeq).toList();
            if (proposals.registeredAmong(card.profileId(), codes).isEmpty()) throw duplicate;
            throw conflict(ChatProposalConflictException.Code.CONFLICT, load(proposalId, memberId));
        }
    }

    /**
     * V3-CHAT-007: 일정 제안은 본문 없이 확정한다. 카드를 만든 뒤 대상 약의 일정이 바뀌었거나 그 약을 지웠으면
     * 지금 카드를 붙인 409 SCHEDULE_CHANGED이고 카드는 대기 그대로다(새 카드를 받아야 함).
     */
    private ChatProposalResponse confirmSchedule(ChatProposalResponse card, ChatProposalConfirmRequest request, Long memberId) {
        if (request != null && request.itemNos() != null) throw invalid("일정 제안은 항목을 고르지 않고 확정해요.");
        try {
            var confirmed = proposals.confirmSchedule(card.proposalId(), memberId, card.profileId());
            log.info("[제안 확정] kind={}", card.kind());
            return confirmed;
        } catch (ChatProposalStore.NotPending changed) {
            refuseDecided(load(card.proposalId(), memberId));
            throw new IllegalStateException("대기 제안을 확정하지 못함", changed);
        } catch (ChatProposalConflictException conflict) {
            throw conflict(conflict.getCode(), load(card.proposalId(), memberId));
        }
    }

    /** 이미 취소된 제안은 같은 결과로 200이다. 확정·만료된 제안은 409이고 상태를 바꾸지 않는다. */
    public ChatProposalResponse cancel(Long proposalId) {
        Long memberId = SecurityUtil.getCurrentMemberId();
        var card = load(proposalId, memberId);
        if (card.status() == ChatProposalResponse.Status.CANCELLED) return card;
        refuseDecided(card);
        if (proposals.cancel(proposalId, memberId)) {
            log.info("[제안 취소] kind={}", card.kind());
            return load(proposalId, memberId);
        }
        var now = load(proposalId, memberId);
        if (now.status() == ChatProposalResponse.Status.CANCELLED) return now;
        refuseDecided(now);
        throw new IllegalStateException("대기 제안을 취소하지 못함");
    }

    /**
     * 카드와 맞춰 본 고른 항목(카드 순서). 빈 목록·중복·카드에 없는 번호는 400이다. '이미 등록됨'을 고르면 지금 카드를 붙인
     * 409 CONFLICT다. 대기 카드의 '이미 등록됨'은 읽는 때 약목록 값이라, 화면이 오래된 카드를 보고 있었을 수 있어서다.
     */
    static List<ChatProposalResponse.Item> chosen(ChatProposalResponse card, ChatProposalConfirmRequest request) {
        if (request == null || request.itemNos() == null || request.itemNos().isEmpty())
            throw invalid("약목록에 넣을 약을 하나 이상 골라 주세요.");
        var byNo = card.items().stream().collect(Collectors.toMap(ChatProposalResponse.Item::itemNo, Function.identity()));
        var seen = new HashSet<Integer>();
        var chosen = new ArrayList<ChatProposalResponse.Item>();
        for (Integer itemNo : request.itemNos()) {
            if (itemNo == null || !seen.add(itemNo)) throw invalid("같은 항목은 한 번만 골라 주세요.");
            var item = byNo.get(itemNo);
            if (item == null) throw invalid("카드에 있는 항목 번호를 골라 주세요.");
            chosen.add(item);
        }
        if (chosen.stream().anyMatch(ChatProposalResponse.Item::alreadyRegistered))
            throw conflict(ChatProposalConflictException.Code.CONFLICT, card);
        chosen.sort(Comparator.comparingInt(ChatProposalResponse.Item::itemNo));
        return chosen;
    }

    private ChatProposalResponse load(Long proposalId, Long memberId) {
        return proposals.find(proposalId, memberId).orElseThrow(() -> new NotFoundException("제안을 찾을 수 없습니다."));
    }

    /** 대기가 아닌 제안은 지금 상태에 맞는 409로 막는다. */
    private static void refuseDecided(ChatProposalResponse card) {
        switch (card.status()) {
            case PENDING -> { }
            case CONFIRMED -> throw conflict(ChatProposalConflictException.Code.PROPOSAL_ALREADY_CONFIRMED, card);
            case CANCELLED -> throw conflict(ChatProposalConflictException.Code.PROPOSAL_CANCELLED, card);
            case EXPIRED -> throw conflict(ChatProposalConflictException.Code.PROPOSAL_EXPIRED, card);
        }
    }

    private static ChatProposalConflictException conflict(ChatProposalConflictException.Code code, ChatProposalResponse card) {
        log.info("[제안 충돌] code={}", code);
        return new ChatProposalConflictException(code, card);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_INPUT, message);
    }
}

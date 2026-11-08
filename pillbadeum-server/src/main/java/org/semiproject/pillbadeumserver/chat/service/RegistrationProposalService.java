package org.semiproject.pillbadeumserver.chat.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * V3-CHAT-002 등록 제안 만들기. 저장된 등록 요청 질문에 제안 카드나 안내를 답변으로 저장한다.
 * 약목록에는 아무것도 저장하지 않는다(확정은 V3-CHAT-003). 답변 글은 모델이 만들지 않은 고정 문장이다.
 */
@Service
public class RegistrationProposalService {
    private static final Logger log = LoggerFactory.getLogger(RegistrationProposalService.class);
    static final int MAX_ITEMS = 10;
    static final String FOUND = "등록할 약 후보를 찾았어요. 카드에서 약목록에 넣을 약을 고른 뒤 확정해 주세요. "
            + "확정하기 전에는 저장되지 않고, 카드는 30분 동안 쓸 수 있어요.";
    static final String ASK_NAME = "등록할 약 이름을 알려 주세요. 포장에 적힌 제품명과 함량으로 말해 주시면 후보를 찾아 드릴게요. "
            + "예) 타이레놀 500 등록해 줘";
    static final String NOT_FOUND = "말씀하신 약을 공공데이터에서 찾지 못했어요. 포장에 적힌 제품명과 함량으로 다시 말해 주세요.";
    static final String SEARCH_FAILED = "지금은 공공데이터에서 약을 찾을 수 없어요. 잠시 뒤 다시 말해 주세요. 약목록에는 아무것도 저장하지 않았어요.";

    private final RegistrationCandidates candidates;
    private final QuestionStore questions;
    private final ChatProposalStore proposals;

    public RegistrationProposalService(RegistrationCandidates candidates, QuestionStore questions, ChatProposalStore proposals) {
        this.candidates = candidates;
        this.questions = questions;
        this.proposals = proposals;
    }

    /**
     * @param mentions 의도 가르기(V3-CHAT-001)가 뽑은 이름·함량 원문(최대 10)
     * @param retry 다시 보내기면 저장 직전에 다시 보내기 자격을 확인한다(기존 답변과 같음)
     * @return 제안 카드가 붙은 답변, 또는 카드 없이 안내만 있는 답변. 저장하지 못하면 같은 질문 ID로 다시 보낼 수 있다.
     */
    public QuestionResponse.Answer propose(Long sessionId, Long memberId, Long profileId, QuestionResponse.Question question,
                                           List<String> mentions, ChatDeadline deadline, boolean retry) {
        try {
            deadline.check();
            if (mentions.isEmpty()) return questions.saveNotice(sessionId, memberId, profileId, question, ASK_NAME, deadline, retry);
            deadline.stage(ChatStage.FINDING_DRUG);
            List<List<RegistrationCandidates.Candidate>> found;
            try {
                found = candidates.find(mentions, deadline);
            } catch (ExternalApiException unavailable) {
                // 질문 전체 한도가 지났으면 안내 대신 실패로 끝내 다시 보내기를 할 수 있게 한다.
                deadline.check();
                log.warn("[등록 제안] result=search_failed");
                return questions.saveNotice(sessionId, memberId, profileId, question, SEARCH_FAILED, deadline, retry);
            }
            var picked = pick(found);
            log.info("[등록 제안] mentions={} items={}", mentions.size(), picked.size());
            if (picked.isEmpty()) return questions.saveNotice(sessionId, memberId, profileId, question, NOT_FOUND, deadline, retry);
            deadline.check();
            return proposals.saveRegisterAnswer(sessionId, memberId, profileId, question, describe(mentions, found),
                    picked, deadline, retry);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.FORBIDDEN || exception.getErrorCode() == ErrorCode.NOT_FOUND
                    || exception.getErrorCode() == ErrorCode.DUPLICATE_REQUEST
                    || exception.getErrorCode() == ErrorCode.RETRY_NOT_ALLOWED) throw exception;
            throw new AnswerFailedException(question.messageId(), exception);
        } catch (RuntimeException exception) {
            throw new AnswerFailedException(question.messageId(), exception);
        }
    }

    /** 이름마다 한 개씩 돌아가며 담아 최대 10개를 고른다. 이름이 많아도 이름마다 첫 후보는 카드에 든다. 같은 품목은 한 번만. */
    static List<RegistrationCandidates.Candidate> pick(List<List<RegistrationCandidates.Candidate>> found) {
        var picked = new ArrayList<RegistrationCandidates.Candidate>();
        var seen = new HashSet<String>();
        for (int round = 0; picked.size() < MAX_ITEMS; round++) {
            boolean any = false;
            for (var list : found) {
                if (round >= list.size()) continue;
                any = true;
                var candidate = list.get(round);
                if (seen.add(candidate.drug().itemSeq())) picked.add(candidate);
                if (picked.size() == MAX_ITEMS) break;
            }
            if (!any) break;
        }
        return picked;
    }

    /**
     * 카드를 설명하는 답변 글. 후보를 하나도 찾지 못한 이름은 다시 말해 달라고 덧붙이고, 비슷한 이름으로만 찾은 이름은
     * 맞는 약인지 확인해 달라고 덧붙인다(2026-11-08). 이름은 ‘ ’로 감싼다(카톡에서 '가 &#39;로 보이는 일을 막음).
     */
    static String describe(List<String> mentions, List<List<RegistrationCandidates.Candidate>> found) {
        var missing = new ArrayList<String>();
        var similar = new ArrayList<String>();
        for (int i = 0; i < mentions.size(); i++) {
            if (found.get(i).isEmpty()) missing.add(quote(mentions.get(i)));
            else if (found.get(i).stream().allMatch(RegistrationCandidates.Candidate::similar)) similar.add(quote(mentions.get(i)));
        }
        var out = new StringBuilder(FOUND);
        if (!similar.isEmpty()) out.append("\n").append(SIMILAR).append(" ").append(String.join(", ", similar))
                .append(". 이름이 꼭 맞는 약이 없어 비슷한 이름을 보여 드려요. 맞는 약인지 확인해 주세요.");
        if (!missing.isEmpty()) out.append("\n").append(MISSING).append(" ").append(String.join(", ", missing))
                .append(". 포장에 적힌 제품명과 함량으로 다시 말해 주세요.");
        return out.toString();
    }

    /** 카톡 다리가 이 줄을 골라 보여 준다(카톡 다리 proposal.py). 문구를 바꾸면 다리도 함께 바꾼다. */
    static final String MISSING = "찾지 못한 이름:";
    static final String SIMILAR = "비슷한 이름으로 찾은 약:";

    static String quote(String name) {
        return "‘" + name + "’";
    }
}

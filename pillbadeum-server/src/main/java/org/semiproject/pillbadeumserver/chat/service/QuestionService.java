package org.semiproject.pillbadeumserver.chat.service;

import java.util.Set;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** CHAT-004·005·007: 권한·입력·진행 표식 → 분류 → PASS 질문 저장 → 답변 생성·저장 순서로 조정한다. */
@Service
public class QuestionService {
    private final QuestionStore store;
    private final QuestionClassifier classifier;
    private final ChatAnswerService answers;
    private final Set<Long> inProgress = ConcurrentHashMap.newKeySet();

    public QuestionService(QuestionStore store, QuestionClassifier classifier, ChatAnswerService answers) {
        this.store = store;
        this.classifier = classifier;
        this.answers = answers;
    }

    /**
     * 질문 앞뒤 공백을 제거하고 1–1,000자를 검사한 뒤 분류한다. 회원 번호는 인증 정보에서만 얻는다.
     * 외부 호출 중 DB 트랜잭션을 유지하지 않으며 성공·차단·예외 모두 finally에서 진행 표식을 반납한다.
     * @return 답변 저장까지 끝난 ANSWERED 또는 저장 없는 BLOCKED. 답변 임베딩은 커밋 뒤 제출한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public QuestionResponse submit(Long sessionId, String content) {
        Long memberId = SecurityUtil.getCurrentMemberId();
        // 90초 응답 한도에 전달 여유를 남긴 외부 작업 예산이다. 취소 후 늦은 답변 쓰기를 막는다.
        var deadline = ChatDeadline.after(Duration.ofSeconds(85));
        Long profileId = store.checkAccess(sessionId, memberId);
        String question = content == null ? "" : content.strip();
        if (question.isBlank() || question.length() > 1000) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "앞뒤 공백을 제거한 질문은 1자 이상 1,000자 이하여야 합니다.");
        }
        if (!inProgress.add(sessionId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST, "이전 질문에 답하는 중이에요.");
        }
        try {
            deadline.check();
            var category = classifier.classify(question);
            if (!category.isPassed()) return QuestionResponse.blocked(category);
            deadline.check();
            var saved = store.save(sessionId, memberId, profileId, question);
            return QuestionResponse.answered(saved, answers.complete(sessionId, memberId, profileId, saved, deadline));
        } finally {
            inProgress.remove(sessionId);
        }
    }

    /** 마지막 미응답 질문만 복구한다. 새 질문과 같은 표식을 공유하고 분류·질문 저장·질문 임베딩은 반복하지 않는다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public QuestionResponse retry(Long sessionId, Long messageId) {
        Long memberId = SecurityUtil.getCurrentMemberId();
        var deadline = ChatDeadline.after(Duration.ofSeconds(85));
        Long profileId = store.checkAccess(sessionId, memberId);
        if (!inProgress.add(sessionId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST, "이전 질문에 답하는 중이에요.");
        }
        try {
            deadline.check();
            var question = store.retryQuestion(sessionId, memberId, profileId, messageId);
            return QuestionResponse.answered(question, answers.retry(sessionId, memberId, profileId, question, deadline));
        } finally {
            inProgress.remove(sessionId);
        }
    }

    /** CHAT-003: 단일 JVM의 진행 상태만 읽는다. 실제 retry 자격은 API에서 DB와 함께 다시 확인한다. */
    public boolean isInProgress(Long sessionId) { return inProgress.contains(sessionId); }
}

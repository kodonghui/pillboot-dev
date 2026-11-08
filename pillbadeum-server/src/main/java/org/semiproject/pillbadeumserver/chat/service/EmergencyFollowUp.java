package org.semiproject.pillbadeumserver.chat.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;

/**
 * 2026-11-08 QA(과다 복용을 말한 뒤 '괜찮겠지?'·'119 안 부르면 안돼?'에 안심시키는 답, '농담이야'에 잡담 안내): 응급·자해 안내로
 * 막은 질문은 저장하지 않아 다음 질문의 분류가 앞 상황을 모른다. 같은 프로필에서 막은 뒤 30분 동안 그 분류와 시각만(질문 글은
 * 두지 않음) 분류기(QuestionClassifier) 메모리에 두고, 이어 묻는 말을 같은 안내로 받게 한다. 새 대화방을 열어도 같은 프로필이면
 * 이어지고, 막을 때마다 30분이 다시 시작하며, 서버를 다시 켜면 잊는다. 프로필을 모르면(null) 기억하지 않는다.
 */
final class EmergencyFollowUp {
    static final Duration WINDOW = Duration.ofMinutes(30);

    private record Mark(QuestionCategory category, Instant at) {}

    private final Map<Long, Mark> marks = new ConcurrentHashMap<>();
    private final Clock clock;

    EmergencyFollowUp(Clock clock) {
        this.clock = clock;
    }

    /** 30분 안에 응급·자해로 막은 분류. 없거나 지났으면 null. */
    QuestionCategory recent(Long profileId) {
        if (profileId == null) return null;
        var mark = marks.get(profileId);
        if (mark == null) return null;
        if (expired(mark)) {
            marks.remove(profileId, mark);
            return null;
        }
        return mark.category();
    }

    /** 응급·자해로 막았음(다른 분류는 무시). 30분 안에 앞서 자해였으면 109 안내가 빠지지 않게 자해로 둔다. */
    void mark(Long profileId, QuestionCategory category) {
        if (profileId == null || category == null || !category.isEmergency()) return;
        var now = new Mark(category, clock.instant());
        marks.merge(profileId, now, (old, next) -> new Mark(
                !expired(old) ? stronger(old.category(), next.category()) : next.category(), next.at()));
    }

    /** 자해가 섞이면 자해(109 안내 포함), 아니면 응급. */
    static QuestionCategory stronger(QuestionCategory a, QuestionCategory b) {
        return a == QuestionCategory.SELF_HARM || b == QuestionCategory.SELF_HARM ? QuestionCategory.SELF_HARM
                : QuestionCategory.EMERGENCY;
    }

    private boolean expired(Mark mark) {
        return mark.at().plus(WINDOW).isBefore(clock.instant());
    }
}

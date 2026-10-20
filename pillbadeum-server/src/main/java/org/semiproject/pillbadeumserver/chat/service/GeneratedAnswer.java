package org.semiproject.pillbadeumserver.chat.service;

import org.semiproject.pillbadeumserver.chat.dto.DurNotice;
import java.util.List;

/** 외부 작업의 읽기 전용 결과이다. 이 객체를 받았다는 이유만으로 DB 저장이 끝난 상태는 아니다. */
public record GeneratedAnswer(String content, String responseId, DurNotice durNotice, List<DurLookup> durLookups) {
    public GeneratedAnswer(String content, String responseId, DurNotice durNotice) {
        this(content, responseId, durNotice, List.of());
    }
}

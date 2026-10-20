package org.semiproject.pillbadeumserver.chat.service;

import java.util.List;
import org.semiproject.pillbadeumserver.chat.domain.AnswerSource;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** CHAT-003·006·007: 기존 content의 근거 꼬리표를 조립하고 맥락에서는 본문만 사용하게 한다. */
@Component
public class AnswerSources {
    public static final String MARKER = "@@SOURCES@@";
    private final ObjectMapper json;

    public AnswerSources(ObjectMapper json) { this.json = json; }

    /** 모델이 만든 근거 표시는 제거하고, 서버의 실제 조회 목록만 마지막 JSON 배열로 붙인다. */
    public String append(String generatedText, List<AnswerSource> sources) {
        String text = body(generatedText).strip();
        if (text.isBlank()) throw ExternalApiException.failed("OpenAI", "AI 응답 본문이 없습니다.", null);
        return text + "\n" + MARKER + "\n" + json.writeValueAsString(sources);
    }

    /** 이전 답변의 정상·손상된 근거 블록 모두 모델 입력·요약에 재사용하지 않는다. */
    public String body(String content) {
        if (content == null) return "";
        int marker = content.indexOf(MARKER);
        return marker < 0 ? content : content.substring(0, marker).stripTrailing();
    }

    /** 이력에서 사용할 공통 파서이다. 손상된 레거시 꼬리표를 새 근거로 추정하지 않는다. */
    public List<AnswerSource> sources(String content) {
        if (content == null || !content.contains(MARKER)) return List.of();
        try {
            var array = json.readTree(content.substring(content.indexOf(MARKER) + MARKER.length()).strip());
            if (!array.isArray()) return List.of();
            var result = new java.util.ArrayList<AnswerSource>();
            for (var item : array) {
                var source = json.treeToValue(item, AnswerSource.class);
                if (source == null || source.source() == null || source.itemSeq() == null || source.itemSeq().isBlank()
                        || source.drugName() == null || source.drugName().isBlank() || source.updatedAt() == null) return List.of();
                result.add(source);
            }
            return List.copyOf(result);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }
}

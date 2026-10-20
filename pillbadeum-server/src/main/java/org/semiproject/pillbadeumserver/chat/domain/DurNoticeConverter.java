package org.semiproject.pillbadeumserver.chat.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.semiproject.pillbadeumserver.chat.dto.DurNotice;
import tools.jackson.databind.json.JsonMapper;

/** CHAT-003/008: 당시 DUR 카드를 저장하며 오래된 NULL 행은 그대로 읽음. */
@Converter
public class DurNoticeConverter implements AttributeConverter<DurNotice, String> {
    private static final JsonMapper JSON = new JsonMapper();
    @Override public String convertToDatabaseColumn(DurNotice value) {
        return value == null ? null : JSON.writeValueAsString(value);
    }
    @Override public DurNotice convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) return null;
        try { return JSON.readValue(value, DurNotice.class); }
        catch (RuntimeException invalidSnapshot) { return null; } // 본문 경고와 과거 이력은 유지함.
    }
}

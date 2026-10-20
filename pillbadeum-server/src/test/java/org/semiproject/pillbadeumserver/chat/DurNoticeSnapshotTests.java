package org.semiproject.pillbadeumserver.chat;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.domain.DurNoticeConverter;
import org.semiproject.pillbadeumserver.chat.dto.DurNotice;

class DurNoticeSnapshotTests {
    @Test void structuredSnapshotPreservesStatusClinicalContentAndReferenceDate() {
        var value = new DurNotice("PARTIAL", "FOUND", 1, List.of("미확인약"),
                List.of(new DurNotice.Item("AGE", List.of("합성약"), "합성 주의", LocalDate.of(2026, 10, 20))), "합성 안내");
        var converter = new DurNoticeConverter();
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(value))).isEqualTo(value);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("[invalid]")).isNull();
    }
}

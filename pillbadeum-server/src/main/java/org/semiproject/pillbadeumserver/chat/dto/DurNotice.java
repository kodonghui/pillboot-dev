package org.semiproject.pillbadeumserver.chat.dto;

import java.time.LocalDate;
import java.util.List;

/** CHAT-008: 조회 범위(lookup)와 확인된 조건의 대조 결과(result)를 분리한 응답이다. */
public record DurNotice(String lookup, String result, int checkedCount, List<String> uncheckedDrugNames,
                        List<Item> items, String message) {
    public static DurNotice unverified(List<String> names) {
        return new DurNotice("FAILED", null, 0, names, List.of(),
                "지금은 금기 정보를 확인하지 못했어요. 금기가 없다는 뜻은 아니니 약사에게 확인해 주세요.");
    }

    public record Item(String type, List<String> drugNames, String content, LocalDate updatedAt) {}
}

package org.semiproject.pillbadeumserver.chat.service;

import java.time.LocalDate;
import java.util.List;

/** CHAT-008·COMMON-005: 이번 요청의 유형별 조회 결과이다. 성공 0건과 실패를 캐시 행 수로 구분하지 않는다. */
public record DurLookup(String itemSeq, Type type, boolean success, List<Row> rows, LocalDate updatedAt) {
    public static DurLookup failed(String code, Type type, LocalDate today) {
        return new DurLookup(code, type, false, List.of(), today);
    }

    /** DB에는 기존 짧은 유형명을 사용하며 외부 서비스 경로·응답 유형은 공식 Service03 기준이다. */
    public enum Type {
        INTERACTION("병용금기", "병용", "/getUsjntTabooInfoList03"),
        AGE("연령금기", "연령", "/getSpcifyAgrdeTabooInfoList03"),
        ELDERLY("노인주의", "노인", "/getOdsnAtentInfoList03"),
        PREGNANCY("임부금기", "임부", "/getPwnmTabooInfoList03");

        private final String label;
        private final String cacheType;
        private final String operation;

        Type(String label, String cacheType, String operation) {
            this.label = label;
            this.cacheType = cacheType;
            this.operation = operation;
        }

        public String label() { return label; }
        public String cacheType() { return cacheType; }
        public String path() { return "/1471000/DURPrdlstInfoService03" + operation; }
        public List<String> cacheAliases() {
            return this == AGE ? List.of(cacheType, label, "특정연령대금기") : List.of(cacheType, label);
        }
    }

    public record Row(String partnerItemSeq, String ingredient, String effect, String content, LocalDate updatedAt) {}
}

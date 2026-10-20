package org.semiproject.pillbadeumserver.drug.dto;

import org.semiproject.pillbadeumserver.drug.domain.DurWarning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/* 설명. 약 근거 출처 (GET /api/drugs/{itemSeq}/guide)
 *  ----------------------------------------------------------------------------------
 *  약 상세 화면의 '효능 · 용법 · 주의사항 · 상호작용 · 부작용' 과 DUR 금기 정보를 출처와 함께 내려준다.
 *  본문은 DB 에 저장하지 않고 요청할 때마다 공공데이터에서 가져온다. (drug 테이블에 본문 컬럼이 없음)
 *  ----------------------------------------------------------------------------------
 *  응답 예)
 *  {
 *    "itemSeq": "197700120",
 *    "name": "부루펜정200밀리그램(이부프로펜)",
 *    "available": true,
 *    "sourceName": "식품의약품안전처 의약품개요정보(e약은요)",
 *    "sourceItemName": "부루펜정200밀리그램(이부프로펜)",   // 출처 자료에 적힌 제품명
 *    "referenceDate": "2024-05-09",          // 출처 자료의 최종 수정일 (없으면 null)
 *    "retrievedAt": "2026-10-20T15:30:00",   // 이번에 조회한 시각
 *    "sections": [
 *      { "key": "EFFICACY", "title": "효능", "content": "이 약은 ..." },
 *      { "key": "USAGE",    "title": "사용법", "content": "..." }
 *    ],
 *    "durWarnings": [ { "type": "노인", "ingredient": "...", "effect": "...", "content": "...", "syncedAt": "..." } ],
 *    "durSourceName": "식품의약품안전처 DUR 품목정보",
 *    "notice": "이 정보는 식품의약품안전처 공개 자료를 옮긴 것으로 진단이나 처방을 대신하지 않습니다. ..."
 *  }
 *  ----------------------------------------------------------------------------------
 *  - available = false 이면 공식 자료에 이 약의 설명이 없다는 뜻이다. (sections 는 빈 배열, 404 아님)
 *  - sections 는 내용이 있는 항목만 화면 순서대로 들어간다.
 *  - durWarnings 는 dur_warning 테이블에 저장된 값이다. 아직 조회된 적 없는 약이면 빈 배열이다.
 *  - sourceItemName 은 출처 자료의 제품명이다. name(drug 테이블)과 크게 다르면 품목기준코드가 잘못 저장된 약일 수 있다.
 * */
public record DrugGuideResponse(
        String itemSeq,                 // 품목기준코드
        String name,                    // 제품명 (drug 테이블)
        boolean available,              // 공식 설명 자료가 있는지
        String sourceName,              // 설명 자료 출처 (없으면 null)
        String sourceItemName,          // 출처 자료에 적힌 제품명 (없으면 null)
        LocalDate referenceDate,        // 출처 자료 최종 수정일 (없으면 null)
        LocalDateTime retrievedAt,      // 이번 조회 시각
        List<Section> sections,         // 효능 · 사용법 · 주의사항 등
        List<DurItem> durWarnings,      // DUR 금기 · 주의
        String durSourceName,           // DUR 출처
        String notice                   // 의료진 상담 안내 문구
) {

    public static final String NOTICE = "이 정보는 식품의약품안전처 공개 자료를 옮긴 것으로 진단이나 처방을 대신하지 않습니다. "
            + "복용 방법을 바꾸거나 다른 약과 함께 먹기 전에 의사 · 약사와 상담하세요.";

    /* 설명. 설명 항목 하나 (key 는 화면 · 챗봇이 구분할 때 쓰는 고정 코드) */
    public record Section(String key, String title, String content) {
    }

    /* 설명. DUR 금기 · 주의 한 줄 */
    public record DurItem(String type, String ingredient, String effect, String content, LocalDateTime syncedAt) {

        public static DurItem from(DurWarning warning) {
            return new DurItem(warning.getType(), warning.getIngredient(), warning.getEffect(),
                    warning.getContent(), warning.getSyncedAt());
        }
    }
}

package org.semiproject.pillbadeumserver.drug.dto;

/* 설명. 약 이름 검색 결과 한 줄 (GET /api/drugs/search?name=&profileId=)
 *  ----------------------------------------------------------------------------------
 *  공공데이터 e약은요(getDrbEasyDrugList) 응답에서 화면에 필요한 값만 옮겨 담는다.
 *  검색 결과는 DB 에 저장하지 않는다. 사용자가 약을 고르면 GET /api/drugs/{itemSeq} 로 넘어간다.
 *  ----------------------------------------------------------------------------------
 *  응답 예)
 *  {
 *    "itemSeq": "200808876",
 *    "name": "가스디알정50밀리그램(디메크로틴산마그네슘)",
 *    "maker": "일동제약(주)",
 *    "imageUrl": "https://nedrug.mfds.go.kr/pbp/cmn/itemImageDownload/...",
 *    "registered": false
 *  }
 *  - imageUrl : 이미지가 없는 약이면 null
 *  - registered : 이 프로필의 내 약 목록에 이미 있으면 true (화면에서 '등록됨' 표시, 중복 등록 방지)
 * */
public record DrugSearchResponse(
        String itemSeq,     // 품목기준코드 (e약은요 itemSeq)
        String name,        // 제품명 (itemName)
        String maker,       // 업체명 (entpName)
        String imageUrl,    // 낱알 이미지 (itemImage)
        boolean registered  // 이 프로필에 이미 등록된 약인지
) {

    /* 설명. 공공데이터 값은 앞뒤 공백이 섞여 오는 경우가 있어 정리하고, 빈 문자열은 null 로 바꾼다. */
    public static DrugSearchResponse of(String itemSeq, String name, String maker,
                                        String imageUrl, boolean registered) {
        return new DrugSearchResponse(clean(itemSeq), clean(name), clean(maker), clean(imageUrl), registered);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}

package org.semiproject.pillbadeumserver.drug.dto;

import org.semiproject.pillbadeumserver.drug.domain.Drug;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;

import java.time.LocalDateTime;

/* 설명. 약 정보 (GET /api/drugs/{itemSeq})
 *  ----------------------------------------------------------------------------------
 *  검색 결과에서 약 하나를 골랐을 때, 등록 전에 '이 약이 맞는지' 보여주는 화면용 응답이다.
 *  값은 drug 테이블(낱알식별 · 허가정보에서 채운 값)에서 온다. 복용정보는 들어 있지 않다.
 *  ----------------------------------------------------------------------------------
 *  응답 예)
 *  {
 *    "itemSeq": "200808876",
 *    "name": "노바스크정5밀리그램",
 *    "maker": "한국비아트리스(주)",
 *    "etcOtc": "ETC",
 *    "etcOtcName": "전문의약품",
 *    "className": "혈압강하제",
 *    "material": "암로디핀베실산염",
 *    "imageUrl": "https://nedrug.mfds.go.kr/pbp/cmn/itemImageDownload/...",
 *    "printFront": "NVS",
 *    "printBack": null,
 *    "shape": "팔각형",
 *    "color": "하양",
 *    "lineFront": null,
 *    "lineBack": null,
 *    "form": "정제",
 *    "sourceName": "식품의약품안전처 의약품 낱알식별 정보",
 *    "syncedAt": "2026-10-19T15:10:00"
 *  }
 *  ----------------------------------------------------------------------------------
 *  - material 은 성분명이다. (DB 컬럼 material, sql/03 에서 matrial 오타를 고침)
 *  - 각인 · 분할선 · 이미지는 없는 약도 있어서 null 일 수 있다.
 *  - sourceName · syncedAt 은 화면의 '근거 출처' 표시에 쓴다. (어디서, 언제 가져온 정보인지)
 * */
public record DrugInfoResponse(
        String itemSeq,         // 품목기준코드
        String name,            // 제품명
        String maker,           // 업체명
        String etcOtc,          // ETC / OTC
        String etcOtcName,      // 전문의약품 / 일반의약품
        String className,       // 분류명
        String material,        // 성분명
        String imageUrl,        // 공식 사진 (없으면 null)
        String printFront,      // 앞면 각인
        String printBack,       // 뒷면 각인
        String shape,           // 모양
        String color,           // 색
        String lineFront,       // 앞면 분할선
        String lineBack,        // 뒷면 분할선
        String form,            // 제형 (정제 / 연질캡슐 / 경질캡슐 / 기타)
        String sourceName,      // 정보 출처
        LocalDateTime syncedAt  // 공공데이터에서 가져온 시각
) {

    public static DrugInfoResponse from(Drug drug) {
        return new DrugInfoResponse(
                drug.getItemSeq(),
                drug.getName(),
                drug.getMaker(),
                drug.getEtcOtc(),
                toEtcOtcName(drug.getEtcOtc()),
                drug.getClassName(),
                drug.getMaterial(),
                drug.getImageUrl(),
                drug.getPrintFront(),
                drug.getPrintBack(),
                drug.getShape(),
                drug.getColor(),
                drug.getLineFront(),
                drug.getLineBack(),
                drug.getForm(),
                drug.metadataSourceName(),
                drug.getSyncedAt());
    }

    private static String toEtcOtcName(String etcOtc) {
        if (Drug.ETC.equals(etcOtc)) {
            return "전문의약품";
        }
        if (Drug.OTC.equals(etcOtc)) {
            return "일반의약품";
        }
        return null;
    }
}

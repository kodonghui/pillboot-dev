package org.semiproject.pillbadeumserver.drug.dto;

import org.semiproject.pillbadeumserver.drug.domain.Drug;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/* 설명. 내 약 목록 한 줄 (GET /api/profiles/{profileId}/drugs)
 *  ----------------------------------------------------------------------------------
 *  목록 화면에서 약 카드 하나를 그리는 데 필요한 값만 담는다.
 *  생김새(각인 · 색 · 분할선)나 근거 출처는 상세 화면 · guide API 에서 내려준다.
 *  ----------------------------------------------------------------------------------
 *  응답 예) 목록 전체는 이 객체의 배열로 내려간다. (페이지 없음, 최근 등록순)
 *  {
 *    "myDrugId": 3,
 *    "itemSeq": "202600001",
 *    "name": "노바스크정5밀리그램",
 *    "maker": "한국화이자제약(주)",
 *    "imageUrl": "https://nedrug.mfds.go.kr/...",
 *    "etcOtc": "ETC",
 *    "dosePerTime": 1,
 *    "timing": "식후 30분",
 *    "doseTimes": ["08:00"],
 *    "startDate": "2026-10-01",
 *    "endDate": "2026-12-31",
 *    "source": "PRESCRIPTION_PHOTO",
 *    "sourceName": "처방전 사진",
 *    "registeredAt": "2026-10-10T09:00:00"
 *  }
 *  ----------------------------------------------------------------------------------
 *  - 엔티티를 그대로 내보내지 않고 이 DTO 로 옮긴다. (지연 로딩 · 엔티티 변경이 응답에 새지 않게)
 *  - from() 은 drug 가 fetch join 으로 함께 조회된 MyDrug 에만 쓴다. (MyDrugRepository.findAllWithDrugByProfileId)
 * */
public record MyDrugListResponse(
        Long myDrugId,
        String itemSeq,
        String name,                // 약 이름
        String maker,               // 업체명
        String imageUrl,            // 공식 사진 (없으면 null)
        String etcOtc,              // ETC(전문) / OTC(일반)
        Integer dosePerTime,        // 복용정보 미설정이면 NULL임.
        String timing,              // 복용 시점 (식후 30분 등, 없으면 null)
        List<String> doseTimes,     // 복용 시각 'HH:mm' 목록 (이른 시각부터)
        LocalDate startDate,        // 복용 시작일
        LocalDate endDate,          // 복용 종료일
        DrugSource source,          // 등록 방법 코드
        String sourceName,          // 등록 방법 한글 (처방전 사진 등)
        LocalDateTime registeredAt  // 등록 시각
) {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public static MyDrugListResponse from(MyDrug myDrug) {

        Drug drug = myDrug.getDrug();
        List<String> doseTimes = myDrug.getDoseTimes().stream()
                .map(TIME_FORMAT::format)
                .toList();

        return new MyDrugListResponse(
                myDrug.getMyDrugId(),
                drug == null ? null : drug.getItemSeq(),
                drug == null ? myDrug.getCustomName() : drug.getName(),
                drug == null ? null : drug.getMaker(),
                drug == null ? null : drug.getImageUrl(),
                drug == null ? null : drug.getEtcOtc(),
                myDrug.getConfirmedDosePerTime(),
                myDrug.getTiming(),
                doseTimes,
                myDrug.getStartDate(),
                myDrug.getEndDate(),
                myDrug.getSource(),
                myDrug.getSource().getDescription(),
                myDrug.getRegisteredAt());
    }
}

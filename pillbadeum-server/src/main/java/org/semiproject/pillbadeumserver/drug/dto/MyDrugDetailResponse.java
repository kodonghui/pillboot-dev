package org.semiproject.pillbadeumserver.drug.dto;

import org.semiproject.pillbadeumserver.drug.domain.Drug;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/* 설명. 내 약 상세 (복용정보 + 약 정보)
 *  ----------------------------------------------------------------------------------
 *  쓰는 곳
 *  - POST  /api/profiles/{profileId}/drugs              저장 결과 (배열)
 *  - GET   /api/profiles/{profileId}/drugs/{myDrugId}   약 상세
 *  ----------------------------------------------------------------------------------
 *  응답 예)
 *  {
 *    "myDrugId": 3,
 *    "name": "타이레놀정500밀리그램",   // 화면에 보여줄 이름 (직접 입력한 약은 입력한 이름)
 *    "dosePerTime": 1,
 *    "timing": "식후 30분",
 *    "doseTimes": ["08:00", "13:00", "19:00"],
 *    "startDate": "2026-10-19",
 *    "endDate": "2026-10-25",
 *    "source": "PRESCRIPTION_PHOTO",
 *    "sourceName": "처방전 사진",
 *    "registeredAt": "2026-10-19T15:10:00",
 *    "updatedAt": "2026-10-19T15:10:00",
 *    "drug": { DrugInfoResponse 와 같은 모양 (모양 · 색 · 각인 · 전문/일반 · 성분명 · 출처) }
 *  }
 *  ----------------------------------------------------------------------------------
 *  - 약 정보는 DrugInfoResponse 를 그대로 넣어 GET /api/drugs/{itemSeq} 와 같은 모양으로 내려준다.
 *  - 효능 · 용법 · 주의사항(근거 출처)은 넣지 않는다. → GET /api/drugs/{itemSeq}/guide
 *  - from() 은 트랜잭션 안에서 호출한다. (MyDrug.drug 지연 로딩)
 *  - 직접 입력한 약(source = MANUAL, 복약 일정 쪽에서 이름만 넣어 등록)은 drug 가 없어 drug = null 이고,
 *    name 에 입력한 이름(customName)이 들어간다. 근거 출처(guide)도 품목기준코드가 없어 부를 수 없다.
 *  - 일정 없는 약은 startDate · endDate 가 null, doseTimes 가 빈 배열이다.
 * */
public record MyDrugDetailResponse(
        Long myDrugId,
        String name,                // 화면에 보여줄 이름 (약 이름, 직접 입력한 약은 입력한 이름)
        Integer dosePerTime,        // 미확정이면 NULL이며 DB 초기값을 실제 복용량으로 표시하지 않음.
        String timing,              // 복용 시점 (없으면 null)
        List<String> doseTimes,     // 복용 시각 'HH:mm' 목록 (이른 시각부터)
        LocalDate startDate,        // 복용 시작일
        LocalDate endDate,          // 복용 종료일
        DrugSource source,          // 등록 방법 코드
        String sourceName,          // 등록 방법 한글
        LocalDateTime registeredAt, // 등록 시각
        LocalDateTime updatedAt,    // 마지막 수정 시각
        DrugInfoResponse drug       // 약 정보 (직접 입력한 약이면 null)
) {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public static MyDrugDetailResponse from(MyDrug myDrug) {

        List<String> doseTimes = myDrug.getDoseTimes().stream()
                .map(TIME_FORMAT::format)
                .toList();

        Drug drug = myDrug.getDrug();

        return new MyDrugDetailResponse(
                myDrug.getMyDrugId(),
                drug == null ? myDrug.getCustomName() : drug.getName(),
                myDrug.getConfirmedDosePerTime(),
                myDrug.getTiming(),
                doseTimes,
                myDrug.getStartDate(),
                myDrug.getEndDate(),
                myDrug.getSource(),
                myDrug.getSource().getDescription(),
                myDrug.getRegisteredAt(),
                myDrug.getUpdatedAt(),
                drug == null ? null : DrugInfoResponse.from(drug));
    }
}

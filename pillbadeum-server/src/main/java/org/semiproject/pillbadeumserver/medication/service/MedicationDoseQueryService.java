package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.DoseItem;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.DoseResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;

/** 선택한 날짜의 복용 예정 목록이다. 복약 완료 기록은 생성하거나 변경하지 않는다. */
@Service
@Transactional(readOnly = true)
public class MedicationDoseQueryService {
    private final MedicationRepository reads;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public MedicationDoseQueryService(MedicationRepository reads) {
        this.reads = reads;
    }

    public DoseResponse get(long memberId, LocalDate date, String time) {
        if (date == null) throw invalid();
        if (time != null && !time.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) throw invalid();
        LocalTime selectedTime = time == null ? null : LocalTime.parse(time, TIME_FORMAT);

        var doses = reads.calendar(memberId, date, date).stream()
                .filter(target -> target.includes(date))
                .flatMap(target -> target.doseTimes().stream()
                        .filter(doseTime -> selectedTime == null || doseTime.equals(selectedTime))
                        .map(doseTime -> new DoseItem(target.myDrugId(), target.profileId(), target.profileName(),
                                target.drugName(), doseTime.format(TIME_FORMAT), target.dosePerTime(), target.timing())))
                .sorted(Comparator.comparing(DoseItem::time).thenComparingLong(DoseItem::profileId)
                        .thenComparingLong(DoseItem::myDrugId))
                .toList();
        return new DoseResponse(date, time, doses);
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_INPUT, "조회 날짜와 복용 시각(HH:mm)을 확인해주세요.");
    }
}

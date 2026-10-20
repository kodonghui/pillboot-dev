package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.CalendarResponse;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** 한 회원의 캘린더에 표시할 여러 프로필의 일정을 조회한다. */
@Service
@Transactional(readOnly = true)
public class MedicationCalendarService {
    private final MedicationRepository reads;

    public MedicationCalendarService(MedicationRepository reads) {
        this.reads = reads;
    }

    public CalendarResponse get(long memberId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "조회 시작일과 종료일을 날짜 순서에 맞게 입력해주세요.");
        }

        var targets = reads.calendar(memberId, from, to);
        boolean pushEnabled = reads.pushEnabled(memberId);
        // 날짜마다 행을 늘리지 않는다. 저장된 전체 기간과 시각을 보내 화면에서 조회 범위에 맞춰 표시한다.
        var schedules = targets.stream().map(target -> ScheduleResponse.from(target, pushEnabled)).toList();
        return new CalendarResponse(from, to, schedules);
    }
}

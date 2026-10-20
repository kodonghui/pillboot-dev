package org.semiproject.pillbadeumserver.medication.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.DoseResponse;
import org.semiproject.pillbadeumserver.medication.service.MedicationDoseQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/calendar/doses")
@Tag(name = "복약 일정")
public class MedicationDoseQueryController {
    private final MedicationDoseQueryService service;

    public MedicationDoseQueryController(MedicationDoseQueryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "날짜·시간별 복용 예정 목록 조회", description = "로그인한 회원의 모든 프로필에서 해당 날짜의 복용 예정 약을 조회합니다. "
            + "time은 선택이며 HH:mm 형식입니다. 생략하면 하루 전체, 지정하면 해당 시각만 반환합니다.")
    public DoseResponse get(@RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                            @RequestParam(value = "time", required = false) String time) {
        return service.get(SecurityUtil.getCurrentMemberId(), date, time);
    }
}

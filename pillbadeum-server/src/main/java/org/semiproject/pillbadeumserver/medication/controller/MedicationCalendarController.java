package org.semiproject.pillbadeumserver.medication.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.CalendarResponse;
import org.semiproject.pillbadeumserver.medication.service.MedicationCalendarService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/calendar")
@Tag(name = "복약 일정")
public class MedicationCalendarController {
    private final MedicationCalendarService service;

    public MedicationCalendarController(MedicationCalendarService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "계정 캘린더 조회", description = "로그인한 회원의 모든 프로필에서 조회 기간과 겹치는 일정을 반환합니다. "
            + "양 끝 날짜를 포함하며, 일정이 없으면 빈 목록입니다. 저장된 일정 기간은 잘라내지 않습니다.")
    public CalendarResponse get(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.get(SecurityUtil.getCurrentMemberId(), from, to);
    }
}

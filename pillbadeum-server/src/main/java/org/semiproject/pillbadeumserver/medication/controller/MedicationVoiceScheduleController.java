package org.semiproject.pillbadeumserver.medication.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.medication.dto.MedicationVoiceDtos.CommandRequest;
import org.semiproject.pillbadeumserver.medication.dto.MedicationVoiceDtos.CommandResponse;
import org.semiproject.pillbadeumserver.medication.service.MedicationVoiceScheduleService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedule-voice-commands")
@Tag(name = "복약 일정")
public class MedicationVoiceScheduleController {
    private final MedicationVoiceScheduleService service;

    public MedicationVoiceScheduleController(MedicationVoiceScheduleService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "음성 문장으로 일정 저장", description = "브라우저 STT의 최종 문장을 해석해 확인 버튼 없이 저장합니다. "
            + "약 생략 시 현재 선택 약, 날짜 생략 시 클릭한 날짜를 사용합니다. 완료 음성 생성만 실패하면 저장 성공과 UNAVAILABLE을 반환합니다.")
    public CommandResponse execute(@Valid @RequestBody CommandRequest request) {
        return service.execute(SecurityUtil.getCurrentMemberId(), request);
    }
}

package org.semiproject.pillbadeumserver.medication.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleRequest;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;
import org.semiproject.pillbadeumserver.medication.service.MedicationScheduleService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profiles/{profileId}/drugs/{myDrugId}/schedule")
@Tag(name = "복약 일정", description = "내 약의 복용 기간·시각 관리. 음성·알림은 별도 단계입니다.")
public class MedicationScheduleController {
    private final MedicationScheduleService service;

    public MedicationScheduleController(MedicationScheduleService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "일정 상세 조회")
    public ScheduleResponse get(@PathVariable @Positive long profileId, @PathVariable @Positive long myDrugId) {
        return service.get(SecurityUtil.getCurrentMemberId(), profileId, myDrugId);
    }

    @PutMapping
    @Operation(summary = "일정 저장·수정", description = "선택한 내 약에 일정을 등록하거나 교체하고 200을 반환합니다.")
    public ScheduleResponse save(@PathVariable @Positive long profileId, @PathVariable @Positive long myDrugId,
                                 @Valid @RequestBody ScheduleRequest request) {
        return service.save(SecurityUtil.getCurrentMemberId(), profileId, myDrugId, request);
    }

    @PostMapping("/stop")
    @Operation(summary = "그만 먹기", description = "진행 중 일정의 종료일을 한국 시간 오늘로 변경합니다. 내일부터 제외됩니다.")
    public ScheduleResponse stop(@PathVariable @Positive long profileId, @PathVariable @Positive long myDrugId) {
        return service.stop(SecurityUtil.getCurrentMemberId(), profileId, myDrugId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "일정 한 건 삭제", description = "선택한 내 약의 기간·복용 시각만 비우고 204를 반환합니다. 약·프로필·다른 일정은 유지합니다.")
    public void delete(@PathVariable @Positive long profileId, @PathVariable @Positive long myDrugId) {
        service.delete(SecurityUtil.getCurrentMemberId(), profileId, myDrugId);
    }
}

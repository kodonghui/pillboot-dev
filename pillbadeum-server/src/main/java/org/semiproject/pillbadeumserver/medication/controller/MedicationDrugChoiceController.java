package org.semiproject.pillbadeumserver.medication.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.DrugChoices;
import org.semiproject.pillbadeumserver.medication.service.MedicationDrugChoiceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profiles/{profileId}/drugs/schedule-choices")
@Tag(name = "복약 일정")
public class MedicationDrugChoiceController {
    private final MedicationDrugChoiceService service;

    public MedicationDrugChoiceController(MedicationDrugChoiceService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "일정 등록용 약 목록 조회", description = "로그인한 회원이 소유한 프로필의 내 약을 반환합니다. "
            + "일정 없는 약도 포함하며 약이 없으면 빈 목록, 없는 프로필이나 다른 회원의 프로필이면 404입니다.")
    public DrugChoices get(@PathVariable @Positive long profileId) {
        return service.get(SecurityUtil.getCurrentMemberId(), profileId);
    }
}

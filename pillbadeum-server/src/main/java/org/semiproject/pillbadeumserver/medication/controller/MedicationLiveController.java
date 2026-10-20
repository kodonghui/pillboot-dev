package org.semiproject.pillbadeumserver.medication.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.medication.dto.MedicationLiveDtos.*;
import org.semiproject.pillbadeumserver.medication.service.MedicationLiveSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/medication/live/sessions")
@Tag(name = "복약 Live")
public class MedicationLiveController {
    private final MedicationLiveSessionService service;
    public MedicationLiveController(MedicationLiveSessionService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Live 음성 대화 시작", description = "선택한 내 약·날짜와 WebRTC SDP offer로 대화를 시작합니다. API 키는 서버에만 있습니다.")
    public StartResponse start(@Valid @RequestBody StartRequest request) {
        return service.start(SecurityUtil.getCurrentMemberId(), request);
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "내 Live 대화의 수집·저장 상태 조회")
    public Progress get(@PathVariable String sessionId) { return service.get(SecurityUtil.getCurrentMemberId(), sessionId); }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "내 Live 대화 종료", description = "추가 저장을 막고 연결을 종료합니다. 이미 저장한 일정은 유지됩니다.")
    public void stop(@PathVariable String sessionId) { service.stop(SecurityUtil.getCurrentMemberId(), sessionId); }
}

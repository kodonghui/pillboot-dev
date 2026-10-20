package org.semiproject.pillbadeumserver.medication.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.SettingRequest;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.SettingResponse;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.SettingSummary;
import org.semiproject.pillbadeumserver.medication.service.NotificationSettingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notification-settings")
@Tag(name = "복약 알림", description = "로그인한 회원의 알림 켜기·끄기와 알림 수신 동의")
public class NotificationSettingController {
    private final NotificationSettingService service;

    public NotificationSettingController(NotificationSettingService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "알림 설정 조회", description = "알림 켜짐, 동의 상태, 동의할 약관 버전, 구독한 브라우저 수")
    public SettingSummary get() {
        return service.get(SecurityUtil.getCurrentMemberId());
    }

    @PutMapping
    @Operation(summary = "알림 켜기·끄기",
            description = "켜기는 동의가 없으면 notificationConsent가 필요하고 없으면 409 CONSENT_REQUIRED. "
                    + "끄기는 동의 철회를 기록하고 이 회원의 모든 브라우저 구독을 지운다.")
    public SettingResponse change(@Valid @RequestBody SettingRequest request) {
        return service.change(SecurityUtil.getCurrentMemberId(), request);
    }
}

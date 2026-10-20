package org.semiproject.pillbadeumserver.medication.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.SubscriptionRequest;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.SubscriptionResponse;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.VapidKeyResponse;
import org.semiproject.pillbadeumserver.medication.service.PushSubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/push")
@Tag(name = "복약 알림", description = "로그인한 회원의 알림 켜기·끄기와 알림 수신 동의")
public class PushSubscriptionController {
    private final PushSubscriptionService service;

    public PushSubscriptionController(PushSubscriptionService service) { this.service = service; }

    @GetMapping("/vapid-public-key")
    @Operation(summary = "웹 푸시 공개 키", description = "브라우저 구독을 만들 때 applicationServerKey로 쓴다. 키가 없으면 503")
    public VapidKeyResponse publicKey() {
        return new VapidKeyResponse(service.publicKey());
    }

    @PostMapping("/subscriptions")
    @Operation(summary = "이 브라우저 구독 저장",
            description = "처음이면 201, 같은 브라우저(endpoint)를 고쳤으면 200. 다른 회원 구독이었으면 지금 회원으로 옮긴다.")
    public ResponseEntity<SubscriptionResponse> subscribe(@Valid @RequestBody SubscriptionRequest request) {
        var saved = service.save(SecurityUtil.getCurrentMemberId(), request);
        return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new SubscriptionResponse(saved.subscriptionId()));
    }
}

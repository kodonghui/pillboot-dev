package org.semiproject.pillbadeumserver.kakao.controller;

import java.util.List;
import org.semiproject.pillbadeumserver.kakao.dto.KakaoLinkDtos.KakaoReminderTarget;
import org.semiproject.pillbadeumserver.kakao.service.KakaoReminderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** V3-KAKAO-017(DEC-033): 카카오 다리 전용(KakaoBridgeAuthFilter). 공개 주소에서는 Caddy가 막는다. */
@RestController
@RequestMapping("/api/kakao-bridge/reminders")
public class KakaoReminderController {

    private final KakaoReminderService reminders;

    public KakaoReminderController(KakaoReminderService reminders) {
        this.reminders = reminders;
    }

    @GetMapping
    public List<KakaoReminderTarget> targets(@RequestParam String date, @RequestParam String time) {
        return reminders.targets(date, time);
    }
}

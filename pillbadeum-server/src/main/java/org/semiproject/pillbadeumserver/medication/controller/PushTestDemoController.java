package org.semiproject.pillbadeumserver.medication.controller;

import org.semiproject.pillbadeumserver.common.security.SecurityUtil;
import org.semiproject.pillbadeumserver.medication.service.PushTestService;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로컬 프로필에만 있는 웹 푸시 시험 화면. 화면·스크립트만 공개하고 시험 발송은 Bearer 인증을 요구한다. */
@RestController
@Profile("local")
public class PushTestDemoController {
    private final PushTestService service;

    public PushTestDemoController(PushTestService service) { this.service = service; }

    @GetMapping(value = "/dev/push-test", produces = MediaType.TEXT_HTML_VALUE)
    public Resource page() { return new ClassPathResource("push-test/index.html"); }

    @GetMapping(value = "/dev/push-test.js", produces = "application/javascript")
    public Resource script() { return new ClassPathResource("push-test/push-test.js"); }

    // 서비스 워커는 /dev/ 아래 페이지를 맡는다.
    @GetMapping(value = "/dev/push-sw.js", produces = "application/javascript")
    public Resource serviceWorker() { return new ClassPathResource("push-test/push-sw.js"); }

    @PostMapping("/dev/push-test/send")
    public PushTestService.Result send() {
        return service.send(SecurityUtil.getCurrentMemberId());
    }
}

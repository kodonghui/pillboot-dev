package org.semiproject.pillbadeumserver.medication.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로컬 프로필에만 있는 수동 연동 화면. 업무 API는 별도의 Bearer 인증을 그대로 요구한다. */
@RestController
@Profile("local")
public class MedicationLiveDemoController {
    @GetMapping(value = "/dev/medication-live", produces = MediaType.TEXT_HTML_VALUE)
    public Resource page() { return new ClassPathResource("medication-live/index.html"); }

    @GetMapping(value = "/dev/medication-live.js", produces = "application/javascript")
    public Resource script() { return new ClassPathResource("medication-live/live.js"); }
}

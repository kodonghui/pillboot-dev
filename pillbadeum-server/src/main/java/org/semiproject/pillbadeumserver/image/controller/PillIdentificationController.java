package org.semiproject.pillbadeumserver.image.controller;

import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.domain.PillIdentificationCandidate;
import org.semiproject.pillbadeumserver.image.service.PillDrugIdentificationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/images")
public class PillIdentificationController {

    private final PillDrugIdentificationService identificationService;

    public PillIdentificationController(PillDrugIdentificationService identificationService) {
        this.identificationService = identificationService;
    }

    /** Accepts OCR/visual identification values and returns ranked drug candidates. */
    @PostMapping("/identify")
    public List<PillIdentificationCandidate> identify(@RequestBody PillIdentification input) {
        return identificationService.identify(input);
    }
}

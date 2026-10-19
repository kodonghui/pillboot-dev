package org.semiproject.pillbadeumserver.member.service;

import org.semiproject.pillbadeumserver.member.domain.Consent;
import org.semiproject.pillbadeumserver.member.domain.ConsentRepository;
import org.semiproject.pillbadeumserver.member.dto.ConsentPolicyResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ConsentService {

    private final ConsentRepository consentRepository;

    @Autowired
    public ConsentService(ConsentRepository consentRepository) {
        this.consentRepository = consentRepository;
    }

    /* db에서 약관을 가져와서 응답 dto로 변환한다. */
    @Transactional(readOnly = true)
    public List<ConsentPolicyResponse> getConsentPolicies() {

        List<Consent> consents = consentRepository.findByActiveTrueAndEffectiveAtLessThanEqualOrderByTermsIdAsc(
            LocalDateTime.now()
        );

        List<ConsentPolicyResponse> responses = new ArrayList<>();
        for (Consent consent : consents) {
            responses.add(ConsentPolicyResponse.from(consent));
        }

        return responses;
    }

}

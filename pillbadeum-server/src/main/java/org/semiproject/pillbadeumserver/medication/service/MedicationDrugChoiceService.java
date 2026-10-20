package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.DrugChoices;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 일정 등록 화면에서 선택한 프로필의 내 약을 고른다. 일정 없는 약도 포함한다. */
@Service
@Transactional(readOnly = true)
public class MedicationDrugChoiceService {
    private final MedicationRepository reads;

    public MedicationDrugChoiceService(MedicationRepository reads) {
        this.reads = reads;
    }

    public DrugChoices get(long memberId, long profileId) {
        if (!reads.ownsProfile(memberId, profileId)) {
            throw new NotFoundException("조회할 수 있는 프로필이 없습니다.");
        }
        var targets = reads.drugs(memberId, profileId);
        boolean pushEnabled = reads.pushEnabled(memberId);
        return new DrugChoices(targets.stream().map(target -> ScheduleResponse.from(target, pushEnabled)).toList());
    }
}

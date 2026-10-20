package org.semiproject.pillbadeumserver.medication.domain;

import jakarta.persistence.LockModeType;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 일정 수정용 행 잠금. 약 담당자의 MyDrug 엔티티를 그대로 사용하며 삭제 메서드는 노출하지 않는다. */
public interface MedicationScheduleWriteRepository extends Repository<MyDrug, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m from MyDrug m
            where m.myDrugId = :myDrugId and m.profileId = :profileId
            """)
    Optional<MyDrug> findForUpdate(@Param("profileId") long profileId, @Param("myDrugId") long myDrugId);

    MyDrug save(MyDrug myDrug);
}

package org.semiproject.pillbadeumserver.drug.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 병용 유형이 이번 요청에서 완전히 조회된 품목만 교체한다. */
public interface DurInteractionRepository extends JpaRepository<DurInteraction, Long> {
    @Modifying(flushAutomatically = true)
    @Query("delete from DurInteraction d where d.drug.itemSeq = :code")
    int deleteForItem(@Param("code") String code);
}

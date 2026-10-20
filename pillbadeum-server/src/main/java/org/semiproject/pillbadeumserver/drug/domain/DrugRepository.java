package org.semiproject.pillbadeumserver.drug.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

/* 설명. drug 테이블 조회 · 저장
 *  ----------------------------------------------------------------------------------
 *  PK 는 품목기준코드(item_seq, String).
 *  - findById(itemSeq)  : GET /api/drugs/{itemSeq}, 내 약 저장 전 약 존재 확인
 *  - save(drug)         : 낱알식별 조회 결과 upsert
 *  ----------------------------------------------------------------------------------
 *  PK 를 직접 넣는 엔티티라 save() 는 먼저 SELECT 로 있는지 확인한 뒤 INSERT / UPDATE 한다.
 *  upsert 는 서비스에서 findById 로 꺼내 sync() 하거나, 없으면 Drug.create() 후 save() 한다.
 * */
public interface DrugRepository extends JpaRepository<Drug, String> {
    /** COMMON-005: 같은 품목의 캐시 교체를 직렬화한다. 여러 품목은 호출자가 코드 순으로 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Drug d where d.itemSeq = :code")
    Optional<Drug> lockForDurRefresh(@Param("code") String code);
}

package org.semiproject.pillbadeumserver.drug.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/*  dur_warning 테이블 조회 · 갱신
 *  ----------------------------------------------------------------------------------
 *  - 근거 출처(GET /api/drugs/{itemSeq}/guide) 에서 약별 금기 목록을 읽는다.
 *  - 공공데이터에서 다시 가져올 때는 '기존 행 삭제 → 새로 저장' 을 한 트랜잭션에서 한다.
 *  ----------------------------------------------------------------------------------
 *  deleteAllByItemSeq 는 DELETE 쿼리를 바로 실행한다. (한 건씩 SELECT 후 삭제하지 않음)
 *  이미 영속성 컨텍스트에 올라온 DurWarning 이 남지 않도록 실행 후 컨텍스트를 비운다.
 * */
public interface DurWarningRepository extends JpaRepository<DurWarning, Long> {

    // 약 하나의 DUR 금기 목록 (저장한 순서대로
    List<DurWarning> findAllByDrug_ItemSeqOrderByDurWarningIdAsc(String itemSeq);

    // 약 하나의 DUR 금기 전체 삭제 (공공데이터 재조회 전에 호출)
    // @return 삭제한 행 수
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from DurWarning w
            where w.drug.itemSeq = :itemSeq
            """)
    int deleteAllByItemSeq(@Param("itemSeq") String itemSeq);

    /** COMMON-005: 실패한 다른 유형·용량 주의 등을 지우지 않고 성공한 유형만 교체한다. */
    @Modifying(flushAutomatically = true)
    @Query("delete from DurWarning w where w.drug.itemSeq = :code and w.type in :types")
    int deleteForType(@Param("code") String code, @Param("types") List<String> types);
}

package org.semiproject.pillbadeumserver.drug.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/*  my_drug 테이블 조회 · 저장 · 삭제
 *  ----------------------------------------------------------------------------------
 *  MyDrug.drug 는 지연 로딩이라, 약 이름 · 이미지까지 쓰는 화면은 fetch join 메서드로 한 번에 가져온다.
 *  (open-in-view: false 이므로 서비스 트랜잭션 밖에서 drug 를 꺼내면 LazyInitializationException 이 난다)
 *  ----------------------------------------------------------------------------------
 *  - 소유권 확인(프로필이 로그인 회원 것인지)은 서비스에서 먼저 하고, 여기서는 profileId 로만 거른다.
 *  - 삭제는 JpaRepository.delete(myDrug) 를 쓴다. (삭제 플래그 컬럼 없음)
 * */
public interface MyDrugRepository extends JpaRepository<MyDrug, Long> {

    // 내 약 목록 (GET /api/profiles/{profileId}/drugs)
    // 최근에 등록한 약이 위로 오게 정렬한다.
    @Query("""
            select m
            from MyDrug m
            left join fetch m.drug
            where m.profileId = :profileId
            order by m.registeredAt desc, m.myDrugId desc
            """)
    List<MyDrug> findAllWithDrugByProfileId(@Param("profileId") Long profileId);

    // 내 약 상세 (GET /api/profiles/{profileId}/drugs/{myDrugId})
    // 어느 프로필의 약인지는 서비스에서 MyDrug.belongsTo(profileId) 로 확인한다.
    @Query("""
            select m
            from MyDrug m
            left join fetch m.drug
            where m.myDrugId = :myDrugId
            """)
    Optional<MyDrug> findWithDrugById(@Param("myDrugId") Long myDrugId);

    // 중복 등록 확인 (POST /api/profiles/{profileId}/drugs)
    // 같은 프로필에 같은 약(item_seq)이 이미 있으면 409.
    boolean existsByProfileIdAndDrug_ItemSeq(Long profileId, String itemSeq);

    // 이 프로필에 이미 등록된 약의 item_seq 목록 (GET /api/drugs/search 의 '등록됨' 표시)
    // drug 를 조인하지 않고 FK 컬럼 값만 읽는다.
    @Query("""
            select m.drug.itemSeq
            from MyDrug m
            where m.profileId = :profileId and m.drug is not null
            """)
    List<String> findItemSeqsByProfileId(@Param("profileId") Long profileId);
}

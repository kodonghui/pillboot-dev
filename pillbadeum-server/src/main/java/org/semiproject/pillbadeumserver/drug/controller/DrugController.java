package org.semiproject.pillbadeumserver.drug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.semiproject.pillbadeumserver.common.PageResponse;
import org.semiproject.pillbadeumserver.drug.dto.DrugGuideResponse;
import org.semiproject.pillbadeumserver.drug.dto.DrugInfoResponse;
import org.semiproject.pillbadeumserver.drug.dto.DrugSearchResponse;
import org.semiproject.pillbadeumserver.drug.service.DrugGuideService;
import org.semiproject.pillbadeumserver.drug.service.DrugSearchService;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/* 설명. 약 검색 · 약 정보 API (/api/drugs)
 *  ----------------------------------------------------------------------------------
 *  로그인 필요. SecurityConfig 의 anyRequest().authenticated() 에 걸리므로 별도 permitAll 없음.
 *  ----------------------------------------------------------------------------------
 *  지금 구현된 API
 *  - GET /api/drugs/search?name=&profileId=&page=&size=   약 이름 검색 (e약은요, DB 저장 안 함)
 *  - GET /api/drugs/{itemSeq}                              약 정보 (없으면 낱알식별 · 허가정보로 채워 저장)
 *  - GET /api/drugs/{itemSeq}/guide                        근거 출처 (e약은요 → 없으면 허가정보, DUR, DB 저장 안 함)
 *  ----------------------------------------------------------------------------------
 *  - page 는 1부터 시작한다. (application.yaml one-indexed-parameters: true) 기본 1페이지 10개, 최대 50개
 *  - name · profileId 를 빠뜨리면 공통 예외 처리기가 400 으로 응답한다.
 *  - 공공데이터 키 누락 · 호출 실패 · 시간 초과는 503 으로 응답한다. (PublicDataClient)
 * */
@Tag(name = "약 검색", description = "약 이름 검색 · 약 정보 조회")
@RestController
@RequestMapping("/api/drugs")
public class DrugController {

    private final DrugSearchService drugSearchService;
    private final DrugSyncService drugSyncService;
    private final DrugGuideService drugGuideService;

    public DrugController(DrugSearchService drugSearchService, DrugSyncService drugSyncService,
                          DrugGuideService drugGuideService) {
        this.drugSearchService = drugSearchService;
        this.drugSyncService = drugSyncService;
        this.drugGuideService = drugGuideService;
    }

    /* 목차. 1. 약 이름 검색 (이 프로필에 이미 등록된 약은 registered = true) */
    @Operation(summary = "약 이름 검색",
            description = "허가정보에서 약 이름으로 검색합니다. 낱알식별에 없는 제형도 조회하며 페이지당 최대20개입니다.")
    @GetMapping("/search")
    public PageResponse<DrugSearchResponse> search(@RequestParam String name,
                                                   @RequestParam Long profileId,
                                                   @PageableDefault(size = DrugSearchService.DEFAULT_PAGE_SIZE) Pageable pageable) {
        return PageResponse.from(drugSearchService.searchForProfile(profileId, name, pageable));
    }

    /* 목차. 2. 약 정보 (검색 결과에서 고른 약, 등록 전 확인용) */
    @Operation(summary = "약 정보",
            description = "품목기준코드로 약 정보를 돌려줍니다. DB 에 없으면 공공데이터에서 가져와 저장합니다.")
    @GetMapping("/{itemSeq}")
    public DrugInfoResponse getDrug(@PathVariable String itemSeq) {
        return DrugInfoResponse.from(drugSyncService.getOrSync(itemSeq));
    }

    /* 목차. 3. 근거 출처 (효능 · 용법 · 주의사항 · 상호작용 · 부작용 + DUR, 출처 · 조회 시각 · 상담 안내 포함) */
    @Operation(summary = "약 근거 출처",
            description = "공공데이터(e약은요, 없으면 허가정보)에서 약 설명을 가져와 출처 · 조회 시각 · 상담 안내와 함께 돌려줍니다. "
                    + "drug 테이블에 없는 약은 404 입니다.")
    @GetMapping("/{itemSeq}/guide")
    public DrugGuideResponse getGuide(@PathVariable String itemSeq) {
        return drugGuideService.getGuide(itemSeq);
    }
}

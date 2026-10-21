package org.semiproject.pillbadeumserver.drug.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.domain.MyDrugRepository;
import org.semiproject.pillbadeumserver.drug.dto.DrugSearchResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/* 설명. REG-008: 허가정보에서 전체 제형을 이름으로 검색함.
 *  ----------------------------------------------------------------------------------
 *  검색 결과는 DB 에 저장하지 않는다. 사용자가 약을 고르면 DrugSyncService가 낱알 또는 허가 상세로 drug를 보장함.
 *  ----------------------------------------------------------------------------------
 *  공개 메서드 2개
 *  1) searchForProfile(profileId, name, pageable)
 *     - GET /api/drugs/search?name=&profileId= 에서 사용
 *     - 프로필 소유권 확인 + 이 프로필에 이미 등록된 약이면 registered = true
 *  2) search(name, pageable)
 *     - 프로필과 무관한 순수 검색. registered 는 항상 false
 *     - 이미지 팀 공유용: 처방전 OCR 로 읽은 약 이름(ocr_candidate.read_name) → itemSeq 후보 찾기
 *  ----------------------------------------------------------------------------------
 *  - 외부 API 호출 동안 DB 연결을 붙잡지 않도록 @Transactional 을 붙이지 않는다.
 *    (등록 여부 조회는 Repository 자체 트랜잭션으로 짧게 끝난다)
 *  - 외부 API 실패 · 시간 초과는 PublicDataClient 가 503 예외로 바꿔 던진다.
 * */
@Service
public class DrugSearchService {

    public static final String SEARCH_PATH = DrugSyncService.PERMIT_INFO_PATH;
    public static final String SOURCE_NAME = "식품의약품안전처 의약품 제품 허가정보";

    public static final int MIN_NAME_LENGTH = 2;
    public static final int MAX_NAME_LENGTH = 50;
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 20;

    private final PublicDataClient publicDataClient;
    private final MyDrugRepository myDrugRepository;
    private final ProfileAccessChecker profileAccessChecker;

    public DrugSearchService(PublicDataClient publicDataClient,
                             MyDrugRepository myDrugRepository,
                             ProfileAccessChecker profileAccessChecker) {
        this.publicDataClient = publicDataClient;
        this.myDrugRepository = myDrugRepository;
        this.profileAccessChecker = profileAccessChecker;
    }

    /* 목차. 1. 프로필 기준 검색 (화면의 '약 등록 › 이름 검색') */
    public Page<DrugSearchResponse> searchForProfile(Long profileId, String name, Pageable pageable) {

        profileAccessChecker.checkOwner(profileId);

        Page<DrugSearchResponse> result = search(name, pageable);
        if (result.isEmpty()) {
            return result;
        }

        Set<String> registeredItemSeqs = new HashSet<>(myDrugRepository.findItemSeqsByProfileId(profileId));
        return result.map(item -> DrugSearchResponse.of(item.itemSeq(), item.name(), item.maker(),
                item.imageUrl(), registeredItemSeqs.contains(item.itemSeq())));
    }

    /* 목차. 2. 순수 이름 검색 (이미지 팀 공유)
     *  @param name     약 이름 일부 (앞뒤 공백 제거 후 2~50자)
     *  @param pageable 페이지 번호 · 크기 (크기는 최대 20, 없으면 1페이지 10개)
     * */
    public Page<DrugSearchResponse> search(String name, Pageable pageable) {

        String keyword = normalizeName(name);
        Pageable page = limitPage(pageable);

        JsonNode body = publicDataClient.get(SEARCH_PATH, Map.of(
                "item_name", keyword,
                "pageNo", page.getPageNumber() + 1,     // 공공데이터는 1페이지부터
                "numOfRows", page.getPageSize()));

        List<DrugSearchResponse> content = PublicDataClient.items(body).stream()
                .map(DrugSearchService::toResponse)
                .filter(item -> item.itemSeq() != null)  // 품목기준코드가 없으면 고를 수 없으므로 제외
                .toList();

        return new PageImpl<>(content, page, PublicDataClient.totalCount(body));
    }

    /** REG-003 보조 경로도 함량 대조를 공유함. 일반 이름 검색의 페이지네이션은 변경하지 않음. */
    public List<DrugSearchResponse> searchPrescription(String name) {
        return new PrescriptionDrugLookup(publicDataClient).find(normalizeName(name), null).stream()
                .map(DrugSearchService::toResponse).filter(item -> item.itemSeq() != null).limit(5).toList();
    }

    /* 허가정보 응답의 실제 코드·이름만 검색 결과로 전달함. */
    private static DrugSearchResponse toResponse(JsonNode item) {
        return DrugSearchResponse.of(
                text(item, "ITEM_SEQ"),
                text(item, "ITEM_NAME"),
                text(item, "ENTP_NAME"),
                text(item, "ITEM_IMAGE"),
                false);
    }

    private static String text(JsonNode item, String field) {
        for (var entry : item.properties())
            if (entry.getKey().equalsIgnoreCase(field) && !entry.getValue().isNull())
                return entry.getValue().asString().strip();
        return null;
    }

    private static String normalizeName(String name) {

        String keyword = (name == null) ? "" : name.strip();
        if (keyword.length() < MIN_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "약 이름을 " + MIN_NAME_LENGTH + "자 이상 입력해주세요.");
        }
        if (keyword.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "약 이름은 " + MAX_NAME_LENGTH + "자 이하로 입력해주세요.");
        }
        return keyword;
    }

    /* 설명. 페이지 정보가 없으면 1페이지 10개, 크기는 최대 20개로 제한한다. (공공데이터 호출량 보호) */
    private static Pageable limitPage(Pageable pageable) {

        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE);
        }
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        return PageRequest.of(pageable.getPageNumber(), size);
    }
}

package org.semiproject.pillbadeumserver.common.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/* 설명. 공공 의약품 · DUR 정보 캐시 (COMMON-005)
 *  ----------------------------------------------------------------------------------
 *  공공데이터포털 API 는 하루 호출 횟수 제한이 있고 응답도 느리다.
 *  같은 약을 여러 사람이 조회해도 한 번만 호출하도록 결과를 메모리에 기억해 둔다.
 *  ----------------------------------------------------------------------------------
 *  사용 예) 공공 API 를 부르는 서비스 메서드에 붙인다.
 *      @Cacheable(cacheNames = CacheConfig.DRUG_INFO, key = "#itemSeq")
 *      public DrugInfo getDrugInfo(String itemSeq) { ... publicDataClient.get(...) ... }
 *  ----------------------------------------------------------------------------------
 *  - 캐시 이름은 아래 상수만 쓴다. 목록에 없는 이름을 쓰면 실행 중 오류가 난다. (오타 방지)
 *  - 갱신 : CacheEvictScheduler 가 매일 새벽 4시에 비운다. 다음 조회 때 최신 정보로 다시 채워진다.
 *  - 주의 : 예외가 난 호출 결과는 저장되지 않는다. null 결과도 저장되므로 '없음'을 캐시하고 싶지 않으면
 *          @Cacheable(..., unless = "#result == null") 을 붙인다.
 * */
@Configuration
@EnableCaching
public class CacheConfig {

    // e약은요 등 의약품 상세 정보 (DRUG-002, CHAT-007)
    public static final String DRUG_INFO = "drugInfo";
    // 약 이름 검색 결과 (REG-008)
    public static final String DRUG_SEARCH = "drugSearch";
    // 낱알식별 정보 · 공식 사진 (REG-004, REG-007)
    public static final String PILL_IDENTIFICATION = "pillIdentification";
    // DUR 금기 정보 (CHAT-008)
    public static final String DUR_INFO = "durInfo";

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(DRUG_INFO, DRUG_SEARCH, PILL_IDENTIFICATION, DUR_INFO);
    }
}

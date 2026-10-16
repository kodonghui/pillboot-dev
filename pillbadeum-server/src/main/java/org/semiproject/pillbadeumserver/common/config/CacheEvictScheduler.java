package org.semiproject.pillbadeumserver.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/* 설명. 공공 의약품 · DUR 캐시를 매일 새벽 4시에 비운다. (COMMON-005 갱신)
 *  허가 변경 · 새 DUR 금기 정보가 다음 날에는 반영되도록 하기 위해서다.
 * */
@Component
public class CacheEvictScheduler {

    private static final Logger log = LoggerFactory.getLogger(CacheEvictScheduler.class);

    private final CacheManager cacheManager;

    public CacheEvictScheduler(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void evictPublicDataCaches() {
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
        log.info("[캐시] 공공 의약품 · DUR 캐시를 비웠습니다. {}", cacheManager.getCacheNames());
    }
}

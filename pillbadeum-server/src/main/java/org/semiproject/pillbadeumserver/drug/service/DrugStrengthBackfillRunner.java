package org.semiproject.pillbadeumserver.drug.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/* 설명. V3-DRUG-002: 운영자가 켤 때만 서버 시작 직후 함량 채우기를 한 번 실행한다.
 *  예) ./gradlew bootRun --args='--drug.strength-backfill.enabled=true'
 *  끝나면 채운 수 · 원문만 채운 수 · 못 채운 수를 로그에 남기고 서버는 그대로 동작한다. 기본은 꺼져 있다.
 * */
@Component
@ConditionalOnProperty(name = "drug.strength-backfill.enabled", havingValue = "true")
public class DrugStrengthBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DrugStrengthBackfillRunner.class);
    private final DrugStrengthBackfill backfill;

    public DrugStrengthBackfillRunner(DrugStrengthBackfill backfill) {
        this.backfill = backfill;
    }

    @Override
    public void run(ApplicationArguments args) {
        var result = backfill.run();
        log.info("drug strength backfill filled={} textOnly={} none={}",
                result.filled(), result.textOnly(), result.none());
    }
}

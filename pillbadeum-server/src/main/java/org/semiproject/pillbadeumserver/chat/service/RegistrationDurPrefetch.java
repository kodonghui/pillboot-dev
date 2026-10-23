package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.semiproject.pillbadeumserver.drug.domain.DrugRegistered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** COMMON-005: 등록 커밋 뒤 DUR을 선조회한다. 외부 실패나 제출 실패는 이미 저장한 내 약을 취소하지 않는다. */
@Component
public class RegistrationDurPrefetch {
    private static final Logger log = LoggerFactory.getLogger(RegistrationDurPrefetch.class);
    private final DurLookupService lookup;
    private final DurCacheStore cache;
    private final AsyncTaskExecutor executor;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Semaphore jobPermit = new Semaphore(1);

    public RegistrationDurPrefetch(DurLookupService lookup, DurCacheStore cache,
                                  @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor) {
        this.lookup = lookup;
        this.cache = cache;
        this.executor = executor;
    }

    /** TX 안의 일괄 저장은 AFTER_COMMIT, TX 밖의 사진 등록은 repository 커밋 반환 뒤 발행한 이벤트만 받는다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void registered(DrugRegistered event) {
        // 선택적인 선조회 여러 묶음이 공공데이터 자리를 모두 차지하지 않게 함.
        if (!jobPermit.tryAcquire()) return;
        var codes = event.itemSeqs().stream().filter(code -> code != null && !code.isBlank())
                .distinct().limit(10).filter(inFlight::add).toList();
        if (codes.isEmpty()) { jobPermit.release(); return; }
        var deadline = ChatDeadline.after(Duration.ofSeconds(35));
        try {
            executor.execute(() -> {
                try {
                    var results = lookup.collectForPrefetch(codes, EnumSet.allOf(DurLookup.Type.class), deadline);
                    deadline.check();
                    cache.refresh(results, deadline);
                    log.info("[등록 후 DUR] result=finished requestedTypes={} successfulTypes={}", results.size(),
                            results.stream().filter(DurLookup::success).count());
                } catch (RuntimeException exception) {
                    // 제공자 메시지·품목/프로필·의료 본문은 로그에 넣지 않는다.
                    log.warn("[등록 후 DUR] result=not_confirmed");
                } finally {
                    inFlight.removeAll(codes);
                    jobPermit.release();
                }
            });
        } catch (RuntimeException exception) {
            inFlight.removeAll(codes);
            jobPermit.release();
            log.warn("[등록 후 DUR] result=not_submitted");
        }
    }
}

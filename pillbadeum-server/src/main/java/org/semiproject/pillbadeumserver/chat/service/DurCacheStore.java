package org.semiproject.pillbadeumserver.chat.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.drug.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** COMMON-005: 호출 스레드가 받은 성공 결과만 짧은 트랜잭션에서 캐시에 반영한다. 외부 호출은 없다. */
@Service
public class DurCacheStore {
    private static final Logger log = LoggerFactory.getLogger(DurCacheStore.class);
    private final DrugRepository drugs;
    private final DurWarningRepository warnings;
    private final DurInteractionRepository interactions;

    public DurCacheStore(DrugRepository drugs, DurWarningRepository warnings, DurInteractionRepository interactions) {
        this.drugs = drugs;
        this.warnings = warnings;
        this.interactions = interactions;
    }

    /** 실패·길이 초과 유형은 기존 행을 유지한다. 성공 0건은 그 유형만 비우며 조회 완료 열을 추가하지 않는다. */
    @Transactional(timeout = 5)
    public void refresh(List<DurLookup> results, ChatDeadline deadline) {
        var usable = results.stream().filter(DurLookup::success).filter(this::fitsSchema).toList();
        Map<String, Drug> locked = new LinkedHashMap<>();
        for (String code : usable.stream().map(DurLookup::itemSeq).distinct().sorted().toList()) {
            deadline.check();
            drugs.lockForDurRefresh(code).ifPresent(drug -> locked.put(code, drug));
        }
        LocalDateTime now = LocalDateTime.now(TimeZoneConfig.ZONE);
        for (var result : usable) {
            deadline.check();
            var drug = locked.get(result.itemSeq());
            if (drug == null) continue;
            if (result.type() == DurLookup.Type.INTERACTION) {
                interactions.deleteForItem(result.itemSeq());
                for (var row : result.rows()) {
                    if (row.partnerItemSeq() != null) interactions.save(DurInteraction.create(drug, row.partnerItemSeq(), row.content(), now));
                }
            } else {
                warnings.deleteForType(result.itemSeq(), result.type().cacheAliases());
                for (var row : result.rows()) warnings.save(DurWarning.create(drug, result.type().cacheType(),
                        row.ingredient(), row.effect(), row.content(), now));
            }
        }
        deadline.check();
    }

    private boolean fitsSchema(DurLookup result) {
        boolean fits = result.rows().stream().allMatch(row -> result.type() == DurLookup.Type.INTERACTION
                ? (row.partnerItemSeq() == null || row.partnerItemSeq().length() <= 50) && row.content().length() <= 255
                : row.ingredient().length() <= 50 && row.effect().length() <= 100);
        if (!fits) log.warn("[DUR 캐시] type={} result=skipped_schema_length", result.type());
        return fits;
    }
}

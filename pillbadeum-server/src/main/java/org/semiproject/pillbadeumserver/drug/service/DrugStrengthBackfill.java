package org.semiproject.pillbadeumserver.drug.service;

import org.semiproject.pillbadeumserver.drug.domain.Drug;
import org.semiproject.pillbadeumserver.drug.domain.DrugRepository;
import org.semiproject.pillbadeumserver.drug.domain.DrugStrength;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/* 설명. V3-DRUG-002: 기존 drug 행의 빈 함량 칸을 채운다. (사용자 API 없음, 운영자가 한 번 실행)
 *  ----------------------------------------------------------------------------------
 *  - base_name 이 NULL 인 행만 고르고, 채우면 base_name 이 늘 생기므로 다시 돌려도 같은 결과다.
 *  - 500행씩 따로 커밋한다. 중간에 멈추면 다음 실행이 남은 행부터 한다.
 *  - 실행 방법은 DrugStrengthBackfillRunner 와 sql/README.md 를 본다.
 * */
@Service
public class DrugStrengthBackfill {

    private final DrugRepository drugRepository;
    private final TransactionTemplate batch;

    public DrugStrengthBackfill(DrugRepository drugRepository, PlatformTransactionManager manager) {
        this.drugRepository = drugRepository;
        this.batch = new TransactionTemplate(manager);
    }

    /** 채운 수(값까지), 원문만 채운 수(복합 · 농도), 못 채운 수(품목명에 함량 없음)를 돌려준다. */
    public Result run() {
        int filled = 0, textOnly = 0, none = 0;
        while (true) {
            Result done = batch.execute(status -> {
                int value = 0, text = 0, missing = 0;
                for (Drug drug : drugRepository.findTop500ByBaseNameIsNullOrderByItemSeqAsc()) {
                    DrugStrength strength = DrugStrength.parse(drug.getName());
                    drug.recordStrength(strength);
                    if (strength.micrograms() != null) value++;
                    else if (strength.text() != null) text++;
                    else missing++;
                }
                return new Result(value, text, missing);
            });
            if (done == null || done.total() == 0) break;
            filled += done.filled();
            textOnly += done.textOnly();
            none += done.none();
        }
        return new Result(filled, textOnly, none);
    }

    public record Result(int filled, int textOnly, int none) {
        public int total() { return filled + textOnly + none; }
    }
}

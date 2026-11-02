package org.semiproject.pillbadeumserver.drug.service;

import java.util.List;
import org.semiproject.pillbadeumserver.drug.domain.DrugStrength;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/* 설명. V3-DRUG-002: 기존 drug 행의 빈 함량 칸을 채운다. (사용자 API 없음, 운영자가 한 번 실행)
 *  ----------------------------------------------------------------------------------
 *  - base_name 이 NULL 인 행만 고르고, 채우면 base_name 이 늘 생기므로 다시 돌려도 같은 결과다.
 *  - 500행씩 따로 커밋한다. 중간에 멈추면 다음 실행이 남은 행부터 한다.
 *  - 함량 칸 4개만 UPDATE 한다. 읽은 뒤 품목명이 바뀌었거나 이미 채워진 행은 건드리지 않아,
 *    서비스 중인 서버가 같은 행을 새로 받아도 그 값(이름 · 성분 · 취하 · 동기화 시각)을 되돌리지 않는다.
 *  - 실행 방법은 DrugStrengthBackfillRunner 와 sql/README.md 를 본다.
 * */
@Service
public class DrugStrengthBackfill {

    private static final int BATCH_ROWS = 500;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate batch;

    public DrugStrengthBackfill(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.batch = new TransactionTemplate(manager);
    }

    /** 채운 수(값까지), 원문만 채운 수(복합 · 농도), 못 채운 수(품목명에 함량 없음)를 돌려준다. */
    public Result run() {
        int filled = 0, textOnly = 0, none = 0;
        String after = "";
        while (true) {
            // 코드 순으로 넘겨 가며 읽어, 건너뛴 행이 있어도 같은 묶음을 다시 돌지 않는다.
            List<Row> rows = jdbc.query("SELECT item_seq, name FROM drug WHERE base_name IS NULL AND item_seq > ? "
                    + "ORDER BY item_seq LIMIT " + BATCH_ROWS, (rs, i) -> new Row(rs.getString(1), rs.getString(2)), after);
            if (rows.isEmpty()) break;
            Result done = batch.execute(status -> fill(rows));
            if (done != null) {
                filled += done.filled();
                textOnly += done.textOnly();
                none += done.none();
            }
            after = rows.getLast().itemSeq();
        }
        return new Result(filled, textOnly, none);
    }

    /** 읽은 이름 그대로이고 아직 빈 행만 채운다. 바뀐 행은 세지 않는다. */
    Result fill(List<Row> rows) {
        int value = 0, text = 0, missing = 0;
        for (Row row : rows) {
            DrugStrength strength = DrugStrength.parse(row.name());
            int updated = jdbc.update("UPDATE drug SET base_name=?, strength_text=?, strength_ug=?, strength_unit=? "
                            + "WHERE item_seq=? AND name=? AND base_name IS NULL",
                    strength.baseName() == null ? row.name() : strength.baseName(), strength.text(),
                    strength.micrograms(), strength.unit(), row.itemSeq(), row.name());
            if (updated == 0) continue;
            if (strength.micrograms() != null) value++;
            else if (strength.text() != null) text++;
            else missing++;
        }
        return new Result(value, text, missing);
    }

    record Row(String itemSeq, String name) {}

    public record Result(int filled, int textOnly, int none) {
        public int total() { return filled + textOnly + none; }
    }
}

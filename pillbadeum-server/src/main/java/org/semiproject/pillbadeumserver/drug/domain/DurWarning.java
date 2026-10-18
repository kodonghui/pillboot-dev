package org.semiproject.pillbadeumserver.drug.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;

/* 설명. 약 하나에 대한 DUR 금기 · 주의 정보 (dur_warning 테이블)
 *  ----------------------------------------------------------------------------------
 *  공공데이터 'DUR 품목정보' 에서 받은 임부 · 연령 · 노인 · 용량 금기를 약(item_seq)별로 저장한다.
 *  한 약에 여러 행이 있을 수 있다. (예: 임부 금기 1행 + 노인 주의 1행)
 *  - 근거 출처(GET /api/drugs/{itemSeq}/guide) 에서 출처명 · 조회 시각(synced_at)과 함께 보여준다.
 *  - 챗봇의 DUR 금기 확인(CHAT-008)도 같은 테이블을 쓸 수 있으므로 저장 형식을 바꿀 때는 챗봇 담당과 맞춘다.
 *  ----------------------------------------------------------------------------------
 *  - 공공데이터 원문을 그대로 보관하는 테이블이라 수정 메서드를 두지 않는다.
 *    다시 가져올 때는 해당 약의 기존 행을 지우고 새로 저장한다. (DurWarningRepository)
 *  - 병용 금기(두 약을 같이 먹으면 안 됨)는 dur_interaction 테이블이 따로 맡는다.
 * */
@Entity
@Table(name = "dur_warning")
public class DurWarning {

    public static final String SOURCE_NAME = "식품의약품안전처 DUR 품목정보";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dur_warning_id")
    private Long durWarningId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_seq", nullable = false, updatable = false)
    private Drug drug;

    @Column(name = "type", length = 50)
    private String type;                // 임부, 연령, 노인, 용량 등

    @Column(name = "ingredient", length = 50)
    private String ingredient;          // 금기 대상 성분

    @Column(name = "effect", length = 100)
    private String effect;              // 금기 내용 요약

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;             // API 에서 받은 상세 금기 · 주의 내용

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;     // 공공데이터를 가져온 시각

    /* 설명. JPA 전용 기본 생성자 */
    protected DurWarning() {
    }

    /* 목차. 1. 공공데이터 DUR 조회 결과로 새 금기 정보를 만든다. */
    public static DurWarning create(Drug drug, String type, String ingredient, String effect,
                                    String content, LocalDateTime syncedAt) {

        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("DUR 상세 내용(content)은 비어 있을 수 없습니다.");
        }

        DurWarning warning = new DurWarning();
        warning.drug = Objects.requireNonNull(drug, "drug");
        warning.type = type;
        warning.ingredient = ingredient;
        warning.effect = effect;
        warning.content = content;
        warning.syncedAt = Objects.requireNonNull(syncedAt, "syncedAt");
        return warning;
    }

    public Long getDurWarningId() {
        return durWarningId;
    }

    public Drug getDrug() {
        return drug;
    }

    public String getType() {
        return type;
    }

    public String getIngredient() {
        return ingredient;
    }

    public String getEffect() {
        return effect;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getSyncedAt() {
        return syncedAt;
    }
}

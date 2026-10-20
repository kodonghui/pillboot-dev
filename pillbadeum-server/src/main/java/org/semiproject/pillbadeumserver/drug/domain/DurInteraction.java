package org.semiproject.pillbadeumserver.drug.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** CHAT-008·COMMON-005: 팀 SQL의 item_seq2를 기준 약으로 매핑한다. 상대 품목은 외래 키가 아니다. */
@Entity
@Table(name = "dur_interaction")
public class DurInteraction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dur_interaction_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_seq2", nullable = false, updatable = false)
    private Drug drug;
    @Column(name = "mixture_item_seq", length = 50)
    private String partnerItemSeq;
    @Column(name = "content", length = 255)
    private String content;
    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;

    protected DurInteraction() {}

    public static DurInteraction create(Drug drug, String partner, String content, LocalDateTime now) {
        var row = new DurInteraction();
        row.drug = drug;
        row.partnerItemSeq = partner;
        row.content = content;
        row.syncedAt = now;
        return row;
    }
}

package org.semiproject.pillbadeumserver.medication.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 회원당 1행인 알림 설정. 행이 없으면 알림이 꺼진 것으로 본다. */
@Entity
@Table(name = "notification_setting")
public class NotificationSetting {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "setting_id")
    private Long settingId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected NotificationSetting() {}

    /** 값이 실제로 바뀔 때만 수정 시각을 남긴다. 이미 같은 상태면 그대로 둔다. */
    public void changePushEnabled(boolean enabled, LocalDateTime now) {
        if (pushEnabled == enabled) return;
        pushEnabled = enabled;
        updatedAt = now;
    }

    public Long getMemberId() { return memberId; }
    public boolean isPushEnabled() { return pushEnabled; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}

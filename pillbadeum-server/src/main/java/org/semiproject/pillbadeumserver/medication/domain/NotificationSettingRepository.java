package org.semiproject.pillbadeumserver.medication.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/** 알림 설정 조회·잠금. 켜기·끄기는 회원의 설정 행을 잠근 뒤 처리해 동시에 눌러도 이력이 겹치지 않는다. */
public interface NotificationSettingRepository extends Repository<NotificationSetting, Long> {
    Optional<NotificationSetting> findByMemberId(Long memberId);

    /** 행이 없으면 꺼진 상태로 만든다. member_id UNIQUE라 동시에 와도 1행만 남는다. */
    @Modifying
    @Query(value = """
            INSERT INTO notification_setting(member_id, push_enabled, updated_at) VALUES(:memberId, FALSE, :now)
            ON DUPLICATE KEY UPDATE setting_id = setting_id
            """, nativeQuery = true)
    void insertIfAbsent(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from NotificationSetting s where s.memberId = :memberId")
    Optional<NotificationSetting> findForUpdate(@Param("memberId") Long memberId);
}

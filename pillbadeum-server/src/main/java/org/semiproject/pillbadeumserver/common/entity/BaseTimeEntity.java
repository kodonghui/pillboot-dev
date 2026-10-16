package org.semiproject.pillbadeumserver.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/* 설명. 생성 시각 · 수정 시각을 자동으로 채워 주는 공통 부모 엔티티
 *  ----------------------------------------------------------------------------------
 *  사용 예) public class Profile extends BaseTimeEntity { ... }
 *  - 저장할 때 created_at, updated_at 이 자동으로 채워진다.
 *  - 수정할 때 updated_at 이 자동으로 바뀐다.
 *  - 테이블에는 created_at, updated_at 컬럼(DATETIME, NOT NULL)을 만들어 둔다.
 *  ----------------------------------------------------------------------------------
 *  JpaAuditingConfig 의 @EnableJpaAuditing 이 있어야 동작한다.
 *  CHAT-010(100일 지난 대화방 삭제)처럼 시각 기준으로 처리하는 기능도 이 컬럼을 쓴다.
 * */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

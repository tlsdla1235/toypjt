package com.sst.flaggame.domain.event.entity;

import com.sst.flaggame.common.exception.BusinessException;
import com.sst.flaggame.common.exception.ErrorCode;
import com.sst.flaggame.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;


@Table(name = "events")
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE events SET deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private EventStatus status = EventStatus.DRAFT;

    @Column(name = "cooldown_ms", nullable = false)
    private Integer cooldownMs = 3000;

    @Column(name = "duration_h", nullable = false)
    private Integer durationH;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder
    public Event(String name, Integer cooldownMs, Integer durationH, User createdBy) {
        this.name = name;
        if (cooldownMs != null) this.cooldownMs = cooldownMs;
        this.durationH = durationH;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public void start(LocalDateTime now) {
        if (this.status != EventStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_EVENT_STATUS);
        }
        this.status = EventStatus.RUNNING;
        this.startedAt = now;
        this.endsAt = this.startedAt.plusHours(this.durationH);
    }

    public void end(LocalDateTime now) {
        if (this.status != EventStatus.RUNNING) {
            throw new BusinessException(ErrorCode.INVALID_EVENT_STATUS);
        }
        this.status = EventStatus.ENDED;
        this.endedAt = now;
    }

    public void finalize_(LocalDateTime now) {
        if (this.status != EventStatus.ENDED) {
            throw new BusinessException(ErrorCode.INVALID_EVENT_STATUS);
        }
        this.status = EventStatus.FINALIZED;
        this.finalizedAt = now;
    }
}

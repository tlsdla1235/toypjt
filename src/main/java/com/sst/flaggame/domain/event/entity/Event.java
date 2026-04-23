package com.sst.flaggame.domain.event.entity;

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
@SQLDelete(sql = "UPDATE users SET deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private EventStatus status = EventStatus.DRAFT;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "cooldown_ms", nullable = false)
    private Long cooldownMs = 3000L;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Event(String name, String description, Long cooldownMs, Integer durationH) {
        this.name = name;
        this.description = description;
        if (cooldownMs != null) this.cooldownMs = cooldownMs;
        this.durationH = durationH;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public void start() {
        this.status = EventStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
        this.endsAt = this.startedAt.plusHours(this.durationH);
    }

    public void end() {
        this.status = EventStatus.ENDED;
        this.endedAt = LocalDateTime.now();
    }

    public void finalize_() {
        this.status = EventStatus.FINALIZED;
        this.finalizedAt = LocalDateTime.now();
    }
}

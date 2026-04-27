package com.sst.flaggame.domain.throne.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(CooldownId.class)
@Table(name = "cooldowns")
public class Cooldown {

    @Id
    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public Cooldown(Long eventId, Long userId, LocalDateTime expiresAt) {
        this.eventId = eventId;
        this.userId = userId;
        this.expiresAt = expiresAt;
    }

    public boolean isActiveAt(LocalDateTime now) {
        return expiresAt.isAfter(now);
    }

    public long remainingMs(LocalDateTime now) {
        if (!isActiveAt(now)) {
            return 0L;
        }
        return Duration.between(now, expiresAt).toMillis();
    }

    public void updateExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}

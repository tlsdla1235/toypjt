package com.sst.flaggame.domain.leaderboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(LeaderboardSnapshotId.class)
@Table(name = "leaderboard_snapshot")
public class LeaderboardSnapshot {

    @Id
    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Id
    @Column(name = "rank_no", nullable = false)
    private Integer rankNo;

    @Id
    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "total_hold_ms", nullable = false)
    private Long totalHoldMs;

    public LeaderboardSnapshot(Long eventId, Integer rankNo, LocalDateTime capturedAt, Long userId, Long totalHoldMs) {
        this.eventId = eventId;
        this.rankNo = rankNo;
        this.capturedAt = capturedAt;
        this.userId = userId;
        this.totalHoldMs = totalHoldMs;
    }
}

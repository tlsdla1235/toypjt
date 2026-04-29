package com.sst.flaggame.domain.leaderboard.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

public class LeaderboardSnapshotId implements Serializable {

    private Long eventId;
    private Integer rankNo;
    private LocalDateTime capturedAt;

    public LeaderboardSnapshotId() {
    }

    public LeaderboardSnapshotId(Long eventId, Integer rankNo, LocalDateTime capturedAt) {
        this.eventId = eventId;
        this.rankNo = rankNo;
        this.capturedAt = capturedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LeaderboardSnapshotId that)) {
            return false;
        }
        return Objects.equals(eventId, that.eventId)
                && Objects.equals(rankNo, that.rankNo)
                && Objects.equals(capturedAt, that.capturedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, rankNo, capturedAt);
    }
}

package com.sst.flaggame.domain.throne.entity;

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
@IdClass(EventParticipantId.class)
@Table(name = "event_participants")
public class EventParticipant {

    @Id
    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "total_hold_ms", nullable = false)
    private Long totalHoldMs;

    @Column(name = "claim_success_cnt", nullable = false)
    private Integer claimSuccessCnt;

    @Column(name = "longest_reign_ms", nullable = false)
    private Long longestReignMs;

    @Column(name = "joined_at", insertable = false, updatable = false)
    private LocalDateTime joinedAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}

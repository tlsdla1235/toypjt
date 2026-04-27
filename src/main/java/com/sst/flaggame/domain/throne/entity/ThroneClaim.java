package com.sst.flaggame.domain.throne.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


/*
    append only테이블이며,
    핫스팟 지점 중 하나임.
    그런 이유로, 일부로 fk제약을 제거
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "throne_claims")
public class ThroneClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false)
    private ThroneClaimResult result;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    public ThroneClaim(Long eventId, Long userId, ThroneClaimResult result, LocalDateTime requestedAt) {
        this.eventId = eventId;
        this.userId = userId;
        this.result = result;
        this.requestedAt = requestedAt;
    }
}

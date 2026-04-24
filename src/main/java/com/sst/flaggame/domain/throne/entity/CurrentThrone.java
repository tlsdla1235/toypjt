package com.sst.flaggame.domain.throne.entity;

import com.sst.flaggame.domain.event.entity.Event;
import com.sst.flaggame.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "current_throne")
public class CurrentThrone {

    @Id
    @Column(name = "event_id")
    private Long eventId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reign_id", nullable = false)
    private ThroneReign reign;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "held_since", nullable = false)
    private LocalDateTime heldSince;

    public CurrentThrone(Event event, ThroneReign reign, User user, LocalDateTime heldSince) {
        this.event = event;
        this.reign = reign;
        this.user = user;
        this.heldSince = heldSince;
    }
}

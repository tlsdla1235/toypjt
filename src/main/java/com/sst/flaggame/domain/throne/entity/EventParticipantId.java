package com.sst.flaggame.domain.throne.entity;

import java.io.Serializable;
import java.util.Objects;

public class EventParticipantId implements Serializable {

    private Long eventId;
    private Long userId;

    public EventParticipantId() {
    }

    public EventParticipantId(Long eventId, Long userId) {
        this.eventId = eventId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EventParticipantId that)) {
            return false;
        }
        return Objects.equals(eventId, that.eventId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, userId);
    }
}

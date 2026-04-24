package com.sst.flaggame.domain.event.dto;

import com.sst.flaggame.domain.event.entity.Event;
import com.sst.flaggame.domain.event.entity.EventStatus;

import java.time.LocalDateTime;

public record EventResponse(
        Long id,
        String name,
        EventStatus status,
        Integer cooldownMs,
        Integer durationH,
        LocalDateTime startedAt,
        LocalDateTime endsAt,
        LocalDateTime endedAt,
        LocalDateTime finalizedAt
) {

    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getStatus(),
                event.getCooldownMs(),
                event.getDurationH(),
                event.getStartedAt(),
                event.getEndsAt(),
                event.getEndedAt(),
                event.getFinalizedAt()
        );
    }
}

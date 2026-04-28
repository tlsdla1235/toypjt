package com.sst.flaggame.domain.throne.dto;

public record ParticipantDelta(
        Long eventId,
        Long userId,
        long totalHoldMs,
        int claimSuccessCnt,
        long maxReignMs
) {
}

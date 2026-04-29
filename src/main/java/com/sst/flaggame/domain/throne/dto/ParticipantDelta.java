package com.sst.flaggame.domain.throne.dto;

// 쓰기용 record 증분을 기록.
public record ParticipantDelta(
        Long eventId,
        Long userId,
        long totalHoldMs,
        int claimSuccessCnt,
        long maxReignMs
) {
}

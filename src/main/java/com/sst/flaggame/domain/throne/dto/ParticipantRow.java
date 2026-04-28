package com.sst.flaggame.domain.throne.dto;

public record ParticipantRow(
        Long userId,
        long totalHoldMs,
        int claimSuccessCnt,
        long longestReignMs
) {
}

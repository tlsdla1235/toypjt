package com.sst.flaggame.domain.throne.dto;

// 읽기용 record
public record ParticipantRow(
        Long userId,
        long totalHoldMs,
        int claimSuccessCnt,
        long longestReignMs
) {
}

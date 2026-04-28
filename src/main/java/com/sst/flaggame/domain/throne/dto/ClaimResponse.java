package com.sst.flaggame.domain.throne.dto;

public record ClaimResponse(
        String result,
        Long reignId,
        Long remainingMs
) {
    public static ClaimResponse success(Long reignId) {
        return new ClaimResponse("SUCCESS", reignId, null);
    }
}

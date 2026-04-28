package com.sst.flaggame.domain.throne.dto;

import java.time.LocalDateTime;

public record ThroneState(
        Long currentKingId,
        Long reignId,
        LocalDateTime heldSince
) {
}

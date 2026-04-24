package com.sst.flaggame.domain.event.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateEventRequest(
        @NotBlank
        @Size(max = 128)
        String name,

        @NotNull
        @Min(100)
        Integer cooldownMs,

        @NotNull
        @Min(1)
        @Max(24)
        Integer durationH
) {
}

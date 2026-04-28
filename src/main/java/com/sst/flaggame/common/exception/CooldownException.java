package com.sst.flaggame.common.exception;

import lombok.Getter;

@Getter
public class CooldownException extends RuntimeException {

    private final long remainingMs;

    public CooldownException(long remainingMs) {
        super(ErrorCode.COOLDOWN.getMessage());
        this.remainingMs = remainingMs;
    }
}

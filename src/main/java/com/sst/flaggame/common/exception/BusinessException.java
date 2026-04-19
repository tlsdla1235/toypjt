package com.sst.flaggame.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
class BusinessException {
    private final ErrorCode errorCode;
}

package com.sst.flaggame.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 400
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 입력값입니다"),
    NOT_RUNNING(HttpStatus.BAD_REQUEST, "이벤트가 진행 중이 아닙니다"),
    INVALID_EVENT_STATUS(HttpStatus.BAD_REQUEST, "잘못된 이벤트 상태 전환입니다"),

    // 401
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다"),

    // 403
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다"),
    USER_BLOCKED(HttpStatus.FORBIDDEN, "차단된 사용자입니다"),

    // 404
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다"),
    NO_ACTIVE_EVENT(HttpStatus.NOT_FOUND, "진행 중인 이벤트가 없습니다"),

    // 409
    ALREADY_OWNER(HttpStatus.CONFLICT, "이미 왕좌를 보유하고 있습니다"),
    LOCK_CONFLICT(HttpStatus.CONFLICT, "다른 요청이 처리 중입니다"),
    EVENT_ALREADY_RUNNING(HttpStatus.CONFLICT, "이미 진행 중인 이벤트가 있습니다"),

    // 429
    COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "쿨타임 중입니다"),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "요청 한도를 초과했습니다"),

    // 500
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다");

    private final HttpStatus status;
    private final String message;
}
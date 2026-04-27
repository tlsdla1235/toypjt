package com.sst.flaggame.common.dto;

import com.sst.flaggame.common.exception.ErrorCode;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.ResponseEntity;

@Getter
@Builder
public class ErrorResponse {
    private final int status;
    private final String code;
    private final String message;
    private final Long remainingMs;

    public static ResponseEntity<ErrorResponse> toResponseEntity(ErrorCode errorCode){
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.builder()
                        .status(errorCode.getStatus().value())
                        .code(errorCode.name())
                        .message(errorCode.getMessage())
                        .remainingMs(null)
                        .build()
                );
    }

    //remaning 을 알려주기 위해 새로운 생성자 생성
    /*
        {
            "status": 429,
            "code": "COOLDOWN",
            "message": "쿨타임 중입니다",
            "remainingMs": 1234
         }

     */
    public static ResponseEntity<ErrorResponse> toResponseEntity(ErrorCode errorCode, Long remainingMs){
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.builder()
                        .status(errorCode.getStatus().value())
                        .code(errorCode.name())
                        .message(errorCode.getMessage())
                        .remainingMs(remainingMs)
                        .build()
                );
    }
}

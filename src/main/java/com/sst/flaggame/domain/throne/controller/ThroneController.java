package com.sst.flaggame.domain.throne.controller;

import com.sst.flaggame.common.dto.ApiResponse;
import com.sst.flaggame.common.exception.BusinessException;
import com.sst.flaggame.common.exception.ErrorCode;
import com.sst.flaggame.common.security.AppUserPrincipal;
import com.sst.flaggame.domain.event.service.EventService;
import com.sst.flaggame.domain.throne.dto.ClaimResponse;
import com.sst.flaggame.domain.throne.service.ThroneService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/active")
@RequiredArgsConstructor
public class ThroneController {

    private final EventService eventService;
    private final ThroneService throneService;



    /*
        claim에 body등을 받지 않는 이유가,
        어처피 event id에 대해 검증을 (유효한 event인지) 해야하기때문에 차라리 이렇게 설계하는게 낫다고 판단함
        또, 이 조회는 db조회가 아니라, 메모리에 있는 값을 읽는거라 매우 쌈
     */
    @PostMapping("/claim")
    public ResponseEntity<ApiResponse<ClaimResponse>> claim(
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        Long eventId = eventService.getCurrentActiveEventId();
        if (eventId == null) {
            throw new BusinessException(ErrorCode.NO_ACTIVE_EVENT);
        }

        ClaimResponse response = throneService.claim(eventId, principal.userId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}

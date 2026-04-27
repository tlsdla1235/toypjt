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

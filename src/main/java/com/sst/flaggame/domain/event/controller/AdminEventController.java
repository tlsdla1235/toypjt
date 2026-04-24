package com.sst.flaggame.domain.event.controller;

import com.sst.flaggame.common.dto.ApiResponse;
import com.sst.flaggame.common.security.AppUserPrincipal;
import com.sst.flaggame.domain.event.dto.CreateEventRequest;
import com.sst.flaggame.domain.event.dto.EventResponse;
import com.sst.flaggame.domain.event.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/events")
@RequiredArgsConstructor
public class AdminEventController {

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<ApiResponse<EventResponse>> create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody CreateEventRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(eventService.create(request, principal.userId())));
    }

    @PostMapping("/{eventId}/start")
    public ResponseEntity<ApiResponse<EventResponse>> start(@PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.success(eventService.start(eventId)));
    }

    @PostMapping("/{eventId}/end")
    public ResponseEntity<ApiResponse<EventResponse>> end(@PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.success(eventService.end(eventId)));
    }

    @PostMapping("/{eventId}/finalize")
    public ResponseEntity<ApiResponse<EventResponse>> finalizeEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.success(eventService.finalizeEvent(eventId)));
    }
}

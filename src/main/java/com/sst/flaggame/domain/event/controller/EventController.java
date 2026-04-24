package com.sst.flaggame.domain.event.controller;

import com.sst.flaggame.common.dto.ApiResponse;
import com.sst.flaggame.domain.event.dto.EventResponse;
import com.sst.flaggame.domain.event.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<EventResponse>> getActiveEvent() {
        return ResponseEntity.ok(ApiResponse.success(eventService.getActiveEvent()));
    }
}

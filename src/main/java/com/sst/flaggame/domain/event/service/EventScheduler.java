package com.sst.flaggame.domain.event.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventScheduler {

    private final EventService eventService;

    @Scheduled(fixedDelay = 60_000)
    public void endExpiredEvents() {
        int endedCount = eventService.endExpiredEvents();
        if (endedCount > 0) {
            log.info("ended expired events: count={}", endedCount);
        }
    }
}

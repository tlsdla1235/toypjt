package com.sst.flaggame.domain.event.service;

import com.sst.flaggame.common.exception.BusinessException;
import com.sst.flaggame.common.exception.ErrorCode;
import com.sst.flaggame.domain.event.dto.CreateEventRequest;
import com.sst.flaggame.domain.event.dto.EventResponse;
import com.sst.flaggame.domain.event.entity.Event;
import com.sst.flaggame.domain.event.entity.EventStatus;
import com.sst.flaggame.domain.event.repository.EventRepository;
import com.sst.flaggame.domain.throne.entity.CurrentThrone;
import com.sst.flaggame.domain.throne.entity.ThroneReign;
import com.sst.flaggame.domain.throne.service.ThroneService;
import com.sst.flaggame.domain.throne.repository.CurrentThroneRepository;
import com.sst.flaggame.domain.throne.repository.ThroneReignRepository;
import com.sst.flaggame.domain.user.entity.User;
import com.sst.flaggame.domain.user.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class EventService {

    private static final long SYSTEM_USER_ID = 1L;

    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final ThroneReignRepository throneReignRepository;
    private final CurrentThroneRepository currentThroneRepository;
    private final ThroneService throneService;
    private final AtomicReference<Long> currentActiveEventId = new AtomicReference<>();

    @PostConstruct
    public void restoreRunningEventOnStartup() {
        restoreActiveEventFromDb();
    }

    @Transactional
    public EventResponse create(CreateEventRequest request, Long adminUserId) {
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Event event = Event.builder()
                .name(request.name())
                .cooldownMs(request.cooldownMs())
                .durationH(request.durationH())
                .createdBy(admin)
                .build();

        return EventResponse.from(eventRepository.save(event));
    }

    @Transactional
    public EventResponse start(Long eventId) {
        List<Event> runningEvents = eventRepository.findAllByStatus(EventStatus.RUNNING);
        if (!runningEvents.isEmpty()) {
            throw new BusinessException(ErrorCode.EVENT_ALREADY_RUNNING);
        }

        Event event = findEvent(eventId);
        LocalDateTime now = LocalDateTime.now();
        event.start(now);
        createInitialThrone(event, now);
        runAfterCommit(() -> {
            currentActiveEventId.set(event.getId());
            throneService.initForEvent(event.getId());
        });

        return EventResponse.from(event);
    }

    @Transactional
    public EventResponse end(Long eventId) {
        Event event = findEvent(eventId);
        LocalDateTime now = LocalDateTime.now();
        event.end(now);
        runAfterCommit(() -> {
            currentActiveEventId.compareAndSet(event.getId(), null);
            throneService.endEvent(event.getId());
        });

        return EventResponse.from(event);
    }

    @Transactional
    public EventResponse finalizeEvent(Long eventId) {
        Event event = findEvent(eventId);
        event.finalize_(LocalDateTime.now());
        runAfterCommit(() -> {
            currentActiveEventId.compareAndSet(event.getId(), null);
            throneService.finalizeEvent(event.getId());
        });

        return EventResponse.from(event);
    }

    @Transactional
    public int endExpiredEvents() {
        LocalDateTime now = LocalDateTime.now();
        List<Event> expiredEvents = eventRepository.findAllByStatusAndEndsAtLessThanEqual(
                EventStatus.RUNNING,
                now
        );

        expiredEvents.forEach(event -> {
            event.end(now);
            runAfterCommit(() -> {
                currentActiveEventId.compareAndSet(event.getId(), null);
                throneService.endEvent(event.getId());
            });
        });

        return expiredEvents.size();
    }

    @Transactional(readOnly = true)
    public EventResponse getActiveEvent() {
        Long activeEventId = getCurrentActiveEventId();
        if (activeEventId == null) {
            throw new BusinessException(ErrorCode.NO_ACTIVE_EVENT);
        }
        Event activeEvent = findEvent(activeEventId);
        if (activeEvent.getStatus() == EventStatus.RUNNING) {
            return EventResponse.from(activeEvent);
        }

        currentActiveEventId.compareAndSet(activeEventId, null);

        return restoreActiveEventFromDb()
                .map(EventResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_ACTIVE_EVENT));
    }

    @Transactional(readOnly = true)
    public Long getCurrentActiveEventId() {
        Long activeEventId = currentActiveEventId.get();
        if (activeEventId != null) {
            return activeEventId;
        }

        return restoreActiveEventFromDb()
                .map(Event::getId)
                .orElse(null);
    }

    private Event findEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
    }

    private void createInitialThrone(Event event, LocalDateTime now) {
        User systemUser = userRepository.findById(SYSTEM_USER_ID)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        ThroneReign initialReign = throneReignRepository.save(new ThroneReign(event, systemUser, now));
        currentThroneRepository.save(new CurrentThrone(event, initialReign, systemUser, now));
    }

    private void runAfterCommit(Runnable runnable) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runnable.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runnable.run();
            }
        });
    }

    private java.util.Optional<Event> restoreActiveEventFromDb() {
        return eventRepository.findFirstByStatus(EventStatus.RUNNING)
                .map(event -> {
                    currentActiveEventId.set(event.getId());
                    throneService.restoreRunningEvent(event.getId());
                    return event;
                });
    }
}

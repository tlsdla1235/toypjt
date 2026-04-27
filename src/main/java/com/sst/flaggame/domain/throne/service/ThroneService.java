package com.sst.flaggame.domain.throne.service;

import com.sst.flaggame.common.exception.BusinessException;
import com.sst.flaggame.common.exception.CooldownException;
import com.sst.flaggame.common.exception.ErrorCode;
import com.sst.flaggame.domain.event.entity.Event;
import com.sst.flaggame.domain.event.entity.EventStatus;
import com.sst.flaggame.domain.event.repository.EventRepository;
import com.sst.flaggame.domain.throne.dto.ClaimResponse;
import com.sst.flaggame.domain.throne.dto.ThroneState;
import com.sst.flaggame.domain.throne.entity.*;
import com.sst.flaggame.domain.throne.repository.CooldownRepository;
import com.sst.flaggame.domain.throne.repository.CurrentThroneRepository;
import com.sst.flaggame.domain.throne.repository.ThroneClaimRepository;
import com.sst.flaggame.domain.throne.repository.ThroneReignRepository;
import com.sst.flaggame.domain.user.entity.User;
import com.sst.flaggame.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ThroneService {

    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final ThroneReignRepository throneReignRepository;
    private final CurrentThroneRepository currentThroneRepository;
    private final CooldownRepository cooldownRepository;
    private final ThroneClaimRepository throneClaimRepository;
    private final TransactionTemplate transactionTemplate;

    private final ConcurrentHashMap<Long, ThroneState> throneMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ReentrantLock> lockMap = new ConcurrentHashMap<>();

    public ThroneService(
            EventRepository eventRepository,
            UserRepository userRepository,
            ThroneReignRepository throneReignRepository,
            CurrentThroneRepository currentThroneRepository,
            CooldownRepository cooldownRepository,
            ThroneClaimRepository throneClaimRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.throneReignRepository = throneReignRepository;
        this.currentThroneRepository = currentThroneRepository;
        this.cooldownRepository = cooldownRepository;
        this.throneClaimRepository = throneClaimRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ClaimResponse claim(Long eventId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        cooldownRepository.findActive(eventId, userId, now);

        ReentrantLock lock = lockMap.computeIfAbsent(eventId, ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            ClaimOutcome outcome = Objects.requireNonNull(
                    transactionTemplate.execute(status -> doClaim(eventId, userId))
            );
            return toResponse(outcome);
        } finally {
            lock.unlock();
        }
    }

    public void initForEvent(Long eventId) {
        ThroneState throneState = loadThroneState(eventId)
                .orElseThrow(() -> new IllegalStateException("Missing current throne for event " + eventId));
        throneMap.put(eventId, throneState);
        lockMap.computeIfAbsent(eventId, ignored -> new ReentrantLock(true));
    }

    public void endEvent(Long eventId) {
        lockMap.remove(eventId);
    }

    public void finalizeEvent(Long eventId) {
        throneMap.remove(eventId);
        lockMap.remove(eventId);
    }

    public void restoreRunningEvent(Long eventId) {
        initForEvent(eventId);
    }

    private ClaimOutcome doClaim(Long eventId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        ThroneState currentState = getOrRestoreThroneState(eventId);

        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));

        if (event.getStatus() != EventStatus.RUNNING) {
            throneClaimRepository.save(new ThroneClaim(eventId, userId, ThroneClaimResult.NOT_RUNNING, now));
            return ClaimOutcome.notRunning();
        }

        if (currentState.currentKingId().equals(userId)) {
            throneClaimRepository.save(new ThroneClaim(eventId, userId, ThroneClaimResult.ALREADY_OWNER, now));
            return ClaimOutcome.alreadyOwner();
        }

        Optional<Cooldown> cooldown = cooldownRepository.findActive(eventId, userId, now);
        if (cooldown.isPresent()) {
            long remainingMs = cooldown.get().remainingMs(now);
            throneClaimRepository.save(new ThroneClaim(eventId, userId, ThroneClaimResult.COOLDOWN, now));
            return ClaimOutcome.cooldown(remainingMs);
        }

        User newOwner = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        long heldMs = Duration.between(currentState.heldSince(), now).toMillis();
        int updatedRows = throneReignRepository.closeOpenReign(currentState.reignId(), now, heldMs);
        if (updatedRows != 1) {
            throw new IllegalStateException("Expected exactly one open reign to close for reignId=" + currentState.reignId());
        }

        ThroneReign newReign = throneReignRepository.save(new ThroneReign(event, newOwner, now));
        CurrentThrone currentThrone = currentThroneRepository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("Missing current throne row for event " + eventId));
        currentThrone.update(newReign, newOwner, now);

        cooldownRepository.upsert(eventId, userId, now.plusNanos(event.getCooldownMs() * 1_000_000L));
        throneClaimRepository.save(new ThroneClaim(eventId, userId, ThroneClaimResult.SUCCESS, now));

        ThroneState nextState = new ThroneState(userId, newReign.getId(), now);
        runAfterCommit(() -> throneMap.put(eventId, nextState));

        return ClaimOutcome.success(newReign.getId());
    }

    private ClaimResponse toResponse(ClaimOutcome outcome) {
        return switch (outcome.result()) {
            case SUCCESS -> ClaimResponse.success(outcome.reignId());
            case ALREADY_OWNER -> throw new BusinessException(ErrorCode.ALREADY_OWNER);
            case NOT_RUNNING -> throw new BusinessException(ErrorCode.NOT_RUNNING);
            case COOLDOWN -> throw new CooldownException(outcome.remainingMs());
        };
    }

    private ThroneState getOrRestoreThroneState(Long eventId) {
        ThroneState currentState = throneMap.get(eventId);
        if (currentState != null) {
            return currentState;
        }

        ThroneState restored = loadThroneState(eventId)
                .orElseThrow(() -> new IllegalStateException("Missing throne state for event " + eventId));
        throneMap.put(eventId, restored);
        lockMap.computeIfAbsent(eventId, ignored -> new ReentrantLock(true));
        return restored;
    }

    private Optional<ThroneState> loadThroneState(Long eventId) {
        return currentThroneRepository.findDetailByEventId(eventId)
                .map(currentThrone -> new ThroneState(
                        currentThrone.getUser().getId(),
                        currentThrone.getReign().getId(),
                        currentThrone.getHeldSince()
                ));
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

    private record ClaimOutcome(
            ThroneClaimResult result,
            Long reignId,
            Long remainingMs
    ) {
        private static ClaimOutcome success(Long reignId) {
            return new ClaimOutcome(ThroneClaimResult.SUCCESS, reignId, null);
        }

        private static ClaimOutcome cooldown(Long remainingMs) {
            return new ClaimOutcome(ThroneClaimResult.COOLDOWN, null, remainingMs);
        }

        private static ClaimOutcome alreadyOwner() {
            return new ClaimOutcome(ThroneClaimResult.ALREADY_OWNER, null, null);
        }

        private static ClaimOutcome notRunning() {
            return new ClaimOutcome(ThroneClaimResult.NOT_RUNNING, null, null);
        }
    }
}

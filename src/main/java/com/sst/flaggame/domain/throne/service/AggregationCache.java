package com.sst.flaggame.domain.throne.service;

import com.sst.flaggame.domain.throne.dto.ParticipantDelta;
import com.sst.flaggame.domain.throne.repository.EventParticipantRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class AggregationCache {

    static final long SYSTEM_USER_ID = 1L;

    private final EventParticipantRepository eventParticipantRepository;
    private final ConcurrentHashMap<Key, AggregationDelta> pending = new ConcurrentHashMap<>();

    public void addReignEnd(Long eventId, Long userId, long durationMs) {
        if (userId == null || userId == SYSTEM_USER_ID) {
            return;
        }
        pending.merge(new Key(eventId, userId), AggregationDelta.ofHold(durationMs), AggregationDelta::merge);
    }

    public void addClaimSuccess(Long eventId, Long userId) {
        if (userId == null || userId == SYSTEM_USER_ID) {
            return;
        }
        pending.merge(new Key(eventId, userId), AggregationDelta.ofClaim(), AggregationDelta::merge);
    }

    @Scheduled(fixedDelay = 10_000)
    public void flush() {
        List<ParticipantDelta> drained = drainPending();
        if (drained.isEmpty()) {
            return;
        }

        try {
            eventParticipantRepository.applyDeltaBatch(drained);
        } catch (RuntimeException ex) {
            restore(drained);
            throw ex;
        }
    }

    public void forceFlush() {
        flush();
    }

    @PreDestroy
    public void finalFlush() {
        flush();
    }

    private List<ParticipantDelta> drainPending() {
        List<ParticipantDelta> drained = new ArrayList<>();
        for (Key key : pending.keySet()) {
            if (key.userId() == SYSTEM_USER_ID) {
                pending.remove(key);
                continue;
            }
            AggregationDelta delta = pending.remove(key);
            if (delta != null) {
                drained.add(new ParticipantDelta(
                        key.eventId(),
                        key.userId(),
                        delta.totalHoldMs(),
                        delta.claimSuccessCnt(),
                        delta.maxReignMs()
                ));
            }
        }
        return drained;
    }

    private void restore(List<ParticipantDelta> drained) {
        for (ParticipantDelta delta : drained) {
            pending.merge(
                    new Key(delta.eventId(), delta.userId()),
                    new AggregationDelta(delta.totalHoldMs(), delta.claimSuccessCnt(), delta.maxReignMs()),
                    AggregationDelta::merge
            );
        }
    }

    record Key(Long eventId, Long userId) {
    }

    record AggregationDelta(long totalHoldMs, int claimSuccessCnt, long maxReignMs) {

        static AggregationDelta ofHold(long durationMs) {
            return new AggregationDelta(durationMs, 0, durationMs);
        }

        static AggregationDelta ofClaim() {
            return new AggregationDelta(0L, 1, 0L);
        }

        AggregationDelta merge(AggregationDelta other) {
            return new AggregationDelta(
                    totalHoldMs + other.totalHoldMs,
                    claimSuccessCnt + other.claimSuccessCnt,
                    Math.max(maxReignMs, other.maxReignMs)
            );
        }
    }
}

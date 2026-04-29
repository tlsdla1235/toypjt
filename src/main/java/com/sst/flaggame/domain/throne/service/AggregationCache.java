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

// 찬탈 성공으로 생긴 참가자 집계 변경분을 메모리에 모아두었다가 주기적으로 DB에 반영하는 write-back 버퍼
@Component
@RequiredArgsConstructor
public class AggregationCache {

    static final long SYSTEM_USER_ID = 1L;

    private final EventParticipantRepository eventParticipantRepository;
    private final ConcurrentHashMap<Key, AggregationDelta> pending = new ConcurrentHashMap<>();

    // 이전 왕의 reign 종료 시 보유 시간 증가분을 메모리에 누적
    // ConcurrentHashMap의 .merget 메서드는 (key, value, (oldValue, newValue)->{})으로 동작
    public void addReignEnd(Long eventId, Long userId, long durationMs) {
        if (userId == null || userId == SYSTEM_USER_ID) {
            return;
        }
        pending.merge(new Key(eventId, userId), AggregationDelta.ofHold(durationMs), AggregationDelta::merge);
    }

    // 새 왕의 찬탈 성공 1회를 메모리에 누적
    // 가장많이 찬탈한 사람을 수상하기 위해 집계하는 함수
    public void addClaimSuccess(Long eventId, Long userId) {
        if (userId == null || userId == SYSTEM_USER_ID) {
            return;
        }
        pending.merge(new Key(eventId, userId), AggregationDelta.ofClaim(), AggregationDelta::merge);
    }

    // pending 집계를 원자적으로 꺼내서 event_participants에 batch upsert
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

    // 리더보드 스냅샷 직전처럼 즉시 반영이 필요할 때 수동 flush
    // 영속성 컨텍스트를 flush()한다는 말과 다르니 오해 x
    // LeaderBoardService를 참고 하면 이해하기 편함
    public void forceFlush() {
        flush();
    }

    // 애플리케이션 종료 직전에 남은 집계가 유실되지 않도록 flush
    @PreDestroy
    public void finalFlush() {
        flush();
    }

    // pending 맵을 key별 remove 방식으로 비우면서 이번 flush 대상만 추출
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

    // flush 실패 시 이번에 꺼낸 delta를 pending으로 되돌려 유실을 막는다.
    private void restore(List<ParticipantDelta> drained) {
        for (ParticipantDelta delta : drained) {
            pending.merge(
                    new Key(delta.eventId(), delta.userId()),
                    new AggregationDelta(delta.totalHoldMs(), delta.claimSuccessCnt(), delta.maxReignMs()),
                    AggregationDelta::merge
            );
        }
    }

    // 이벤트별 사용자별 집계를 pending 맵에서 구분하기 위한 복합 key
    record Key(Long eventId, Long userId) {
    }

    // 한 사용자의 집계 변화량을 누적 보유 시간, 성공 횟수, 최장 reign 기준으로 합치는 값 객체(이름 부터 delta)
    record AggregationDelta(long totalHoldMs, int claimSuccessCnt, long maxReignMs) {

        // reign 종료로 생긴 보유 시간 증가분을 delta로 만든다.
        static AggregationDelta ofHold(long durationMs) {
            return new AggregationDelta(durationMs, 0, durationMs);
        }

        // 찬탈 성공 1회를 delta로 만든다.
        static AggregationDelta ofClaim() {
            return new AggregationDelta(0L, 1, 0L);
        }

        // 같은 사용자의 여러 delta를 하나의 누적값으로 합침 (ConcurrentHashMap.merge를 위한 메서드)
        AggregationDelta merge(AggregationDelta other) {
            return new AggregationDelta(
                    totalHoldMs + other.totalHoldMs,
                    claimSuccessCnt + other.claimSuccessCnt,
                    Math.max(maxReignMs, other.maxReignMs)
            );
        }
    }
}

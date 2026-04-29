package com.sst.flaggame.domain.throne.service;

import com.sst.flaggame.domain.throne.dto.ParticipantDelta;
import com.sst.flaggame.domain.throne.repository.EventParticipantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AggregationCacheTest {

    @Mock
    private EventParticipantRepository eventParticipantRepository;

    private AggregationCache aggregationCache;

    @BeforeEach
    void setUp() {
        aggregationCache = new AggregationCache(eventParticipantRepository);
    }

    @Test
    void addReignEndWhenSystemUserThenIgnored() {
        aggregationCache.addReignEnd(1L, 1L, 100L);

        assertThat(pending()).isEmpty();
    }

    @Test
    void addReignEndWhenSameKeyThenMergesDelta() {
        aggregationCache.addReignEnd(1L, 2L, 100L);
        aggregationCache.addReignEnd(1L, 2L, 300L);
        aggregationCache.addClaimSuccess(1L, 2L);

        AggregationCache.AggregationDelta delta = pending()
                .get(new AggregationCache.Key(1L, 2L));
        assertThat(delta.totalHoldMs()).isEqualTo(400L);
        assertThat(delta.claimSuccessCnt()).isEqualTo(1);
        assertThat(delta.maxReignMs()).isEqualTo(300L);
    }

    @Test
    void flushWhenPendingEmptyThenSkipsRepository() {
        aggregationCache.flush();

        verify(eventParticipantRepository, never()).applyDeltaBatch(anyList());
    }

    @Test
    void flushWhenPendingExistsThenDrainsWithSingleBatchCall() {
        aggregationCache.addReignEnd(1L, 2L, 100L);
        aggregationCache.addClaimSuccess(1L, 2L);
        aggregationCache.addReignEnd(1L, 3L, 50L);

        ArgumentCaptor<List<ParticipantDelta>> captor = ArgumentCaptor.forClass(List.class);

        aggregationCache.flush();

        verify(eventParticipantRepository).applyDeltaBatch(captor.capture());
        assertThat(pending()).isEmpty();
        assertThat(captor.getValue()).containsExactlyInAnyOrder(
                new ParticipantDelta(1L, 2L, 100L, 1, 100L),
                new ParticipantDelta(1L, 3L, 50L, 0, 50L)
        );
    }

    @Test
    void flushWhenNewMergeArrivesDuringFlushThenKeepsNewPendingDelta() {
        aggregationCache.addReignEnd(1L, 2L, 100L);

        doAnswer(invocation -> {
            aggregationCache.addReignEnd(1L, 2L, 50L);
            return null;
        }).when(eventParticipantRepository).applyDeltaBatch(anyList());

        aggregationCache.flush();

        AggregationCache.AggregationDelta remaining = pending().get(new AggregationCache.Key(1L, 2L));
        assertThat(remaining).isNotNull();
        assertThat(remaining.totalHoldMs()).isEqualTo(50L);
        assertThat(remaining.claimSuccessCnt()).isZero();
        assertThat(remaining.maxReignMs()).isEqualTo(50L);
    }

    @Test
    void flushWhenRepositoryFailsThenRestoresPending() {
        aggregationCache.addReignEnd(1L, 2L, 100L);
        doThrow(new IllegalStateException("boom")).when(eventParticipantRepository).applyDeltaBatch(anyList());

        assertThatThrownBy(() -> aggregationCache.flush())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        AggregationCache.AggregationDelta restored = pending().get(new AggregationCache.Key(1L, 2L));
        assertThat(restored).isNotNull();
        assertThat(restored.totalHoldMs()).isEqualTo(100L);
    }

    @Test
    void forceFlushAndFinalFlushDelegateToFlush() {
        aggregationCache.addReignEnd(1L, 2L, 100L);

        aggregationCache.forceFlush();
        verify(eventParticipantRepository).applyDeltaBatch(anyList());

        aggregationCache.addClaimSuccess(1L, 2L);
        aggregationCache.finalFlush();
        verify(eventParticipantRepository, times(2)).applyDeltaBatch(anyList());
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<AggregationCache.Key, AggregationCache.AggregationDelta> pending() {
        return (ConcurrentHashMap<AggregationCache.Key, AggregationCache.AggregationDelta>)
                ReflectionTestUtils.getField(aggregationCache, "pending");
    }
}

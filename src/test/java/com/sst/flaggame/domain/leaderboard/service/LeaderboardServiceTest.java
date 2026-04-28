package com.sst.flaggame.domain.leaderboard.service;

import com.sst.flaggame.domain.event.entity.Event;
import com.sst.flaggame.domain.event.service.EventService;
import com.sst.flaggame.domain.event.repository.EventRepository;
import com.sst.flaggame.domain.leaderboard.entity.LeaderboardSnapshot;
import com.sst.flaggame.domain.leaderboard.repository.LeaderboardSnapshotRepository;
import com.sst.flaggame.domain.throne.dto.ParticipantRow;
import com.sst.flaggame.domain.throne.repository.EventParticipantRepository;
import com.sst.flaggame.domain.throne.service.AggregationCache;
import com.sst.flaggame.domain.user.Role;
import com.sst.flaggame.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeaderboardServiceTest {

    private final EventService eventService = mock(EventService.class);
    private final EventRepository eventRepository = mock(EventRepository.class);
    private final EventParticipantRepository eventParticipantRepository = mock(EventParticipantRepository.class);
    private final LeaderboardSnapshotRepository leaderboardSnapshotRepository = mock(LeaderboardSnapshotRepository.class);
    private final AggregationCache aggregationCache = mock(AggregationCache.class);

    private final LeaderboardService leaderboardService = new LeaderboardService(
            eventService,
            eventRepository,
            eventParticipantRepository,
            leaderboardSnapshotRepository,
            aggregationCache
    );

    @Test
    void snapshotLeaderboardWhenNoActiveEventThenNoOp() {
        when(eventService.getCurrentActiveEventId()).thenReturn(null);

        leaderboardService.snapshotLeaderboard();

        verify(aggregationCache, never()).forceFlush();
        verify(leaderboardSnapshotRepository, never()).saveAllIgnore(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void snapshotLeaderboardWhenEventNotRunningThenNoOp() {
        when(eventService.getCurrentActiveEventId()).thenReturn(1L);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(createEndedEvent()));

        leaderboardService.snapshotLeaderboard();

        verify(aggregationCache, never()).forceFlush();
        verify(leaderboardSnapshotRepository, never()).saveAllIgnore(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void snapshotLeaderboardWhenRunningThenFlushesReadsAndStoresSnapshots() {
        when(eventService.getCurrentActiveEventId()).thenReturn(1L);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(createRunningEvent()));
        when(eventParticipantRepository.findTopByEventIdOrderByTotalHoldDesc(1L, 100))
                .thenReturn(List.of(
                        new ParticipantRow(10L, 500L, 3, 250L),
                        new ParticipantRow(11L, 300L, 2, 200L)
                ));

        leaderboardService.snapshotLeaderboard();

        InOrder inOrder = inOrder(aggregationCache, eventParticipantRepository, leaderboardSnapshotRepository);
        inOrder.verify(aggregationCache).forceFlush();
        inOrder.verify(eventParticipantRepository).findTopByEventIdOrderByTotalHoldDesc(1L, 100);

        ArgumentCaptor<List<LeaderboardSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(leaderboardSnapshotRepository).saveAllIgnore(captor.capture());

        List<LeaderboardSnapshot> snapshots = captor.getValue();
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).getRankNo()).isEqualTo(1);
        assertThat(snapshots.get(1).getRankNo()).isEqualTo(2);
        assertThat(snapshots.get(0).getCapturedAt().getSecond()).isZero();
        assertThat(snapshots.get(0).getCapturedAt().getNano()).isZero();
        assertThat(snapshots.get(0).getCapturedAt().getMinute() % 5).isZero();
    }

    @Test
    void truncateTo5MinutesRoundsDownToBoundary() {
        LocalDateTime truncated = LeaderboardService.truncateTo5Minutes(
                LocalDateTime.of(2026, 4, 28, 12, 3, 42)
        );

        assertThat(truncated).isEqualTo(LocalDateTime.of(2026, 4, 28, 12, 0, 0));
    }

    private Event createRunningEvent() {
        User admin = createUser(900L, Role.ADMIN);
        Event event = Event.builder()
                .name("running")
                .durationH(1)
                .cooldownMs(3_000)
                .createdBy(admin)
                .build();
        event.start(LocalDateTime.now().minusMinutes(1));
        ReflectionTestUtils.setField(event, "id", 1L);
        return event;
    }

    private Event createEndedEvent() {
        Event event = createRunningEvent();
        event.end(LocalDateTime.now());
        return event;
    }

    private User createUser(Long id, Role role) {
        User user = User.builder()
                .githubId(id + 1000)
                .login("user-" + id)
                .role(role)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}

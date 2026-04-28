package com.sst.flaggame.domain.leaderboard.service;

import com.sst.flaggame.domain.event.entity.EventStatus;
import com.sst.flaggame.domain.event.repository.EventRepository;
import com.sst.flaggame.domain.event.service.EventService;
import com.sst.flaggame.domain.leaderboard.entity.LeaderboardSnapshot;
import com.sst.flaggame.domain.leaderboard.repository.LeaderboardSnapshotRepository;
import com.sst.flaggame.domain.throne.dto.ParticipantRow;
import com.sst.flaggame.domain.throne.repository.EventParticipantRepository;
import com.sst.flaggame.domain.throne.service.AggregationCache;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final EventService eventService;
    private final EventRepository eventRepository;
    private final EventParticipantRepository eventParticipantRepository;
    private final LeaderboardSnapshotRepository leaderboardSnapshotRepository;
    private final AggregationCache aggregationCache;

    @Scheduled(cron = "0 */5 * * * *")
    public void snapshotLeaderboard() {
        Long activeEventId = eventService.getCurrentActiveEventId();
        if (activeEventId == null) {
            return;
        }

        var runningEvent = eventRepository.findById(activeEventId)
                .filter(event -> event.getStatus() == EventStatus.RUNNING);
        if (runningEvent.isEmpty()) {
            return;
        }

        aggregationCache.forceFlush();

        List<ParticipantRow> topRows =
                eventParticipantRepository.findTopByEventIdOrderByTotalHoldDesc(activeEventId, 100);
        LocalDateTime capturedAt = truncateTo5Minutes(LocalDateTime.now());
        leaderboardSnapshotRepository.saveAllIgnore(toSnapshots(activeEventId, capturedAt, topRows));

        // TODO Day 6: publish leaderboard snapshot via SSE.
    }

    static LocalDateTime truncateTo5Minutes(LocalDateTime dateTime) {
        ZoneId zoneId = ZoneId.systemDefault();
        long epochSecond = dateTime.atZone(zoneId).toEpochSecond();
        long truncated = epochSecond - (epochSecond % 300);
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(truncated), zoneId);
    }

    private List<LeaderboardSnapshot> toSnapshots(Long eventId, LocalDateTime capturedAt, List<ParticipantRow> rows) {
        List<LeaderboardSnapshot> snapshots = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            ParticipantRow row = rows.get(i);
            snapshots.add(new LeaderboardSnapshot(
                    eventId,
                    i + 1,
                    capturedAt,
                    row.userId(),
                    row.totalHoldMs()
            ));
        }
        return snapshots;
    }
}

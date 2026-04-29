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


// transactional 어노테이션을 사용하지 않은 이유
//

@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final EventService eventService;
    private final EventRepository eventRepository;
    private final EventParticipantRepository eventParticipantRepository;
    private final LeaderboardSnapshotRepository leaderboardSnapshotRepository;
    private final AggregationCache aggregationCache;

    // 스냅샷을 바탕으로 5분 단위로 저장
    // 동작은 이하의 순서와 같다.
    // 1. @Scheduled(cron = "0 */5 * * * *")에 의해 스케쥴 된다.
    // 2. event를 확인한다
    // 3. 집계를 위해, 메모리 캐쉬를 강제로 flush한다
    // 4. 그리고, 원본 데이터인 event_participants에서 Top 100 조회 한다
    // 5. captureAt기준으로 truncate한다
    // 6. 그걸 바탕으로 snapshot에 저장한다.
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
        // 강제 flush
        aggregationCache.forceFlush();
        //원본 데이터
        List<ParticipantRow> topRows =
                eventParticipantRepository.findTopByEventIdOrderByTotalHoldDesc(activeEventId, 100);
        LocalDateTime capturedAt = truncateTo5Minutes(LocalDateTime.now());
        leaderboardSnapshotRepository.saveAllIgnore(toSnapshots(activeEventId, capturedAt, topRows));

        // TODO Day 6: publish leaderboard snapshot via SSE.
    }

    // 현재 시간을 5분 단위로 짜르기.
    // 12시 8분이다 -> 12시 5분으로 컷
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

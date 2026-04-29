package com.sst.flaggame.domain.leaderboard.repository;

import com.sst.flaggame.domain.leaderboard.entity.LeaderboardSnapshot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class LeaderboardSnapshotRepositoryImpl implements LeaderboardSnapshotRepositoryCustom {

    private static final long SYSTEM_USER_ID = 1L;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void saveAllIgnore(List<LeaderboardSnapshot> snapshots) {
        List<LeaderboardSnapshot> filtered = snapshots.stream()
                .filter(snapshot -> snapshot.getUserId() != SYSTEM_USER_ID)
                .toList();
        if (filtered.isEmpty()) {
            return;
        }

        StringBuilder sql = new StringBuilder("""
                insert ignore into leaderboard_snapshot
                    (event_id, rank_no, user_id, total_hold_ms, captured_at)
                values
                """);

        for (int i = 0; i < filtered.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(:eventId").append(i)
                    .append(", :rankNo").append(i)
                    .append(", :userId").append(i)
                    .append(", :totalHoldMs").append(i)
                    .append(", :capturedAt").append(i)
                    .append(")");
        }

        Query query = entityManager.createNativeQuery(sql.toString());
        for (int i = 0; i < filtered.size(); i++) {
            LeaderboardSnapshot snapshot = filtered.get(i);
            query.setParameter("eventId" + i, snapshot.getEventId());
            query.setParameter("rankNo" + i, snapshot.getRankNo());
            query.setParameter("userId" + i, snapshot.getUserId());
            query.setParameter("totalHoldMs" + i, snapshot.getTotalHoldMs());
            query.setParameter("capturedAt" + i, snapshot.getCapturedAt());
        }
        query.executeUpdate();
    }
}

package com.sst.flaggame.domain.throne.repository;

import com.sst.flaggame.domain.throne.dto.ParticipantDelta;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class EventParticipantRepositoryImpl implements EventParticipantRepositoryCustom {

    private static final long SYSTEM_USER_ID = 1L;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void applyDeltaBatch(List<ParticipantDelta> deltas) {
        List<ParticipantDelta> filtered = deltas.stream()
                .filter(delta -> delta.userId() != SYSTEM_USER_ID)
                .toList();
        if (filtered.isEmpty()) {
            return;
        }

        StringBuilder sql = new StringBuilder("""
                insert into event_participants
                    (event_id, user_id, total_hold_ms, claim_success_cnt, longest_reign_ms)
                values
                """);

        for (int i = 0; i < filtered.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(:eventId").append(i)
                    .append(", :userId").append(i)
                    .append(", :hold").append(i)
                    .append(", :claim").append(i)
                    .append(", :max").append(i)
                    .append(")");
        }

        sql.append("""
                 on duplicate key update
                    total_hold_ms = total_hold_ms + values(total_hold_ms),
                    claim_success_cnt = claim_success_cnt + values(claim_success_cnt),
                    longest_reign_ms = greatest(longest_reign_ms, values(longest_reign_ms))
                """);

        Query query = entityManager.createNativeQuery(sql.toString());
        for (int i = 0; i < filtered.size(); i++) {
            ParticipantDelta delta = filtered.get(i);
            query.setParameter("eventId" + i, delta.eventId());
            query.setParameter("userId" + i, delta.userId());
            query.setParameter("hold" + i, delta.totalHoldMs());
            query.setParameter("claim" + i, delta.claimSuccessCnt());
            query.setParameter("max" + i, delta.maxReignMs());
        }
        query.executeUpdate();
    }
}

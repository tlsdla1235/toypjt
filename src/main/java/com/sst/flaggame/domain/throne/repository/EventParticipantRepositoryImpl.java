package com.sst.flaggame.domain.throne.repository;

import com.sst.flaggame.domain.throne.dto.ParticipantDelta;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


// write back을 하려고 하는데, jdbcTemplate을 이용하여 bulk insert를 할 수 있지만,
// 현재 프로젝트가 jpa에 의존적이기 때문에, 같은 의도를 jpa의 native query로 구현함
// (mysql같은 경우에는 id값 등에 대해 auto identity로 정의하면 jpa 단에서 bulk insert를 할 수 없음)
@Repository
public class EventParticipantRepositoryImpl implements EventParticipantRepositoryCustom {

    private static final long SYSTEM_USER_ID = 1L;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void applyDeltaBatch(List<ParticipantDelta> deltas) {

        // 시스템 유저가 참여한 기록 필터링
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

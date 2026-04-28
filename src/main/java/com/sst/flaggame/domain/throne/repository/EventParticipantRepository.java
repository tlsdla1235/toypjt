package com.sst.flaggame.domain.throne.repository;

import com.sst.flaggame.domain.throne.dto.ParticipantRow;
import com.sst.flaggame.domain.throne.entity.EventParticipant;
import com.sst.flaggame.domain.throne.entity.EventParticipantId;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EventParticipantRepository extends JpaRepository<EventParticipant, EventParticipantId>, EventParticipantRepositoryCustom {

    @Query("""
            select new com.sst.flaggame.domain.throne.dto.ParticipantRow(
                ep.userId,
                ep.totalHoldMs,
                ep.claimSuccessCnt,
                ep.longestReignMs
            )
            from EventParticipant ep
            where ep.eventId = :eventId
              and ep.userId <> 1
            order by ep.totalHoldMs desc, ep.userId asc
            """)
    List<ParticipantRow> findTopByEventIdOrderByTotalHoldDesc(
            @Param("eventId") Long eventId,
            Pageable pageable
    );

    default List<ParticipantRow> findTopByEventIdOrderByTotalHoldDesc(Long eventId, int limit) {
        return findTopByEventIdOrderByTotalHoldDesc(eventId, PageRequest.of(0, limit));
    }

    @Query("""
            select new com.sst.flaggame.domain.throne.dto.ParticipantRow(
                ep.userId,
                ep.totalHoldMs,
                ep.claimSuccessCnt,
                ep.longestReignMs
            )
            from EventParticipant ep
            where ep.eventId = :eventId
              and ep.userId <> 1
            order by ep.claimSuccessCnt desc, ep.userId asc
            """)
    List<ParticipantRow> findMostClaimsRows(
            @Param("eventId") Long eventId,
            Pageable pageable
    );

    default Optional<ParticipantRow> findMostClaims(Long eventId) {
        return findMostClaimsRows(eventId, PageRequest.of(0, 1)).stream().findFirst();
    }
}

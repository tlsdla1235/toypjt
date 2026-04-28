package com.sst.flaggame.domain.throne.repository;

import com.sst.flaggame.domain.throne.entity.ThroneReign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface ThroneReignRepository extends JpaRepository<ThroneReign, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ThroneReign tr
               set tr.endedAt = :endedAt,
                   tr.durationMs = :durationMs
             where tr.id = :reignId
               and tr.endedAt is null
            """)
    int closeOpenReign(
            @Param("reignId") Long reignId,
            @Param("endedAt") LocalDateTime endedAt,
            @Param("durationMs") Long durationMs
    );
}

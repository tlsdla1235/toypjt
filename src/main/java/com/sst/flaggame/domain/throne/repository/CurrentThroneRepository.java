package com.sst.flaggame.domain.throne.repository;

import com.sst.flaggame.domain.throne.entity.CurrentThrone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CurrentThroneRepository extends JpaRepository<CurrentThrone, Long> {

    @Query("""
            select ct
            from CurrentThrone ct
            join fetch ct.reign
            join fetch ct.user
            where ct.eventId = :eventId
            """)
    Optional<CurrentThrone> findDetailByEventId(@Param("eventId") Long eventId);
}

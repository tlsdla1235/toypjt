package com.sst.flaggame.domain.throne.repository;

import com.sst.flaggame.domain.throne.entity.Cooldown;
import com.sst.flaggame.domain.throne.entity.CooldownId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface CooldownRepository extends JpaRepository<Cooldown, CooldownId> {

    Optional<Cooldown> findByEventIdAndUserId(Long eventId, Long userId);

    @Query("""
            select c
            from Cooldown c
            where c.eventId = :eventId
              and c.userId = :userId
              and c.expiresAt > :now
            """)
    Optional<Cooldown> findActive(
            @Param("eventId") Long eventId,
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = """
                    insert into cooldowns (event_id, user_id, expires_at)
                    values (:eventId, :userId, :expiresAt)
                    on duplicate key update expires_at = values(expires_at)
                    """,
            nativeQuery = true
    )
    void upsert(
            @Param("eventId") Long eventId,
            @Param("userId") Long userId,
            @Param("expiresAt") LocalDateTime expiresAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from Cooldown c
            where c.expiresAt < :threshold
            """)
    int deleteExpired(@Param("threshold") LocalDateTime threshold);
}

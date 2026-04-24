package com.sst.flaggame.domain.event.repository;

import com.sst.flaggame.domain.event.entity.Event;
import com.sst.flaggame.domain.event.entity.EventStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findFirstByStatus(EventStatus status);

    List<Event> findAllByStatusAndEndsAtLessThanEqual(
            EventStatus status,
            LocalDateTime now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Event> findAllByStatus(EventStatus status);
}

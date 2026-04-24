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

    /*
        특정 상태의 이벤트를 찾는 함수인데,
        현재 running 중인 이벤트를 찾기 위해서 사용하는 메서드
        서버가 재시작했을때, 재시작하기 위한 용도
     */
    Optional<Event> findFirstByStatus(EventStatus status);


    /*
        자동종료 스케쥴러용 메서드
        phase1단계에서는, flag 탈취에 대해, 이벤트 종료가 되었는지 검사를 하기때문에, phase1에서 비관적 lock을 걸 필요 x
     */
    List<Event> findAllByStatusAndEndsAtLessThanEqual(
            EventStatus status,
            LocalDateTime now
    );

    /*
        제약조건 중 하나인, running 중인 이벤트는 하나 밖에 없다는 제약조건을 충족하기 위한 것으로,
        방어적으로 비관적락을 검.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Event> findAllByStatus(EventStatus status);
}

package com.sst.flaggame.domain.throne.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * cooldown 테이블의 복합 key를 해결하기 위한 테이블
 */

public class CooldownId implements Serializable {

    private Long eventId;
    private Long userId;

    public CooldownId() {
    }

    public CooldownId(Long eventId, Long userId) {
        this.eventId = eventId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CooldownId that)) {
            return false;
        }
        return Objects.equals(eventId, that.eventId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, userId);
    }
}

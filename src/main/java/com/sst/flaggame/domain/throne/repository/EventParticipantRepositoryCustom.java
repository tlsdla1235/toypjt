package com.sst.flaggame.domain.throne.repository;

import com.sst.flaggame.domain.throne.dto.ParticipantDelta;

import java.util.List;

public interface EventParticipantRepositoryCustom {

    void applyDeltaBatch(List<ParticipantDelta> deltas);
}

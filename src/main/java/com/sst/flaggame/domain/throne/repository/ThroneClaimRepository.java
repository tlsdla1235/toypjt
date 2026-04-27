package com.sst.flaggame.domain.throne.repository;

import com.sst.flaggame.domain.throne.entity.ThroneClaim;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ThroneClaimRepository extends JpaRepository<ThroneClaim, Long> {
}

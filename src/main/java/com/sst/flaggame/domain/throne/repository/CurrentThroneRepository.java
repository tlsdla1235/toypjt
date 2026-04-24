package com.sst.flaggame.domain.throne.repository;

import com.sst.flaggame.domain.throne.entity.CurrentThrone;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CurrentThroneRepository extends JpaRepository<CurrentThrone, Long> {
}

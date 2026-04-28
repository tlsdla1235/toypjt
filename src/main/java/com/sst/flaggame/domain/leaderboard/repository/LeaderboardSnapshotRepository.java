package com.sst.flaggame.domain.leaderboard.repository;

import com.sst.flaggame.domain.leaderboard.entity.LeaderboardSnapshot;
import com.sst.flaggame.domain.leaderboard.entity.LeaderboardSnapshotId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaderboardSnapshotRepository extends JpaRepository<LeaderboardSnapshot, LeaderboardSnapshotId>, LeaderboardSnapshotRepositoryCustom {
}

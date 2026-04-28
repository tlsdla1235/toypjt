package com.sst.flaggame.domain.leaderboard.repository;

import com.sst.flaggame.domain.leaderboard.entity.LeaderboardSnapshot;

import java.util.List;

public interface LeaderboardSnapshotRepositoryCustom {

    void saveAllIgnore(List<LeaderboardSnapshot> snapshots);
}

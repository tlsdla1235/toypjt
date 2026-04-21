package com.sst.flaggame.domain.user.repository;

import com.sst.flaggame.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}

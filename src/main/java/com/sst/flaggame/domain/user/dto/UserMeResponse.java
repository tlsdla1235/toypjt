package com.sst.flaggame.domain.user.dto;

import com.sst.flaggame.domain.user.entity.User;

import java.time.LocalDateTime;

public record UserMeResponse(
        Long id,
        String login,
        String avatarUrl,
        String role,
        boolean blocked,
        LocalDateTime createdAt
) {
    public static UserMeResponse from(User user) {
        return new UserMeResponse(
                user.getId(),
                user.getLogin(),
                user.getAvatarUrl(),
                user.getRole().name(),
                user.isBlocked(),
                user.getCreatedAt()
        );
    }
}

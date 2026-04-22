package com.sst.flaggame.common.security;

public record AppUserPrincipal(Long userId, String role) implements java.security.Principal {
    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}

package com.sst.flaggame.common.security;

import com.sst.flaggame.domain.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
@Slf4j
public class JwtProvider {
    private final SecretKey secretKey;
    private final long accessTokenExpiry;

    public JwtProvider(@Value("${app.jwt.secret}") String secret,
                            @Value("${app.jwt.access-ttl-sec}") long accessTokenExpiry) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiry=accessTokenExpiry;
    }

    private String generateToken(Long userId, long expiry, String tokenType, Role role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId)) //누구의 토큰인가
                .claim("type", tokenType) //AT, RT 구분
                .claim("role", role.getKey())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiry * 1000))
                .signWith(secretKey)
                .compact();
    }

    public String generateAT(Long userId, Role role) {
        return generateToken(userId, accessTokenExpiry, "ACCESS", role);
    }

    public long getUserId(String token) {
        Claims claim = parseClaims(token);
        return Long.parseLong(claim.getSubject());
    }

    public String getRole(String token) {
        Claims claim = parseClaims(token);
        return claim.get("role", String.class);
    }

    public boolean validate(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("만료된 토큰입니다");
        } catch (JwtException e) {
            log.warn("유효하지 않은 토큰입니다: {}", e.getMessage());
        }
        return false;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

package com.sst.flaggame.common.config;

import com.sst.flaggame.common.logging.UserIdMdcFilter;
import com.sst.flaggame.common.security.JwtFilter;
import com.sst.flaggame.common.security.OAuth2SuccessHandler;
import com.sst.flaggame.domain.auth.service.CustomOAuth2UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final UserIdMdcFilter userIdMdcFilter;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 1. CSRF 설정 (현재는 개발/테스트 편의를 위해 disable. 추후 명세서 13.7에 따라 CookieCsrfTokenRepository 적용 권장)
                .csrf(csrf -> csrf.disable())

                // 2. 세션 관리: JWT를 사용하므로 상태를 저장하지 않는(STATELESS) 설정 필수
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // 3. 인가(Authorization) 규칙 설정
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login/**", "/oauth2/**").permitAll() // 로그인 관련 경로 허용
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")           // 관리자 전용 API
                        .anyRequest().authenticated()                                // 나머지는 전부 인증 필요
                )

                // 4. OAuth2 로그인 설정 (앞서 정리했던 내용)
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService) // DB 저장 로직
                        )
                        .successHandler(oAuth2SuccessHandler)         // JWT 발급 및 쿠키 굽기 로직
                )

                // 5. 필터 체인 순서 명시적 조립 (⭐ 가장 핵심)
                // ① JwtFilter가 제일 먼저 실행되어 Request에서 토큰을 빼내 SecurityContext를 채움
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)

                // ② UserIdMdcFilter는 JwtFilter 바로 뒤(After)에 실행되어 채워진 Context를 읽고 로깅 MDC 세팅
                .addFilterAfter(userIdMdcFilter, JwtFilter.class);

        return http.build();
    }
}
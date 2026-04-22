package com.sst.flaggame.common.security.handler;

import com.sst.flaggame.common.security.CustomOAuth2User;
import com.sst.flaggame.common.security.JwtProvider;
import com.sst.flaggame.domain.user.Role;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtProvider jwtProvider;

    /*
       프론트엔드 URL 주입 나중에 프론트 엔드 작업할때 살릴 예정. 지금은 개발 과정
     */

//    @Value("${app.frontend.url:http://localhost:5173}")
//    private String frontendUrl;

//    @Override
//    public void onAuthenticationSuccess(HttpServletRequest request,
//                                        HttpServletResponse response,
//                                        Authentication authentication)
//            throws IOException, ServletException {
//        CustomOAuth2User oAuth2User = (CustomOAuth2User) authentication.getPrincipal();
//        Long userId = oAuth2User.getUserId();
//        String roleStr = oAuth2User.getRole();
//
//        String accessToken = jwtProvider.generateAT(userId, Role.valueOf(roleStr));
//        log.info("OAuth2 Login Success - UserId: {}", userId);
//        String redirectUrl = frontendUrl + "/oauth/callback?token=" + accessToken;
//        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
//    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {
        CustomOAuth2User oAuth2User = (CustomOAuth2User) authentication.getPrincipal();
        Long userId = oAuth2User.getUserId();
        String roleStr = oAuth2User.getRole();

        String accessToken = jwtProvider.generateAT(userId, Role.valueOf(roleStr));
        log.info("OAuth2 Login Success - UserId: {}", userId);

        // 디버깅용: JSON 응답으로 토큰 직접 반환 (프론트 완성 후 리다이렉트로 전환)
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"accessToken\":\"" + accessToken + "\", \"userId\":" + userId + "}"
        );
    }
}
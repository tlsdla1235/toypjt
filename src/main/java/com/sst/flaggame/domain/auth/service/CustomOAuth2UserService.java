package com.sst.flaggame.domain.auth.service;

import com.sst.flaggame.common.security.CustomOAuth2User;
import com.sst.flaggame.domain.user.Role;
import com.sst.flaggame.domain.user.entity.User;
import com.sst.flaggame.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        Map<String, Object> attributes = oAuth2User.getAttributes();

        Long githubId = ((Number) attributes.get("id")).longValue();
        String login = (String) attributes.get("login");
        String avatarUrl = (String) attributes.get("avatar_url");

        log.info("GitHub Login Attempt - githubId: {}, login: {}", githubId, login);

        User user = findOrCreateUser(githubId, login, avatarUrl);

        if (user.isBlocked()) {
            log.warn("차단된 사용자의 OAuth2 로그인 시도입니다. userId={}, githubId={}", user.getId(), githubId);
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("blocked_user"),
                    "차단된 사용자입니다."
            );
        }

        user.updateProfile(login, avatarUrl);

        return new CustomOAuth2User(user.getId(), user.getRole().name(), attributes);
    }

    private User findOrCreateUser(Long githubId, String login, String avatarUrl) {
        return userRepository.findByGithubId(githubId)
                .orElseGet(() -> createUserSafely(githubId, login, avatarUrl));
    }

    private User createUserSafely(Long githubId, String login, String avatarUrl) {
        try {
            return userRepository.save(
                    User.builder()
                            .githubId(githubId)
                            .login(login)
                            .avatarUrl(avatarUrl)
                            .role(Role.USER)
                            .build()
            );
        } catch (DataIntegrityViolationException e) {
            log.warn("동시 로그인으로 인한 githubId 중복 저장 충돌이 발생해 재조회합니다. githubId={}", githubId);
            return userRepository.findByGithubId(githubId)
                    .orElseThrow(() -> e);
        }
    }
}

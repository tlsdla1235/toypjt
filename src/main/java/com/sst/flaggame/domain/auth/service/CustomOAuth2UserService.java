package com.sst.flaggame.domain.auth.service;

import com.sst.flaggame.common.security.CustomOAuth2User;
import com.sst.flaggame.domain.user.Role;
import com.sst.flaggame.domain.user.entity.User;
import com.sst.flaggame.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
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
        // Spring Security가 알아서 GitHub에서 유저 정보를 가져옴
        OAuth2User oAuth2User = super.loadUser(userRequest);
        Map<String, Object> attributes = oAuth2User.getAttributes();

        // 명세서(users 테이블)에 맞게 정보 추출
        Long githubId = ((Number) attributes.get("id")).longValue();
        String login = (String) attributes.get("login");
        String avatarUrl = (String) attributes.get("avatar_url");

        log.info("GitHub Login Attempt - githubId: {}, login: {}", githubId, login);

        // 3. DB 저장 또는 업데이트
        User user = userRepository.findByGithubId(githubId)
                .orElseGet(() -> userRepository.save(
                        User.builder()
                                .githubId(githubId)
                                .login(login)
                                .avatarUrl(avatarUrl)
                                .role(Role.USER) // 기본 권한 USER
                                .build()
                ));

        // 기존 유저의 닉네임이나 아바타가 변경되었을 수 있으므로 업데이트 로직 (선택사항)
        user.updateProfile(login, avatarUrl);

        // 4. 필터와 동일한 규격의 Principal 리턴
        return new CustomOAuth2User(user.getId(), user.getRole().name(), attributes);
    }
}